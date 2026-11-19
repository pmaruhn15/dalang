package de.dalang.nav.navigation

import de.dalang.nav.util.GeoUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fortschritt entlang der Route: projiziert GPS-Fixes auf die Routen-Polyline und führt die
 * kumulierte Distanz [s] (m ab Routenstart). Daraus: aktueller Step, Distanz zum Manöver ENTLANG
 * der Route, Restdistanz/-zeit, Ankunft, Off-Route und Koppelnavigation bei GPS-Ausfall (Tunnel).
 *
 * Why: Vorher Luftlinie zu Manöverpunkten — Step-Advance 25 m vor der Kreuzung (rote Ampel),
 * Sprung über Wende + Kreisverkehr, "Ziel erreicht" auf der Gegenfahrbahn (Testfahrt 18.11.).
 *
 * Matching: nur in einem Fenster ab dem letzten Fortschritt (kleine Rücksprünge erlaubt);
 * Segmente gegen die GPS-Fahrtrichtung (> 90°) zählen nicht, so matchen Gegenfahrbahn und Rückweg
 * einer Wende nicht. Unter den Kandidaten gewinnt kleinster Seitenabstand + Abweichung von der
 * erwarteten Position (s + v·dt). Der erste Fix einer Route sucht global (übernommene Alternative
 * beginnt am alten Start). Manöverpunkte werden einmalig der Reihe nach auf der Polyline verortet.
 *
 * Reines Kotlin, Zeiten in ms (Wanduhr). Logging über [log] (MainViewModel → CrashLogger).
 */
/**
 * Voransage-Schwellen, die bei Distanz [d] (entlang Route) jetzt verbraucht werden; leer = nichts sagen.
 * Fällig ist eine Ansage, sobald eine noch offene Schwelle unterschritten ist. Kleinere Schwellen
 * werden mitverbraucht, wenn die Ansage gleich klänge: bei 70 m heißt es gerundet schon "In 50 Metern"
 * — sonst kommt bei 50 m dieselbe Ansage nochmal (Fahrer-Report 23:17, 2× "In 50 Metern: Links abbiegen").
 */
fun consumeAnnouncements(d: Double, thresholds: List<Int>, done: Set<Int>): List<Int> =
    if (thresholds.none { d <= it && it !in done }) emptyList()
    else thresholds.filter { d <= it || d < it * 1.5 }

class RouteProgress(val route: Route, private val log: (String) -> Unit = {}) {

    companion object {
        const val MAX_LATERAL_M = 60.0          // weiter weg = nicht auf der Route
        const val MAX_HEADING_DIFF_DEG = 90.0   // GPS-Kurs vs. Segment; darüber Gegenrichtung
        const val MIN_HEADING_SPEED_MS = 3.0    // darunter ist der GPS-Kurs Rauschen
        const val BACK_TOLERANCE_M = 50.0       // erlaubter Rücksprung (GPS-Rauschen, Tunnel-Überschätzung)
        const val AHEAD_BASE_M = 100.0          // Suchfenster nach vorn: s + 2·v·dt + 100 m
        const val ALONG_PENALTY = 0.2           // Kosten (m) je m Abweichung von s + v·dt
        const val PASS_MARGIN_M = 10.0          // Manöver gilt erst so weit dahinter als passiert
        const val OFF_ROUTE_MISSES = 3          // so viele Fixes in Folge ohne Match = Off-Route
        const val ARRIVAL_REMAINING_M = 30.0    // Restroute entlang der Straße
        const val LOCATE_TOL_M = 20.0           // Manöverpunkt so nah an der Polyline = gefunden
        const val GOOD_FIX_ACCURACY_M = 50f     // schlechtere Fixes zählen nicht als GPS-Empfang
        // Koppelnavigation (Tunnel)
        const val GPS_LOSS_MS = 3000L           // so lange kein guter Fix -> schätzen
        const val DR_MIN_SPEED_MS = 2.0         // darunter nicht schätzen (Stau/Ampel)
        const val DR_MAX_MS = 300_000L          // max 5 min ...
        const val DR_MAX_M = 8_000.0            // ... bzw. 8 km (längste dt. Straßentunnel)
        const val GRACE_AFTER_DR_MS = 8000L     // erste Fixes nach Tunnel sind oft ungenau: kein Off-Route
        const val PRE_MATCH_START_RADIUS_M = 150.0  // vor dem ersten Match: Off-Route erst weiter weg vom Routenstart
        private const val M_PER_DEG = 6371000.0 * Math.PI / 180
    }

    private val g = route.geometry
    private val nSeg = max(g.size - 1, 0)
    private val cum = DoubleArray(g.size).also { c ->
        for (i in 1 until g.size) c[i] = c[i - 1] + g[i - 1].distanceTo(g[i])
    }
    val lengthM: Double = cum.lastOrNull() ?: 0.0

    /** Lage der Manöverpunkte (Step-Anfänge) auf der Polyline, monoton. */
    private val stepS = DoubleArray(route.steps.size).also { arr ->
        var j0 = 0
        var prev = 0.0
        route.steps.forEachIndexed { i, step ->
            var best: Hit? = null
            for (j in j0 until nSeg) {
                val h = hit(step.maneuver.location, j)
                if (best == null || h.d < best.d) best = h
                // Minimum gefunden und nah genug: nicht weitersuchen (Rückweg einer Wende kommt später vorbei)
                else if (best.d < LOCATE_TOL_M) break
            }
            prev = max(best?.s ?: prev, prev)
            arr[i] = prev
            j0 = best?.seg ?: j0
        }
    }

    // --- Ergebnis ---
    var s = 0.0; private set
    var lateralM = 0.0; private set
    /** Position auf der Route (letzter Match bzw. Schätzung); null bis zum ersten Match. */
    var position: LatLng? = null; private set
    var bearingDeg = 0f; private set
    var stepIndex = 0; private set
    var matched = false; private set
    var estimating = false; private set
    var lastGoodFixMs = 0L; private set

    // --- intern ---
    private var initialized = false
    private var misses = 0
    private var lastMoveMs = 0L
    private var graceUntilMs = 0L
    private val speeds = ArrayDeque<Double>()
    var drSpeedMs = 0.0; private set
    private var drStartMs = 0L
    private var drStartS = 0.0
    private var drExhausted = false
    private var drStartStep = 0
    /** Nach Schätzung: Position kann weit danebenliegen — ab drStartS neu suchen, bis wieder ein Match da ist. */
    private var recovering = false
    // Letzter Fix jeder Qualität: meldet er Stillstand, pausiert die Schätzung (Stau im Tunnel, Ampel in Häuserschlucht)
    private var lastAnyFixMs = 0L
    private var lastAnySpeed: Double? = null

    val remainingM: Double get() = (lengthM - s).coerceAtLeast(0.0)

    /** Distanz entlang der Route bis zum Manöver von Step [i] (Routenende, falls es ihn nicht gibt). */
    fun distanceToStep(i: Int): Double =
        if (i in stepS.indices) (stepS[i] - s).coerceAtLeast(0.0) else remainingM

    val offRoute: Boolean get() = misses >= OFF_ROUTE_MISSES && !estimating && lastMoveMs >= graceUntilMs

    /** Routenende entlang der Straße erreicht (nicht aus Schätzung). Entartete Route (Ziel ≈ Start) = sofort. */
    val atRouteEnd: Boolean get() = !estimating &&
        (lengthM < ARRIVAL_REMAINING_M || matched && remainingM <= ARRIVAL_REMAINING_M)

    /** Restzeit: aktueller Step anteilig nach Strecke, Rest voll (Routing-Zeiten). */
    fun remainingTimeS(): Double {
        val steps = route.steps
        if (steps.isEmpty()) return if (lengthM > 0) route.duration * remainingM / lengthM else 0.0
        var t = 0.0
        for (i in stepIndex until steps.size) {
            val end = if (i + 1 < steps.size) stepS[i + 1] else lengthM
            val len = end - stepS[i]
            t += steps[i].duration * if (len > 0) ((end - max(s, stepS[i])) / len).coerceIn(0.0, 1.0) else 0.0
        }
        return t
    }

    /**
     * Neuer GPS-Fix. [accuracyM] > [GOOD_FIX_ACCURACY_M] zählt nicht als Empfang und wird während
     * der Schätzung ignoriert (manche GNSS-Chips liefern im Tunnel eigene, wegdriftende Positionen).
     */
    fun update(pos: LatLng, gpsBearingDeg: Float?, speedMs: Float?, accuracyM: Float?, nowMs: Long) {
        if (nSeg == 0) return
        val good = accuracyM == null || accuracyM <= GOOD_FIX_ACCURACY_M
        val vOrNull = speedMs?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
        lastAnyFixMs = nowMs
        lastAnySpeed = vOrNull
        if (estimating) {
            if (!good) return
            estimating = false
            recovering = true
            graceUntilMs = nowMs + GRACE_AFTER_DR_MS
            log("GPS zurück nach ${(nowMs - drStartMs) / 1000}s, ${(s - drStartS).toInt()}m geschätzt (s=${s.toInt()})")
        }
        val v = vOrNull ?: 0.0
        if (good) {
            lastGoodFixMs = nowMs
            drExhausted = false
            speeds.addLast(v)
            if (speeds.size > 3) speeds.removeFirst()
        }
        val dt = if (lastMoveMs > 0) ((nowMs - lastMoveMs) / 1000.0).coerceIn(0.0, 30.0) else 0.0
        lastMoveMs = nowMs
        val heading = gpsBearingDeg?.takeIf { v >= MIN_HEADING_SPEED_MS }?.toDouble()
        val sPred = s + v * dt
        // Nach Schätzung kann die Wahrheit irgendwo zwischen Tunneleinfahrt und Schätzung (oder davor) liegen
        val from = if (!initialized) 0.0 else if (recovering) drStartS - BACK_TOLERANCE_M else s - BACK_TOLERANCE_M
        val to = if (initialized) sPred + AHEAD_BASE_M + v * dt else lengthM
        val penalty = if (initialized && !recovering) ALONG_PENALTY else 0.0

        var best: Hit? = null
        var bestCost = Double.MAX_VALUE
        var nearest = Double.MAX_VALUE
        var wrongWay = false
        for (j in segAt(from)..segAt(to)) {
            if (cum[j + 1] - cum[j] < 0.1) continue  // Doppelpunkt: keine Richtung
            val h = hit(pos, j)
            nearest = min(nearest, h.d)
            if (h.d > MAX_LATERAL_M) continue
            if (heading != null && angleDiff(heading, segBearing(j)) > MAX_HEADING_DIFF_DEG) {
                wrongWay = true
                continue
            }
            val cost = h.d + penalty * abs(h.s - sPred)
            if (cost < bestCost) { bestCost = cost; best = h }
        }

        if (best == null) {
            matched = false
            lateralM = nearest
            // Vor dem ersten Match (Start auf Parkplatz/in Tiefgarage > 60 m neben der Straße) nur zählen,
            // wenn man sich vom Routenstart entfernt — sonst Recalc-Schleife mit immer derselben Route.
            if (initialized || pos.distanceTo(g[0]) > PRE_MATCH_START_RADIUS_M) misses++
            if (misses == 1 || misses == OFF_ROUTE_MISSES) {
                log("kein Match (${misses}x): nächstes Segment ${nearest.toInt()}m" +
                    (if (wrongWay) ", nahe Segmente gegen Fahrtrichtung (GPS ${heading?.toInt()}°)" else "") +
                    ", s=${s.toInt()}/${lengthM.toInt()}")
            }
            return
        }
        if (!initialized) log("Start: s=${best.s.toInt()}/${lengthM.toInt()}m, seitlich ${best.d.toInt()}m")
        initialized = true
        matched = true
        misses = 0
        s = best.s
        lateralM = best.d
        position = pointOf(best.seg, best.t)
        bearingDeg = segBearing(best.seg).toFloat()
        if (recovering) {
            recovering = false
            // Manöver, die nur die Schätzung passiert hat, wieder freigeben
            if (stepIndex > drStartStep) log("Schätzung lag daneben: Step $stepIndex -> neu ab $drStartStep bei s=${s.toInt()}")
            stepIndex = drStartStep
        }
        advanceSteps()
    }

    /**
     * 1-Hz-Takt: bei GPS-Ausfall s mit dem zuletzt gemessenen Tempo (Median der letzten 3 Fixes)
     * entlang der Route vorschieben. true = [position] ist eine neue Schätzung.
     * Bewusst kein Accelerometer/keine Tempo-Kalibrierung: Einbaulage unbekannt, Doppelintegration driftet.
     */
    fun deadReckon(nowMs: Long): Boolean {
        // Ein aktueller (auch ungenauer) Fix meldet Stillstand: nicht schätzen bzw. Schätzung anhalten
        val standing = nowMs - lastAnyFixMs <= GPS_LOSS_MS && (lastAnySpeed ?: Double.MAX_VALUE) < DR_MIN_SPEED_MS
        if (standing) {
            if (estimating) lastMoveMs = nowMs
            return false
        }
        if (!estimating) {
            if (!matched || recovering || drExhausted || lastGoodFixMs == 0L || nowMs - lastGoodFixMs <= GPS_LOSS_MS) return false
            val v = speeds.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }.coerceAtMost(70.0)
            // Auch das letzte Tempo prüfen: beim Abbremsen hinkt der Median hinterher.
            if (v < DR_MIN_SPEED_MS || (speeds.lastOrNull() ?: 0.0) < DR_MIN_SPEED_MS) return false
            estimating = true
            drSpeedMs = v
            drStartMs = lastMoveMs
            drStartS = s
            drStartStep = stepIndex
            log("kein guter GPS-Fix seit ${nowMs - lastGoodFixMs}ms — Schätzung startet bei s=${s.toInt()}, v=${(v * 3.6).toInt()} km/h")
        }
        if (nowMs - drStartMs > DR_MAX_MS || s - drStartS > DR_MAX_M) {
            estimating = false
            drExhausted = true
            recovering = true
            log("Schätzung abgebrochen (Limit) nach ${(nowMs - drStartMs) / 1000}s, ${(s - drStartS).toInt()}m")
            return false
        }
        // Ab letztem Fix bzw. letzter Schätzung hochrechnen
        s = min(s + drSpeedMs * ((nowMs - lastMoveMs) / 1000.0).coerceIn(0.0, 5.0), lengthM)
        lastMoveMs = nowMs
        val j = segAt(s)
        val len = cum[j + 1] - cum[j]
        position = pointOf(j, if (len > 0) ((s - cum[j]) / len).coerceIn(0.0, 1.0) else 0.0)
        bearingDeg = segBearing(j).toFloat()
        lateralM = 0.0
        advanceSteps()
        return true
    }

    fun debugLine(): String {
        val next = stepIndex + 1
        val nextTxt = route.steps.getOrNull(next)?.let { "${it.maneuver.type} in ${distanceToStep(next).toInt()}m" } ?: "Ziel"
        return "RouteProgress: s=${s.toInt()}/${lengthM.toInt()}m, Rest ${remainingM.toInt()}m, seitlich ${lateralM.toInt()}m, " +
            "Step $stepIndex/${route.steps.size}, nächstes $nextTxt, " +
            (if (matched) "matched" else "KEIN Match ($misses)") +
            (if (estimating) ", SCHÄTZUNG seit ${(lastMoveMs - drStartMs) / 1000}s v=${(drSpeedMs * 3.6).toInt()} km/h" else "")
    }

    // --- intern ---

    private fun advanceSteps() {
        while (stepIndex + 1 < stepS.size && stepS[stepIndex + 1] + PASS_MARGIN_M <= s) {
            stepIndex++
            log("Manöver $stepIndex (${route.steps[stepIndex].maneuver.type}) passiert: s=${s.toInt()}, Manöver bei ${stepS[stepIndex].toInt()}" +
                (if (estimating) " (geschätzt)" else ""))
        }
    }

    private class Hit(val seg: Int, val t: Double, val d: Double, val s: Double)

    /** Projektion von p auf Segment j (lokal eben, für Segmentlängen < einige km genau genug). */
    private fun hit(p: LatLng, j: Int): Hit {
        val a = g[j]
        val b = g[j + 1]
        val kx = M_PER_DEG * cos(Math.toRadians(a.lat))
        val bx = (b.lng - a.lng) * kx
        val by = (b.lat - a.lat) * M_PER_DEG
        val px = (p.lng - a.lng) * kx
        val py = (p.lat - a.lat) * M_PER_DEG
        val len2 = bx * bx + by * by
        val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
        val dx = px - t * bx
        val dy = py - t * by
        return Hit(j, t, sqrt(dx * dx + dy * dy), cum[j] + t * (cum[j + 1] - cum[j]))
    }

    /** Größtes Segment j mit cum[j] <= dist. */
    private fun segAt(dist: Double): Int {
        var lo = 0
        var hi = nSeg - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= dist) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun pointOf(j: Int, t: Double): LatLng {
        val a = g[j]
        val b = g[j + 1]
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    private fun segBearing(j: Int) = GeoUtils.calculateBearing(g[j].lat, g[j].lng, g[j + 1].lat, g[j + 1].lng)

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
