package de.dalang.nav.navigation

import de.dalang.nav.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Lauf: ./gradlew testDebugUnitTest
 * Ampel- und Wende-Fälle nutzen GPS-Fixes der Testfahrt 18.11. (Reports 23:04:16 und 23:18:59);
 * die Routen-Geometrie ist daraus nachgebaut (Original-Polyline steht nicht im Report).
 */
class RouteProgressTest {

    private class Fix(val pos: LatLng, val speed: Float, val course: Float)

    private fun fix(lat: Double, lng: Double, speed: Double, course: Double) =
        Fix(LatLng(lat, lng), speed.toFloat(), course.toFloat())

    private fun step(type: String, at: LatLng, modifier: String? = null) =
        RouteStep("", 0.0, 0.0, Maneuver(type, modifier, at), emptyList())

    private fun route(geometry: List<LatLng>, vararg steps: RouteStep) =
        Route(distance = 0.0, duration = 0.0, geometry = geometry, steps = steps.toList())

    // Lokale Ebene um (48.0, 11.0): x Ost, y Nord in Metern
    private val mPerDeg = 6371000.0 * Math.PI / 180
    private fun xy(x: Double, y: Double) = LatLng(48.0 + y / mPerDeg, 11.0 + x / (mPerDeg * cos(Math.toRadians(48.0))))

    private var t = 1_000_000L
    private fun RouteProgress.feed(f: Fix, accuracy: Float = 5f) {
        t += 1000
        update(f.pos, f.course, f.speed, accuracy, t)
    }

    /** Fixes entlang einer Polyline von Distanz [from] bis [to] in [step]-m-Schritten (1 Fix/s). */
    private fun along(geom: List<LatLng>, from: Double, to: Double, step: Double): List<Fix> {
        val cum = DoubleArray(geom.size)
        for (i in 1 until geom.size) cum[i] = cum[i - 1] + geom[i - 1].distanceTo(geom[i])
        val out = mutableListOf<Fix>()
        var d = from
        while (d <= minOf(to, cum.last())) {
            var j = 0
            while (j < geom.size - 2 && cum[j + 1] < d) j++
            val a = geom[j]
            val b = geom[j + 1]
            val f = ((d - cum[j]) / (cum[j + 1] - cum[j])).coerceIn(0.0, 1.0)
            val p = LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            out += Fix(p, step.toFloat(), GeoUtils.calculateBearing(a.lat, a.lng, b.lat, b.lng).toFloat())
            d += step
        }
        return out
    }

    // --- 1: rote Ampel < 25 m vor Linksabbiegen (Report 23:04:16: App sagte schon "rechts abbiegen") ---

    private val approach = listOf(
        fix(48.178957, 11.515804, 13.19, 307.8), fix(48.179028, 11.515663, 13.16, 306.6), fix(48.179099, 11.515522, 13.09, 305.9),
        fix(48.179166, 11.515382, 12.84, 305.1), fix(48.179234, 11.515241, 12.68, 304.8), fix(48.179296, 11.515101, 12.56, 304.6),
        fix(48.179359, 11.514963, 12.4, 304.5), fix(48.179421, 11.514826, 12.37, 304.4), fix(48.179484, 11.514688, 12.46, 303.3),
        fix(48.179544, 11.514544, 12.51, 301.9), fix(48.179602, 11.514399, 12.57, 301.4), fix(48.179664, 11.514252, 12.64, 302.3),
        fix(48.179726, 11.514110, 12.73, 303.7), fix(48.179792, 11.513966, 12.84, 304.8), fix(48.179862, 11.513820, 12.94, 305.2),
        fix(48.179931, 11.513675, 12.99, 305.6), fix(48.179998, 11.513534, 12.87, 306.2), fix(48.180069, 11.513397, 12.76, 307.1),
        fix(48.180139, 11.513264, 12.71, 308.1), fix(48.180214, 11.513132, 12.65, 308.9), fix(48.180286, 11.513000, 12.51, 309.8),
        fix(48.180356, 11.512873, 12.23, 310.5), fix(48.180424, 11.512757, 11.78, 311.0), fix(48.180492, 11.512641, 11.51, 311.3),
        fix(48.180560, 11.512524, 11.27, 311.4), fix(48.180628, 11.512416, 10.93, 311.6), fix(48.180691, 11.512308, 10.57, 311.8),
        fix(48.180753, 11.512214, 10.09, 311.8), fix(48.180810, 11.512121, 9.42, 311.8), fix(48.180859, 11.512035, 8.34, 311.4),
        fix(48.180898, 11.511961, 7.17, 310.9), fix(48.180932, 11.511903, 5.85, 311.0), fix(48.180958, 11.511857, 4.54, 311.1),
        fix(48.180975, 11.511822, 3.32, 311.3), fix(48.180990, 11.511801, 2.48, 311.4), fix(48.180998, 11.511781, 1.48, 311.5),
    )

    @Test
    fun redLightBeforeTurnDoesNotAdvance() {
        val left = LatLng(48.18111, 11.51166)     // "Turn left onto Baubergerstraße" aus dem Report
        val right = LatLng(48.1786, 11.50824)     // "Turn right onto Großbeerenstraße"
        val end = LatLng(48.179352, 11.506987)
        val geom = approach.map { it.pos } + left + right + end
        val r = route(geom, step("depart", geom[0]), step("turn", left, "left"), step("turn", right, "right"), step("arrive", end))
        val p = RouteProgress(r)

        approach.forEach { p.feed(it) }
        // 30 s an der Ampel mit GPS-Rauschen ±4 m, Tempo 0
        repeat(30) { i ->
            val jitter = xy(4.0 * cos(i * 1.7), 4.0 * cos(i * 2.3))
            val stop = approach.last().pos
            p.feed(Fix(LatLng(stop.lat + jitter.lat - 48.0, stop.lng + jitter.lng - 11.0), 0f, 311.5f))
            assertEquals("Step-Advance an der Ampel", 0, p.stepIndex)
        }
        val d = p.distanceToStep(1)
        assertTrue("Distanz zum Linksabbiegen entlang Route: $d", d in 5.0..30.0)
        assertEquals("left", NavigationState(route = r, currentStepIndex = p.stepIndex).nextRelevantStep?.maneuver?.modifier)

        // Grün: abbiegen. Erst 10 m hinter dem Manöverpunkt gilt es als passiert.
        val sLeft = p.s + d
        val drive = along(geom, p.s, sLeft + 60, 5.0)
        drive.forEach { f ->
            p.feed(f)
            if (p.s < sLeft + RouteProgress.PASS_MARGIN_M - 1) assertEquals(0, p.stepIndex)
        }
        assertEquals(1, p.stepIndex)
        assertEquals(left.distanceTo(right) - 60, p.distanceToStep(2), 8.0)
    }

    // --- 2: Wende + Kreisverkehr eng, Vorbeifahrt am Haus auf der Gegenfahrbahn (Reports 23:17–23:18) ---

    private val loop = listOf(
        fix(48.180949, 11.509203, 4.79, 85.5), fix(48.180952, 11.509267, 4.8, 85.0), fix(48.180955, 11.509332, 4.89, 85.4),
        fix(48.180958, 11.509399, 4.97, 85.3), fix(48.180961, 11.509465, 4.84, 85.1), fix(48.180964, 11.509530, 4.88, 84.9),
        fix(48.180967, 11.509595, 4.86, 85.2), fix(48.180970, 11.509660, 4.74, 86.0), fix(48.180972, 11.509718, 3.83, 87.2),
        fix(48.180974, 11.509766, 3.33, 88.7), fix(48.180976, 11.509811, 3.38, 86.3), fix(48.180983, 11.509853, 2.97, 73.1),
        fix(48.180998, 11.509884, 2.83, 50.5), fix(48.181020, 11.509900, 2.68, 20.0), fix(48.181047, 11.509898, 2.77, 352.0),
        fix(48.181072, 11.509878, 2.76, 325.1), fix(48.181091, 11.509846, 2.75, 296.6), fix(48.181097, 11.509806, 2.62, 266.2),
        fix(48.181094, 11.509765, 2.85, 243.2), fix(48.181088, 11.509721, 3.18, 249.0), fix(48.181082, 11.509673, 3.45, 246.5),
        fix(48.181077, 11.509621, 3.71, 248.2), fix(48.181073, 11.509565, 3.91, 250.7), fix(48.181070, 11.509507, 4.01, 253.0),
        fix(48.181069, 11.509448, 4.14, 254.3), fix(48.181067, 11.509388, 4.29, 255.1), fix(48.181066, 11.509326, 4.53, 256.8),
        fix(48.181065, 11.509261, 4.65, 258.6), fix(48.181065, 11.509195, 4.71, 260.3), fix(48.181065, 11.509129, 4.78, 261.2),
        fix(48.181065, 11.509062, 4.79, 261.5), fix(48.181064, 11.508995, 4.79, 261.8), fix(48.181063, 11.508929, 4.8, 262.2),
        fix(48.181061, 11.508862, 4.86, 261.4), fix(48.181058, 11.508794, 4.98, 262.7), fix(48.181068, 11.508716, 5.25, 263.2),
        fix(48.181065, 11.508643, 5.47, 263.7), fix(48.181061, 11.508567, 5.64, 264.0), fix(48.181055, 11.508491, 5.76, 263.9),
        fix(48.181051, 11.508410, 5.78, 264.0), fix(48.181049, 11.508339, 5.53, 263.9), fix(48.181043, 11.508265, 5.38, 263.6),
        fix(48.181039, 11.508189, 5.53, 263.2), fix(48.181033, 11.508114, 5.61, 260.7), fix(48.181022, 11.508040, 5.64, 255.5),
        fix(48.181001, 11.507971, 5.61, 247.6), fix(48.180977, 11.507909, 5.67, 240.4), fix(48.180947, 11.507847, 5.68, 233.9),
        fix(48.180911, 11.507785, 5.8, 227.9), fix(48.180870, 11.507724, 6.07, 222.7), fix(48.180828, 11.507669, 6.23, 218.0),
        fix(48.180783, 11.507619, 6.6, 214.3), fix(48.180733, 11.507566, 6.87, 213.1), fix(48.180683, 11.507508, 6.7, 213.5),
        fix(48.180635, 11.507453, 6.5, 213.8), fix(48.180588, 11.507400, 6.55, 213.8), fix(48.180539, 11.507341, 6.72, 214.4),
        fix(48.180491, 11.507282, 6.86, 215.2), fix(48.180442, 11.507219, 6.95, 216.1), fix(48.180393, 11.507159, 6.84, 216.4),
        fix(48.180347, 11.507097, 6.68, 216.2), fix(48.180299, 11.507043, 6.48, 215.5), fix(48.180253, 11.506989, 6.45, 214.7),
        fix(48.180206, 11.506933, 6.48, 214.7), fix(48.180161, 11.506880, 6.21, 215.0), fix(48.180117, 11.506832, 6.11, 214.8),
        fix(48.180077, 11.506786, 5.94, 214.8), fix(48.180037, 11.506740, 5.85, 215.0), fix(48.179994, 11.506691, 5.73, 215.3),
        fix(48.179954, 11.506648, 5.44, 215.3), fix(48.179916, 11.506609, 5.12, 215.1), fix(48.179877, 11.506572, 5.13, 214.9),
        fix(48.179840, 11.506534, 5.21, 214.5), fix(48.179795, 11.506495, 5.39, 214.5), fix(48.179752, 11.506459, 5.48, 215.1),
        fix(48.179710, 11.506422, 5.28, 215.8), fix(48.179671, 11.506392, 5.09, 215.9), fix(48.179634, 11.506357, 4.88, 213.1),
        fix(48.179593, 11.506333, 4.86, 205.0), fix(48.179550, 11.506320, 4.81, 196.1), fix(48.179510, 11.506312, 4.82, 187.2),
        fix(48.179464, 11.506322, 4.81, 175.8), fix(48.179424, 11.506345, 4.82, 165.0), fix(48.179379, 11.506381, 5.01, 154.6),
        fix(48.179341, 11.506426, 5.28, 143.6), fix(48.179306, 11.506483, 5.46, 132.7), fix(48.179277, 11.506548, 5.64, 121.2),
        fix(48.179258, 11.506619, 5.68, 109.8), fix(48.179253, 11.506689, 5.66, 97.9), fix(48.179253, 11.506770, 5.7, 89.8),
        fix(48.179251, 11.506848, 5.74, 89.9),
    )

    @Test
    fun uturnRoundaboutAndOppositeCarriageway() {
        // Route = gefahrener Weg bis in den Kreisverkehr, dann zurück auf der ~20 m entfernten
        // Gegenfahrbahn (Fixes 22:18:31..19 um 20 m nach SO versetzt) bis vors Haus.
        val ringExit = LatLng(48.17940, 11.50695)
        val back = (72 downTo 60).map { LatLng(loop[it].pos.lat - 1.032e-4, loop[it].pos.lng + 2.21e-4) }
        val geom = loop.map { it.pos } + ringExit + back
        val house = LatLng(48.1801353, 11.5074418)   // Ziel aus dem Report
        val r = route(
            geom,
            step("depart", geom[0]),
            step("turn", loop[14].pos, "uturn"),
            step("exit roundabout", loop[77].pos),
            step("arrive", geom.last())
        )
        val p = RouteProgress(r)

        loop.forEachIndexed { i, f ->
            p.feed(f)
            assertTrue("matched bei Fix $i: ${p.debugLine()}", p.matched)
            assertFalse("Off-Route bei Fix $i", p.offRoute)
            assertFalse("Ziel erreicht bei Fix $i: ${p.debugLine()}", p.atRouteEnd)
            when {
                i <= 14 -> assertEquals("vor/an der Wende (Fix $i)", 0, p.stepIndex)  // alt: Fix 8 schon Step 2
                i in 20..77 -> assertEquals("zwischen Wende und Kreisverkehr (Fix $i)", 1, p.stepIndex)
                i >= 82 -> assertEquals("im Kreisverkehr (Fix $i)", 2, p.stepIndex)
            }
            if (i == 56) {
                // Am Haus vorbei auf der Gegenfahrbahn: Hauspunkt per Luftlinie nah, Route noch lang
                assertTrue(f.pos.distanceTo(house) < 50)
                assertTrue("Rest entlang Route ${p.remainingM}", p.remainingM > 200)
            }
        }

        // Rest der Route: Kreisverkehr verlassen, Gegenfahrbahn zurück zum Haus
        along(geom, p.s + 5, p.lengthM, 5.0).forEach { f ->
            p.feed(f)
            assertTrue(p.matched)
            assertEquals(p.remainingM <= RouteProgress.ARRIVAL_REMAINING_M, p.atRouteEnd)
        }
        assertTrue(p.atRouteEnd)
    }

    // --- 3: Gegenfahrbahn synthetisch, auch im Stand ---

    @Test
    fun oppositeCarriagewayNeverMatchesRouteEnd() {
        // Ost auf y=0 bis x=600, Wende auf y=20, zurück West bis x=300 (Ende)
        val geom = (0..600 step 50).map { xy(it.toDouble(), 0.0) } + (600 downTo 300 step 50).map { xy(it.toDouble(), 20.0) }
        val r = route(geom, step("depart", geom[0]), step("turn", xy(600.0, 20.0), "uturn"), step("arrive", geom.last()))
        val p = RouteProgress(r)
        along(geom, 0.0, 300.0, 10.0).forEach { p.feed(it) }
        assertEquals(300.0, p.s, 2.0)
        assertFalse(p.atRouteEnd)
        // Stillstand ohne verwertbaren Kurs: bleibt auf der eigenen Fahrbahn
        repeat(10) { p.feed(Fix(xy(300.0, 3.0), 0f, 0f)) }
        assertEquals(300.0, p.s, 5.0)
        assertEquals(620.0, p.remainingM, 5.0)
        // GPS-Rauschen näher an der Gegenfahrbahn (8 m vs. 12 m): Fenster hält uns auf der eigenen
        repeat(5) { p.feed(Fix(xy(300.0, 12.0), 0f, 0f)) }
        assertEquals(300.0, p.s, 5.0)
        // ... auch kurz vor der Wende, wo beide Äste im Suchfenster liegen (Along-Penalty)
        along(geom, 300.0, 580.0, 10.0).forEach { p.feed(it) }
        repeat(5) { p.feed(Fix(xy(580.0, 12.0), 0f, 0f)) }
        assertEquals(580.0, p.s, 5.0)
        assertEquals(0, p.stepIndex)
    }

    // --- 4: Ankunft + Alternative mitten auf der Route (erster Fix sucht global) ---

    @Test
    fun arrivalAndGlobalFirstFix() {
        val geom = (0..1000 step 100).map { xy(it.toDouble(), 0.0) }
        val r = route(geom, step("depart", geom[0]), step("arrive", geom.last()))
        val p = RouteProgress(r)
        p.feed(Fix(xy(600.0, 5.0), 15f, 90f))
        assertEquals(600.0, p.s, 2.0)
        along(geom, 610.0, 960.0, 15.0).forEach { p.feed(it); assertFalse(p.atRouteEnd) }
        p.feed(Fix(xy(975.0, 4.0), 15f, 90f))
        assertTrue(p.atRouteEnd)
    }

    // --- 5: Tunnel (Koppelnavigation) ---

    private val north = (0..30).map { xy(0.0, it * 100.0) }
    private val tunnelRoute = route(north, step("depart", north[0]), step("turn", north[15], "right"), step("arrive", north.last()))

    private fun driveNorth(p: RouteProgress, speed: Double, seconds: Int) {
        repeat(seconds) { i -> p.feed(Fix(xy(0.0, i * speed), speed.toFloat(), 0f)) }
    }

    @Test
    fun tunnelEstimatesAlongRouteAndHandsBack() {
        val p = RouteProgress(tunnelRoute)
        driveNorth(p, 20.0, 20)                       // letzter Fix bei s=380
        val tLast = t
        assertFalse(p.deadReckon(tLast + 3000))       // 3 s Lücke: noch kein Ausfall
        assertTrue(p.deadReckon(tLast + 4000))
        assertTrue(p.estimating)
        assertEquals(460.0, p.s, 1.0)
        for (k in 5..60) assertTrue(p.deadReckon(tLast + k * 1000L))
        assertEquals(380.0 + 60 * 20, p.s, 1.0)
        assertEquals("Manöver bei 1500 m im Tunnel passiert", 1, p.stepIndex)

        // Wegdriftender Chip-Fix mit schlechter Accuracy: ignoriert
        t = tLast + 60_500
        p.update(xy(300.0, 900.0), 0f, 20f, 200f, t)
        assertTrue(p.estimating)
        assertEquals(1580.0, p.s, 1.0)

        // GPS zurück, 20 m hinter der Schätzung: kleiner Rücksprung, kein Off-Route
        t = tLast + 61_000
        p.update(xy(0.0, 1560.0), 0f, 20f, 5f, t)
        assertFalse(p.estimating)
        assertTrue(p.matched)
        assertEquals(1560.0, p.s, 2.0)
        assertFalse(p.offRoute)
        assertEquals(1, p.stepIndex)
        assertFalse(p.deadReckon(t + 1000))
    }

    @Test
    fun tunnelRefusesWhenSlowAndStopsAtLimit() {
        val slow = RouteProgress(tunnelRoute)
        driveNorth(slow, 1.5, 20)
        assertFalse(slow.deadReckon(t + 5000))        // Stau/Ampel: nicht schätzen

        val p = RouteProgress(tunnelRoute)
        driveNorth(p, 5.0, 10)
        val tLast = t
        var k = 4
        while (p.deadReckon(tLast + k * 1000L)) k++
        assertEquals(RouteProgress.DR_MAX_MS / 1000 + 1, k.toLong())
        assertFalse(p.estimating)
        assertFalse(p.atRouteEnd)
    }

    // --- 6: Off-Route seitlich und Falschfahrt ---

    @Test
    fun offRouteByDistanceAndWrongWay() {
        val geom = (0..10).map { xy(it * 100.0, 0.0) }
        val r = route(geom, step("depart", geom[0]), step("arrive", geom.last()))

        val away = RouteProgress(r)
        along(geom, 0.0, 200.0, 10.0).forEach { away.feed(it) }
        listOf(30.0, 70.0, 110.0, 150.0).forEachIndexed { i, y ->
            away.feed(Fix(xy(200.0, y), 10f, 45f))
            assertEquals("nach Fix $i (y=$y)", i >= 3, away.offRoute)
        }

        val wrongWay = RouteProgress(r)
        along(geom, 0.0, 500.0, 10.0).forEach { wrongWay.feed(it) }
        repeat(3) { i -> wrongWay.feed(Fix(xy(490.0 - i * 10, 0.0), 10f, 270f)) }
        assertTrue(wrongWay.offRoute)
    }

    // --- 7: Doppelansage ---

    @Test
    fun announcementsNotDuplicated() {
        val city = listOf(200, 50)
        assertEquals(emptyList<Int>(), consumeAnnouncements(250.0, city, emptySet()))
        assertEquals(listOf(200), consumeAnnouncements(180.0, city, emptySet()))
        assertEquals(emptyList<Int>(), consumeAnnouncements(120.0, city, setOf(200)))
        assertEquals(listOf(200, 50), consumeAnnouncements(49.0, city, setOf(200)))
        // Erste Ansage erst bei 70 m (= gesprochen "In 50 Metern"): 50er-Schwelle gleich mitverbrauchen
        assertEquals(listOf(200, 50), consumeAnnouncements(70.0, city, emptySet()))
        assertEquals(emptyList<Int>(), consumeAnnouncements(45.0, city, setOf(200, 50)))
    }
}
