package de.dalang.nav.navigation

import de.dalang.nav.util.GeoUtils
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offline-Auswertung von Fahrtaufzeichnungen: spielt GPS-Fixes durch den echten [RouteProgress]
 * (inkl. 1-Hz-Koppelnavigations-Ticks mit simulierter Zeit) und misst Tunnel-Genauigkeit
 * (echte und künstlich eingefügte GNSS-Ausfälle) sowie Ansage-Timing.
 *
 * Lauf (Git Bash):
 *   TRIPS_DIR=<Ordner> TRIP_REPORT_DIR=<Ausgabe> ./gradlew testDebugUnitTest --tests '*TripEvaluation' --rerun
 * Ohne TRIPS_DIR wird nichts ausgewertet (CI bleibt grün). Optional: TRIP=<Namensteil>, GOOD_ACC_M (50),
 * OUTAGE_MIN_MS (3000), OUTAGE_MIN_M (50), INJECT_LENS ("30,60,120"), INJECT_STEP_S (30). Konsolen-Zusammenfassung im
 * system-out des Test-Reports, Markdown-Berichte in TRIP_REPORT_DIR (Default app/build/trip-report).
 *
 * Eingaben (rekursiv unter TRIPS_DIR): trip_*.zip bzw. Ordner mit gnss.ndjson (Fahrtenschreiber-Format)
 * und alte track_*.ndjson (1 Hz, keine Steps/IMU; GPS-Spur dient als Route, siehe [TripEval.loadOld]).
 */
class TripEvaluation {
    @Test
    fun evaluate() {
        val dir = File(System.getenv("TRIPS_DIR").also { Assume.assumeNotNull(it) })
        val out = File(System.getenv("TRIP_REPORT_DIR") ?: "build/trip-report")
        val only = System.getenv("TRIP")
        val cfg = TripEval.Cfg(
            goodAcc = System.getenv("GOOD_ACC_M")?.toFloat() ?: RouteProgress.GOOD_FIX_ACCURACY_M,
            minGapMs = System.getenv("OUTAGE_MIN_MS")?.toLong() ?: RouteProgress.GPS_LOSS_MS,
            minOutageM = System.getenv("OUTAGE_MIN_M")?.toDouble() ?: 50.0,
            lens = System.getenv("INJECT_LENS")?.split(",")?.map { it.trim().toInt() } ?: listOf(30, 60, 120),
            stepS = System.getenv("INJECT_STEP_S")?.toInt() ?: 30,
        )
        val trips = TripEval.loadAll(dir).filter { only == null || it.name.contains(only) }
        val results = trips.map { TripEval.analyze(it, cfg) }
        println(TripEval.writeReports(results, out))
    }
}

object TripEval {

    // ---------------- Datenmodell (Zeiten in ms, Basis elapsedRealtime bzw. Aufzeichnungsstart) ----------------

    class Fix(val t: Long, val pos: LatLng, val acc: Float?, val speed: Float?, val bearing: Float?)
    class Status(val t: Long, val sats: Int?)
    class TimedRoute(val t: Long, val reason: String?, val route: Route)
    class Event(val t: Long, val type: String, val text: String?, val data: JSONObject?)
    class Live(val t: Long, val s: Double, val step: Int)
    class Trip(
        val name: String, val format: String, val fixes: List<Fix>, val statuses: List<Status>,
        val routes: List<TimedRoute>, val events: List<Event>, val live: List<Live>,
    )

    class Cfg(
        val goodAcc: Float = RouteProgress.GOOD_FIX_ACCURACY_M,
        val minGapMs: Long = RouteProgress.GPS_LOSS_MS,
        val minOutageM: Double = 50.0,
        val lens: List<Int> = listOf(30, 60, 120),
        val stepS: Int = 30,
        val warmS: Int = 60,
        val modes: List<String> = listOf("drop", "speed"),
    )

    // ---------------- Laden ----------------

    fun loadAll(dir: File): List<Trip> {
        val all = dir.walkTopDown().maxDepth(4).toList()
        val zips = all.filter { it.isFile && it.name.startsWith("trip_") && it.name.endsWith(".zip") }.map { z ->
            ZipFile(z).use { zf ->
                loadNew(z.name.removeSuffix(".zip")) { n ->
                    zf.entries().asSequence().firstOrNull { it.name.substringAfterLast('/') == n }
                        ?.let { e -> zf.getInputStream(e).bufferedReader().use { it.readText() } }
                }
            }
        }
        val dirs = all.filter { it.isDirectory && File(it, "gnss.ndjson").isFile }
            .map { d -> loadNew(d.name) { n -> File(d, n).takeIf { it.isFile }?.readText() } }
        val old = all.filter { it.isFile && it.name.startsWith("track_") && it.name.endsWith(".ndjson") }.map(::loadOld)
        return (zips + dirs + old).filter { it.fixes.size >= 2 && it.routes.isNotEmpty() }.sortedBy { it.name }
    }

    private fun lines(text: String?) = text.orEmpty().lineSequence().filter { it.isNotBlank() }
        .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList()

    private fun JSONObject.num(k: String) = optDouble(k).takeIf { !it.isNaN() }
    private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).ifEmpty { null }
    private fun ms(ns: Long) = ns / 1_000_000

    fun loadNew(name: String, read: (String) -> String?): Trip {
        val fixes = mutableListOf<Fix>()
        val statuses = mutableListOf<Status>()
        for (o in lines(read("gnss.ndjson"))) {
            val t = ms(o.getLong("t"))
            if (o.optString("type") == "status") statuses += Status(t, o.optInt("sats", -1).takeIf { it >= 0 })
            else if (o.has("lat")) fixes += Fix(t, LatLng(o.getDouble("lat"), o.getDouble("lng")),
                o.num("acc")?.toFloat(), o.num("speed")?.toFloat(), o.num("bearing")?.toFloat())
        }
        val routes = lines(read("routes.ndjson")).map { o ->
            val g = o.getJSONArray("geometry")
            val geom = (0 until g.length()).map { LatLng(g.getJSONArray(it).getDouble(0), g.getJSONArray(it).getDouble(1)) }
            val st = o.optJSONArray("steps")
            val steps = (0 until (st?.length() ?: 0)).map { i ->
                val s = st!!.getJSONObject(i)
                RouteStep(s.optString("instruction"), s.optDouble("distance", 0.0), s.optDouble("duration", 0.0),
                    Maneuver(s.optString("type"), s.str("modifier"), LatLng(s.getDouble("lat"), s.getDouble("lng"))),
                    emptyList())
            }
            TimedRoute(ms(o.getLong("t")), o.str("reason"), Route(o.optDouble("distance", 0.0), o.optDouble("duration", 0.0), geom, steps))
        }.filter { it.route.geometry.size >= 2 }
        val events = lines(read("events.ndjson")).map {
            Event(ms(it.getLong("t")), it.optString("type"), it.str("text"), it.optJSONObject("data"))
        }
        val live = lines(read("progress.ndjson")).mapNotNull { o ->
            o.num("s")?.let { Live(ms(o.getLong("t")), it, o.optInt("step", -1)) }
        }
        return Trip(name, "neu", fixes.sortedBy { it.t }, statuses.sortedBy { it.t }, routes.sortedBy { it.t },
            events.sortedBy { it.t }, live.sortedBy { it.t })
    }

    /**
     * Altes Format (track_*.ndjson, t = ms seit Start). Die zugehörige .route.json ist nur die LETZTE Route
     * (bei Recalc überschrieben, deckt meist nur das Fahrtende ab) — daher dient die GPS-Spur selbst
     * (gute Fixes, ≥ 10 m Abstand) als Route.
     */
    fun loadOld(trackFile: File): Trip {
        val fixes = lines(trackFile.readText()).filter { it.has("t") && it.has("lat") }.map {
            Fix(it.getLong("t"), LatLng(it.getDouble("lat"), it.getDouble("lng")),
                it.num("acc")?.toFloat(), it.num("speed")?.toFloat(), it.num("bearing")?.toFloat())
        }.sortedBy { it.t }
        // ponytail: Spur als Route — Fehler entlang der gefahrenen Strecke, Manöver/Ansagen nicht prüfbar
        val geom = mutableListOf<LatLng>()
        for (f in fixes) if ((f.acc ?: 0f) <= RouteProgress.GOOD_FIX_ACCURACY_M && (geom.isEmpty() || geom.last().distanceTo(f.pos) >= 10)) geom += f.pos
        val route = Route(0.0, 0.0, geom, emptyList())
        return Trip(trackFile.name.removeSuffix(".ndjson"), "alt", fixes, emptyList(),
            if (geom.size >= 2) listOf(TimedRoute(Long.MIN_VALUE, "GPS-Spur", route)) else emptyList(), emptyList(), emptyList())
    }

    // ---------------- Wahrheit: GPS unabhängig von RouteProgress auf die Route projiziert ----------------

    private const val M_PER_DEG = 6371000.0 * Math.PI / 180

    class Projector(route: Route) {
        private val g = route.geometry
        val cum = DoubleArray(g.size).also { c -> for (i in 1 until g.size) c[i] = c[i - 1] + g[i - 1].distanceTo(g[i]) }
        private val brg = DoubleArray(g.size - 1) { GeoUtils.calculateBearing(g[it].lat, g[it].lng, g[it + 1].lat, g[it + 1].lng) }

        /** s (m) des nächsten Polyline-Punkts in [from, to], höchstens [maxLat] seitlich, nicht gegen [heading]; NaN = keiner. */
        fun project(p: LatLng, heading: Double?, from: Double, to: Double, maxLat: Double): Double {
            var bestS = Double.NaN
            var bestD = maxLat
            for (j in 0 until g.size - 1) {
                if (cum[j + 1] < from || cum[j] > to || cum[j + 1] - cum[j] < 0.1) continue
                if (heading != null) {
                    val d = abs(heading - brg[j]) % 360
                    if ((if (d > 180) 360 - d else d) > 90) continue
                }
                val a = g[j]
                val b = g[j + 1]
                val kx = M_PER_DEG * cos(Math.toRadians(a.lat))
                val bx = (b.lng - a.lng) * kx
                val by = (b.lat - a.lat) * M_PER_DEG
                val px = (p.lng - a.lng) * kx
                val py = (p.lat - a.lat) * M_PER_DEG
                val t = ((px * bx + py * by) / (bx * bx + by * by)).coerceIn(0.0, 1.0)
                val d = sqrt((px - t * bx).let { it * it } + (py - t * by).let { it * it })
                if (d < bestD) { bestD = d; bestS = cum[j] + t * (cum[j + 1] - cum[j]) }
            }
            return bestS
        }
    }

    const val TRUTH_MAX_LATERAL_M = 40.0

    /** Wahres s je Fix (NaN = schlechter Fix, keine Route oder nicht auf der Route). */
    fun truthTrack(fixes: List<Fix>, routeIdx: IntArray, routes: List<TimedRoute>, goodAcc: Float): DoubleArray {
        val proj = routes.map { Projector(it.route) }
        val out = DoubleArray(fixes.size) { Double.NaN }
        var prevS = Double.NaN
        var prevT = 0L
        var prevR = -2
        fixes.forEachIndexed { i, f ->
            val r = routeIdx[i]
            if (r < 0 || !(f.acc == null || f.acc <= goodAcc)) return@forEachIndexed
            if (r != prevR) { prevS = Double.NaN; prevR = r }
            val dt = (f.t - prevT) / 1000.0
            val global = prevS.isNaN() || dt > 60
            val heading = f.bearing?.takeIf { (f.speed ?: 0f) >= 3f }?.toDouble()
            val s = proj[r].project(f.pos, heading, if (global) 0.0 else prevS - 50, if (global) Double.MAX_VALUE else prevS + 70 * dt + 100, TRUTH_MAX_LATERAL_M)
            if (!s.isNaN()) { out[i] = s; prevS = s; prevT = f.t }
        }
        return out
    }

    // ---------------- Replay durch RouteProgress ----------------

    class Sample(val t: Long, val s: Double, val est: Boolean, val matched: Boolean, val step: Int, val route: Int)
    class Speak(val t: Long, val route: Int, val step: Int, val d: Double, val est: Boolean)

    /** Wie MainViewModel: Fixes per update(), daneben 1-Hz-Takt deadReckon(), Voransagen wie announceNextManeuver(). */
    class Replayer(private val routes: List<TimedRoute>, private val tickPhaseMs: Long = 500) {
        val progresses = arrayOfNulls<RouteProgress>(routes.size)
        val samples = ArrayList<Sample>()
        val fixSample = ArrayList<Int>()
        val speaks = ArrayList<Speak>()
        private var ri = -1
        private var nextTick = Long.MIN_VALUE
        private val announced = HashSet<Pair<LatLng, Int>>()
        val p: RouteProgress? get() = progresses.getOrNull(ri)

        private fun switchRoute(t: Long) {
            while (ri + 1 < routes.size && routes[ri + 1].t <= t) {
                ri++
                progresses[ri] = RouteProgress(routes[ri].route)
            }
        }

        private fun sample(t: Long) {
            val q = p
            samples += if (q == null) Sample(t, Double.NaN, false, false, -1, -1)
            else Sample(t, q.s, q.estimating, q.matched, q.stepIndex, ri)
        }

        /** Takt-Ticks bis ausschließlich [t]. */
        fun advance(t: Long) {
            if (nextTick == Long.MIN_VALUE) nextTick = t + tickPhaseMs
            while (nextTick < t) {
                switchRoute(nextTick)
                p?.let { if (it.deadReckon(nextTick)) announce(it, nextTick) }
                sample(nextTick)
                nextTick += 1000
            }
        }

        fun feed(f: Fix, goodAcc: Float) {
            advance(f.t)
            switchRoute(f.t)
            p?.let { q ->
                val good = f.acc == null || f.acc <= goodAcc
                q.update(f.pos, if (q.estimating && !good) null else f.bearing, f.speed, f.acc, f.t)
                if (!q.offRoute) announce(q, f.t)
            }
            sample(f.t)
            fixSample += samples.size - 1
        }

        private fun announce(q: RouteProgress, t: Long) {
            val steps = q.route.steps
            val r = (q.stepIndex + 1 until steps.size).firstOrNull { steps[it].isRelevantManeuver() && !isInnerStop(steps, it) } ?: return
            val d = q.distanceToStep(r)
            val th = announceDistancesM(steps.getOrNull(r - 1))
            val key = steps[r].maneuver.location
            val consume = consumeAnnouncements(d, th, th.filter { (key to it) in announced }.toSet())
            if (consume.isEmpty()) return
            consume.forEach { announced += key to it }
            speaks += Speak(t, ri, r, d, q.estimating)
        }

        private fun isInnerStop(steps: List<RouteStep>, i: Int) =
            (steps[i].maneuver.type == "arrive" && i < steps.size - 1) || (steps[i].maneuver.type == "depart" && i > 0)
    }

    // ---------------- Auswertung ----------------

    class Outage(
        val t0: Long, val t1: Long, val distM: Double, val sats0: Boolean, val estimated: Boolean,
        val errEnd: Double, val maxErr: Double, val recoverS: Double, val falsePass: Int, val speaks: Int, val early: Int,
    )

    class Injection(val mode: String, val lenS: Int, val cls: String, val v0: Double, val errA: Double, val errB: Double, val errC: Double, val maxA: Double)

    class SpeakCheck(val t: Long, val text: String, val announcedM: Double, val trueM: Double, val speed: Double, val est: Boolean) {
        val devM get() = announcedM - trueM
        val devS get() = if (speed > 1) devM / speed else Double.NaN
    }

    class Result(
        val trip: Trip, val cfg: Cfg, val routeIdx: IntArray, val truth: DoubleArray, val replay: Replayer,
        val outages: List<Outage>, val injections: List<Injection>, val skippedWindows: Int,
        val speakChecks: List<SpeakCheck>, val liveDiff: List<Double>,
    )

    fun analyze(trip: Trip, cfg: Cfg = Cfg()): Result {
        val fx = trip.fixes
        val routeIdx = IntArray(fx.size) { i -> trip.routes.indexOfLast { it.t <= fx[i].t } }
        val truth = truthTrack(fx, routeIdx, trip.routes, cfg.goodAcc)
        val rp = Replayer(trip.routes).apply { fx.forEach { feed(it, cfg.goodAcc) } }
        val (inj, skipped) = injections(trip, routeIdx, truth, cfg)
        val liveDiff = trip.live.mapNotNull { l ->
            val k = rp.samples.binarySearchBy(l.t) { it.t }.let { if (it >= 0) it else -it - 2 }
            rp.samples.getOrNull(k)?.s?.takeIf { !it.isNaN() }?.let { abs(it - l.s) }
        }
        return Result(trip, cfg, routeIdx, truth, rp, outages(trip, routeIdx, truth, rp, cfg), inj, skipped,
            speakChecks(trip, routeIdx, truth, rp), liveDiff)
    }

    private fun good(f: Fix, cfg: Cfg) = f.acc == null || f.acc <= cfg.goodAcc

    /** Wahres s zur Zeit [t], linear zwischen den umliegenden Fixes mit Wahrheit (je max. 3 s entfernt). */
    fun truthAt(fx: List<Fix>, truth: DoubleArray, routeIdx: IntArray, t: Long): Double {
        val k = fx.binarySearchBy(t) { it.t }.let { if (it >= 0) it else -it - 2 }
        var a = k
        while (a >= 0 && truth[a].isNaN()) a--
        var b = maxOf(k, 0)
        while (b < fx.size && (truth[b].isNaN() || fx[b].t < t)) b++
        if (a < 0 || b >= fx.size || t - fx[a].t > 3000 || fx[b].t - t > 3000 || routeIdx[a] != routeIdx[b]) return Double.NaN
        if (fx[b].t == fx[a].t) return truth[a]
        return truth[a] + (truth[b] - truth[a]) * (t - fx[a].t) / (fx[b].t - fx[a].t)
    }

    /** Index des letzten Steps, den die Wahrheit [s] schon passiert hat (wie RouteProgress.advanceSteps). */
    private fun truthStep(p: RouteProgress, s: Double): Int {
        var i = 0
        while (i + 1 < p.route.steps.size && p.stepPositionM(i + 1) + RouteProgress.PASS_MARGIN_M <= s) i++
        return i
    }

    /**
     * Echte Ausfälle: zwei aufeinanderfolgende gute Fixes mehr als minGapMs auseinander und dazwischen mindestens
     * minOutageM bewegt (kürzere Lücken = Stillstand/Logger-Pause). Fehler-Metriken nur, wenn das Replay am
     * Ausfallbeginn auf der Wahrheit lag (sonst war RouteProgress schon vorher off-route).
     */
    fun outages(trip: Trip, routeIdx: IntArray, truth: DoubleArray, rp: Replayer, cfg: Cfg): List<Outage> {
        val fx = trip.fixes
        val goodIdx = fx.indices.filter { good(fx[it], cfg) }
        val out = mutableListOf<Outage>()
        for ((i, j) in goodIdx.zipWithNext()) {
            val t0 = fx[i].t
            val t1 = fx[j].t
            if (t1 - t0 <= cfg.minGapMs || routeIdx[j] < 0) continue
            val sameRoute = routeIdx[i] == routeIdx[j]
            val dist = if (sameRoute && !truth[i].isNaN() && !truth[j].isNaN()) truth[j] - truth[i] else fx[i].pos.distanceTo(fx[j].pos)
            if (dist < cfg.minOutageM) continue
            val startSm = rp.samples[rp.fixSample[i]]
            val startOk = sameRoute && startSm.route == routeIdx[i] && startSm.matched && abs(startSm.s - truth[i]) <= 25
            val inGap = rp.samples.subList(rp.fixSample[i] + 1, rp.fixSample[j])
            val before = inGap.lastOrNull() ?: rp.samples[rp.fixSample[i]]
            val errEnd = if (startOk && before.route == routeIdx[j]) before.s - truth[j] else Double.NaN
            // Wiederfinden: erster Fix ab Ausfallende mit Match und |s - Wahrheit| <= 20 m (max. 60 s)
            var maxErr = abs(errEnd)
            var recover = Double.NaN
            for (k in j until fx.size) {
                if (!startOk || fx[k].t - t1 > 60_000) break
                val sm = rp.samples[rp.fixSample[k]]
                if (truth[k].isNaN() || sm.route != routeIdx[k]) continue
                val e = abs(sm.s - truth[k])
                if (sm.matched && e <= 20) { recover = (fx[k].t - t1) / 1000.0; break }
                maxErr = if (maxErr.isNaN()) e else maxOf(maxErr, e)
            }
            val p = rp.progresses[routeIdx[j]]!!
            val stepEst = inGap.filter { it.route == routeIdx[j] }.maxOfOrNull { it.step } ?: -1
            val falsePass = if (!startOk || truth[j].isNaN()) 0 else maxOf(0, stepEst - truthStep(p, truth[j]))
            val sp = rp.speaks.filter { it.t in (t0 + 1)..t1 && it.est }
            val early = if (!startOk || truth[j].isNaN()) 0 else sp.count { it.route == routeIdx[j] && it.d < p.stepPositionM(it.step) - truth[j] - 1 }
            out += Outage(t0, t1, dist, trip.statuses.any { it.t in t0..t1 && it.sats == 0 }, inGap.any { it.est },
                errEnd, maxErr, recover, falsePass, sp.size, early)
        }
        return out
    }

    /**
     * Künstliche Ausfälle auf Abschnitten mit gutem GPS: Fenster [w0, w0+L) alle stepS s. Replay ab w0 - warmS
     * mit frischem RouteProgress. "drop" = keine Fixes; "speed" = Fixes mit acc 100 m, Position eingefroren,
     * echtem Tempo (Chip mit Sensorfusion) — nur dort greift die Stillstandspause.
     * Baselines: A = RouteProgress, B = Position einfrieren, C = konstantes Tempo des letzten Fixes, ohne Pause/Schwelle.
     */
    fun injections(trip: Trip, routeIdx: IntArray, truth: DoubleArray, cfg: Cfg): Pair<List<Injection>, Int> {
        val fx = trip.fixes
        if (fx.size < 2) return emptyList<Injection>() to 0
        val out = mutableListOf<Injection>()
        var skipped = 0
        val firstIdx = { t: Long -> fx.binarySearchBy(t) { it.t }.let { if (it >= 0) it else -it - 1 } }
        var w0 = fx.first().t + cfg.warmS * 1000L
        while (true) {
            for (len in cfg.lens) {
                val w1 = w0 + len * 1000L
                val i0 = firstIdx(w0 - cfg.warmS * 1000L)
                val iA = firstIdx(w0) - 1
                val iB = firstIdx(w1)
                if (iB >= fx.size || iA < i0) continue
                val r = routeIdx[iA]
                val ok = r >= 0 && (i0..iB).all { good(fx[it], cfg) && !truth[it].isNaN() && routeIdx[it] == r } &&
                    (i0 until iB).all { fx[it + 1].t - fx[it].t <= 2500 }
                if (!ok) continue
                val route = trip.routes[r].route
                val v0 = (fx[iA].speed ?: 0f).toDouble()
                val vMean = (truth[iB] - truth[iA]) * 1000.0 / (fx[iB].t - fx[iA].t)
                val stand = (iA + 1 until iB).any { (fx[it].speed ?: 0f) < 1f }
                val rel = abs(vMean - v0) / maxOf(v0, 2.0)
                val cls = if (stand || rel > 0.5) "stark" else if (rel > 0.2) "mittel" else "konstant"
                for (mode in cfg.modes) {
                    val rp = Replayer(listOf(TimedRoute(Long.MIN_VALUE, null, route)))
                    for (k in i0..iA) rp.feed(fx[k], cfg.goodAcc)
                    val sA = rp.samples.last().s
                    if (!rp.samples.last().matched || abs(sA - truth[iA]) > 25) { skipped++; continue }
                    if (mode == "speed") for (k in iA + 1 until iB) rp.feed(Fix(fx[k].t, fx[iA].pos, 100f, fx[k].speed, null), cfg.goodAcc)
                    rp.advance(fx[iB].t)
                    val win = rp.samples.filter { it.t > fx[iA].t }
                    val eval = win.lastOrNull() ?: continue
                    val tr = truthAt(fx, truth, routeIdx, eval.t)
                    if (tr.isNaN()) continue
                    val maxA = win.maxOf { abs(it.s - truthAt(fx, truth, routeIdx, it.t)).takeIf { e -> !e.isNaN() } ?: 0.0 }
                    val sC = min(sA + v0 * (eval.t - fx[iA].t) / 1000.0, rp.p!!.lengthM)
                    out += Injection(mode, len, cls, v0, eval.s - tr, sA - tr, sC - tr, maxA)
                }
            }
            w0 += cfg.stepS * 1000L
            if (w0 + cfg.lens.min() * 1000L > fx.last().t) break
        }
        return out to skipped
    }

    private val SPOKEN = Regex("""In ([\d.,]+) (Metern|Kilometern)""")

    /** Gesprochene Distanz-Ansage vs. wahre Restdistanz entlang der Route zum Ansagezeitpunkt. */
    fun speakChecks(trip: Trip, routeIdx: IntArray, truth: DoubleArray, rp: Replayer): List<SpeakCheck> {
        val fx = trip.fixes
        return trip.events.filter { it.type == "speak" && it.text != null }.mapNotNull { ev ->
            val m = SPOKEN.find(ev.text!!) ?: return@mapNotNull null
            val announced = m.groupValues[1].replace(',', '.').toDouble() * if (m.groupValues[2] == "Kilometern") 1000 else 1
            val ri = trip.routes.indexOfLast { it.t <= ev.t }
            val p = rp.progresses.getOrNull(ri) ?: return@mapNotNull null
            val steps = p.route.steps
            // Manöver: data.step vom Fahrtenschreiber, sonst nächstes relevantes nach dem Live- bzw. Replay-Step
            val cur = trip.live.lastOrNull { it.t <= ev.t }?.step
                ?: rp.samples.lastOrNull { it.t <= ev.t && it.route == ri }?.step ?: return@mapNotNull null
            val r = ev.data?.optInt("step", -1)?.takeIf { it in steps.indices }
                ?: (cur + 1 until steps.size).firstOrNull { steps[it].isRelevantManeuver() } ?: return@mapNotNull null
            val s = truthAt(fx, truth, routeIdx, ev.t)
            if (s.isNaN()) return@mapNotNull null
            val k = fx.binarySearchBy(ev.t) { it.t }.let { if (it >= 0) it else maxOf(-it - 2, 0) }
            val est = rp.samples.lastOrNull { it.t <= ev.t }?.est ?: false
            SpeakCheck(ev.t, ev.text, announced, p.stepPositionM(r) - s, (fx[k].speed ?: 0f).toDouble(), est)
        }
    }

    // ---------------- Bericht ----------------

    fun pct(xs: List<Double>, p: Double): Double =
        xs.sorted().let { if (it.isEmpty()) Double.NaN else it[((p / 100) * (it.size - 1)).roundToInt()] }

    private fun Double.f(dec: Int = 0) = if (isNaN()) "–" else "%.${dec}f".format(Locale.ROOT, this)
    private fun ms2s(ms: Long) = (ms / 1000.0).f()

    private const val INJ_HEAD = "| Modus | Fenster | Klasse | n | Baseline | P50 | P90 | P95 | Max | Bias (Median, vorzeichenb.) |\n|---|---|---|---|---|---|---|---|---|---|\n"

    /** Tabellenzeilen |Fehler| (m) je Modus × Fensterlänge × Baseline; bei [byClass] zusätzlich je Tempo-Klasse. */
    fun injectionTable(inj: List<Injection>, byClass: Boolean): String = buildString {
        append(INJ_HEAD)
        val groups = inj.groupBy { Triple(it.mode, it.lenS, if (byClass) it.cls else "alle") }.toSortedMap(compareBy({ it.first }, { it.second }, { it.third }))
        for ((k, g) in groups) for ((name, sel) in listOf<Pair<String, (Injection) -> Double>>(
            "A RouteProgress" to { it.errA }, "B einfrieren" to { it.errB }, "C konst. Tempo" to { it.errC }, "A max im Fenster" to { it.maxA })) {
            val e = g.map(sel)
            val a = e.map { abs(it) }
            append("| ${k.first} | ${k.second} s | ${k.third} | ${g.size} | $name | ${pct(a, 50.0).f()} | ${pct(a, 90.0).f()} | ${pct(a, 95.0).f()} | ${a.maxOrNull()?.f() ?: "–"} | ${pct(e, 50.0).f()} |\n")
        }
    }

    /** Zeilen (Fahrt, Fahrtbeginn, Ausfall). */
    private fun outageTable(rows: List<Triple<String, Long, Outage>>): String = buildString {
        append("| Fahrt | Start (s) | Dauer (s) | Strecke (m) | sats=0 | geschätzt | Fehler Ende (m) | max (m) | Wiederfinden (s) | Manöver fälschl. passiert | Ansagen in Schätzung (zu früh) |\n")
        append("|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((name, t0, o) in rows) append("| $name | ${ms2s(o.t0 - t0)} | ${ms2s(o.t1 - o.t0)} | ${o.distM.f()} | ${if (o.sats0) "ja" else "–"} | " +
            "${if (o.estimated) "ja" else "nein"} | ${o.errEnd.f()} | ${o.maxErr.f()} | ${o.recoverS.f()} | ${o.falsePass} | ${o.speaks} (${o.early}) |\n")
    }

    private fun outageRows(r: Result) = r.outages.map { Triple(r.trip.name, r.trip.fixes.first().t, it) }

    fun tripReport(r: Result): String = buildString {
        val fx = r.trip.fixes
        val t0 = fx.first().t
        val goodN = fx.count { good(it, r.cfg) }
        val fixSamples = r.replay.fixSample.map { r.replay.samples[it] }
        val maxGap = fx.zipWithNext { a, b -> b.t - a.t }.maxOrNull() ?: 0
        append("# Fahrt ${r.trip.name}\n\n")
        append("- Format: ${r.trip.format}, Dauer ${ms2s(fx.last().t - t0)} s, ${fx.size} Fixes (${goodN} gut ≤ ${r.cfg.goodAcc.toInt()} m), max. Lücke ${ms2s(maxGap)} s\n")
        append("- Routen: ${r.trip.routes.size} (${r.trip.routes.joinToString { "${it.reason ?: "?"} ${(it.route.geometry.size)} Pkt/${it.route.steps.size} Steps" }})\n")
        append("- Status-Zeilen ${r.trip.statuses.size}, Events ${r.trip.events.size}, Live-Progress ${r.trip.live.size}\n")
        append("- Replay: ${(100.0 * fixSamples.count { it.matched } / fx.size).f()} % der Fixes gematcht, Wahrheit für ${(100.0 * r.truth.count { !it.isNaN() } / fx.size).f()} %, " +
            "${r.replay.speaks.size} simulierte Ansagen\n")
        if (r.liveDiff.isNotEmpty()) append("- Replay vs. Live-App |Δs|: P50 ${pct(r.liveDiff, 50.0).f()} m, P95 ${pct(r.liveDiff, 95.0).f()} m (n=${r.liveDiff.size})\n")
        append("\n## Echte GNSS-Ausfälle (> ${r.cfg.minGapMs} ms ohne guten Fix, ≥ ${r.cfg.minOutageM.toInt()} m bewegt)\n\n")
        append(if (r.outages.isEmpty()) "keine\n" else outageTable(outageRows(r)))
        append("\n## Künstliche Ausfälle (|Fehler| in m am Fensterende, ${r.injections.size} Läufe, ${r.skippedWindows} wegen Fehl-Start verworfen)\n\n")
        append(if (r.injections.isEmpty()) "keine geeigneten Abschnitte\n" else injectionTable(r.injections, false))
        if (r.speakChecks.isNotEmpty()) {
            append("\n## Ansage-Timing\n\n| t (s) | Ansage | angesagt (m) | wahr (m) | Δ (m) | Δ (s) | Schätzung |\n|---|---|---|---|---|---|---|\n")
            for (c in r.speakChecks) append("| ${ms2s(c.t - t0)} | ${c.text} | ${c.announcedM.f()} | ${c.trueM.f()} | ${c.devM.f()} | ${c.devS.f(1)} | ${if (c.est) "ja" else ""} |\n")
        }
    }

    /** Schreibt je Fahrt eine Markdown-Datei + UEBERSICHT.md; liefert die Konsolen-Zusammenfassung. */
    fun writeReports(results: List<Result>, out: File): String {
        out.mkdirs()
        results.forEach { File(out, "${it.trip.name}.md").writeText(tripReport(it)) }
        val inj = results.flatMap { it.injections }
        val outs = results.flatMap(::outageRows)
        val speaks = results.flatMap { it.speakChecks }
        val summary = buildString {
            append("# Fahrt-Auswertung: ${results.size} Fahrten\n\n")
            append("| Fahrt | Format | Dauer (min) | Fixes | echte Ausfälle (geschätzt) | Inj.-Läufe |\n|---|---|---|---|---|---|\n")
            for (r in results) append("| [${r.trip.name}](${r.trip.name}.md) | ${r.trip.format} | ${((r.trip.fixes.last().t - r.trip.fixes.first().t) / 60000.0).f()} | " +
                "${r.trip.fixes.size} | ${r.outages.size} (${r.outages.count { it.estimated }}) | ${r.injections.size} |\n")
            append("\n## Künstliche Ausfälle, alle Fahrten (|Fehler| in m)\n\n")
            append("A = RouteProgress, B = Position einfrieren, C = konstantes Tempo ohne Stillstandspause. " +
                "drop = keine Fixes im Fenster; speed = Fixes mit acc 100 m, eingefrorener Position, echtem Tempo. Bias < 0 = Schätzung hinterher.\n\n")
            append(injectionTable(inj, false))
            append("\n### Nach Tempo-Verlauf im Fenster (konstant ≤ 20 %, mittel, stark > 50 % oder Stillstand)\n\n")
            append(injectionTable(inj, true))
            append("\n## Echte GNSS-Ausfälle (${outs.size})\n\n")
            val e = outs.map { it.third }.filter { it.estimated && !it.errEnd.isNaN() }.map { abs(it.errEnd) }
            append("Mit Schätzung und gültigem Start: ${e.size}, |Fehler Ende| P50 ${pct(e, 50.0).f()} m, P95 ${pct(e, 95.0).f()} m, Max ${(e.maxOrNull() ?: Double.NaN).f()} m\n\n")
            if (outs.isNotEmpty()) append(outageTable(outs))
            if (speaks.isNotEmpty()) {
                val d = speaks.map { it.devM }
                val ds = speaks.map { it.devS }.filter { !it.isNaN() }
                append("\n## Ansage-Timing (${speaks.size} Ansagen)\n\nΔ = angesagt − wahr: P50 ${pct(d, 50.0).f()} m / ${pct(ds, 50.0).f(1)} s, " +
                    "|Δ| P95 ${pct(d.map(::abs), 95.0).f()} m / ${pct(ds.map(::abs), 95.0).f(1)} s\n")
            }
        }
        File(out, "UEBERSICHT.md").writeText(summary)
        return "Bericht: ${out.absolutePath}\n" + injectionTable(inj, false) +
            "echte Ausfälle: ${outs.size}, davon geschätzt ${outs.count { it.third.estimated }}; Ansagen geprüft: ${speaks.size}"
    }
}
