package de.dalang.nav.navigation

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HERE-Strecke → OSM-Route mit gespeicherten Antworten.
 * HERE-Fixtures: here_testfahrt_214226 / here_umweg_154611 = echte HERE-Geometrie aus der App
 * (internal_tracks, Dateien track_…route.json; Testfahrt 2026-11-18 Richtung Bunzlauer Str. bzw. 2026-10-03),
 * als HERE-v8-JSON verpackt (Flexible Polyline, Spans/Actions synthetisch). here_a8_a99 = synthetisch
 * (OSRM-Alternative 5 m versetzt). OSRM-Antworten echt von router.project-osrm.org (2026-11-19).
 * Neu aufzeichnen: OSRM_RECORD=1 ./gradlew testDebugUnitTest
 */
class OsmRouteMatchingTest {

    private val repo = RouteRepository()
    private val dir = File("src/test/resources/osm")
    private val start = LatLng(47.9778, 11.6847)

    private fun here(name: String) = repo.parseHereRoutesWithAlternatives(File(dir, name).readText(), start)!!

    /** Liefert der Reihe nach <scenario>_osrm_<n>.json; mit OSRM_RECORD=1 live holen und speichern. */
    private fun fixtureFetch(scenario: String, urls: MutableList<String>): (String) -> String? = { url ->
        urls += url
        val f = File(dir, "${scenario}_osrm_${urls.size}.json")
        if (System.getenv("OSRM_RECORD") == "1") {
            if (urls.size > 1) Thread.sleep(1100)
            val body = OkHttpClient().newCall(Request.Builder().url(url).header("User-Agent", "DaLang test").build())
                .execute().use { it.body!!.string() }
            f.writeText(body)
        }
        f.takeIf { it.exists() }?.readText()
    }

    private fun RouteStep.lanesString() =
        laneInfo?.lanes?.joinToString(" | ") { it.directions.joinToString("+") + if (it.isRecommended) "*" else "" }

    @Test
    fun testfahrtA8_echteHereGeometrie_SpurenAnGabelungUndKreuz() = runBlocking {
        val hereMain = here("here_testfahrt_214226.json").first
        val urls = mutableListOf<String>()
        val (osm, plain) = repo.matchHereToOsm(hereMain, fixtureFetch("testfahrt", urls))

        assertNotNull("OSM-Anpassung muss klappen", osm); osm!!
        assertTrue("höchstens 2 OSRM-Anfragen: ${urls.size}", urls.size <= 2)
        assertTrue("erste Anfrage ohne Vias", !urls[0].contains("waypoints="))
        assertNotNull(plain)

        // Geometrie OSM-dicht, Dauer/Verkehr von HERE
        assertTrue(osm.geometry.size > hereMain.geometry.size)
        assertEquals(hereMain.duration, osm.duration, 0.01)
        assertEquals(hereMain.typicalDuration, osm.typicalDuration)
        assertEquals(hereMain.duration, osm.steps.sumOf { it.duration }, 1.0)

        // Stau-Segment (HERE-Index 500..600) liegt auf OSM-Indizes an derselben Stelle
        val hereRed = hereMain.trafficSegments.single { it.level == TrafficLevel.RED }
        val osmRed = osm.trafficSegments.single { it.level == TrafficLevel.RED }
        assertTrue(osmRed.endIndex <= osm.geometry.size)
        assertTrue(hereMain.geometry[hereRed.startIndex].distanceTo(osm.geometry[osmRed.startIndex]) < 30)
        assertTrue(hereMain.geometry[hereRed.endIndex].distanceTo(osm.geometry[osmRed.endIndex]) < 30)

        // genau ein depart/arrive
        assertEquals(listOf("depart", "arrive"), osm.steps.map { it.maneuver.type }.filter { it == "depart" || it == "arrive" })
        assertEquals("depart", osm.steps.first().maneuver.type)

        // Gabelung Mittlerer Ring (Testfahrt): eigenes Manöver mit den zwei linken Spuren empfohlen
        val gabelung = osm.steps.single { it.maneuver.location.distanceTo(LatLng(48.17607, 11.56265)) < 30 }
        assertEquals("fork", gabelung.maneuver.type)
        assertEquals("left", gabelung.maneuver.modifier)
        assertEquals("straight* | straight* | slightRight | slightRight", gabelung.lanesString())

        // Kreuz München-Süd (A8/A99): "zwei gehen auf die A8 ... die rechten auf die A99"
        val kreuz = osm.steps.single { it.maneuver.location.distanceTo(LatLng(48.01313, 11.66589)) < 30 }
        assertEquals("fork", kreuz.maneuver.type)
        assertEquals("straight* | straight* | slightRight | slightRight | slightRight", kreuz.lanesString())

        // Ausfahrt Innsbrucker Ring: Spuren aus intersections[0] der Ausfahrt, Richtungen wie NavigationPanel sie kennt
        val ausfahrt = osm.steps.single { it.maneuver.type == "off ramp" && it.maneuver.location.distanceTo(LatLng(48.11134, 11.61543)) < 30 }
        assertEquals("left | left | straight | straight+slightRight* | slightRight* | slightRight*", ausfahrt.lanesString())

        // Rechts in die Dachauer Str.: keine "4x geradeaus"-Spuren einer Vorkreuzung; kein "Abbiegen" bei turn straight
        val dachauer = osm.steps.single { it.instruction == "Dachauer Straße" && it.maneuver.type == "turn" }
        assertEquals("right", dachauer.maneuver.modifier)
        assertNull(dachauer.laneInfo)
        assertTrue(osm.steps.none { it.maneuver.type == "turn" && it.maneuver.modifier == "straight" })

        // Step-Geometrien lückenlos: Ende von Step i = Anfang von Step i+1
        osm.steps.zipWithNext().forEach { (a, b) -> assertEquals(0.0, a.geometry.last().distanceTo(b.geometry.first()), 0.5) }
        // Demo-Server liefert keine classes → Straßentyp unbekannt
        assertTrue(osm.steps.all { it.isMotorway == null })
    }

    @Test
    fun hereNimmtAndereStrecke_stilleViasMitRichtung() = runBlocking {
        val hereMain = here("here_umweg_154611.json").first
        val urls = mutableListOf<String>()
        val (osm, _) = repo.matchHereToOsm(hereMain, fixtureFetch("umweg", urls))

        assertNotNull(osm); osm!!
        assertEquals(2, urls.size)
        val coordCount = urls[1].substringAfter("/driving/").substringBefore("?").split(";").size
        assertTrue(coordCount > 2)
        assertTrue(urls[1].contains("&waypoints=0;${coordCount - 1}"))
        assertEquals(coordCount, urls[1].substringAfter("bearings=").substringBefore("&").split(";").size)
        assertEquals(coordCount, urls[1].substringAfter("radiuses=").substringBefore("&").split(";").size)

        assertEquals(hereMain.distance, osm.distance, hereMain.distance * 0.01)
        assertEquals("keine arrive/depart an Vias", 2, osm.steps.count { it.maneuver.type == "depart" || it.maneuver.type == "arrive" })
        val maxDev = OsmRouteMatcher.deviations(hereMain.geometry, osm.geometry).max()
        assertTrue("HERE-Punkte max $maxDev m neben OSM", maxDev <= OsmRouteMatcher.DEVIATION_M)
    }

    @Test
    fun osmPasstNicht_nullFuerFallbackUndErsteAntwortZurueck() = runBlocking {
        val hereMain = here("here_umweg_154611.json").first
        val plainBody = File(dir, "umweg_osrm_1.json").readText()
        var calls = 0
        val (osm, plain) = repo.matchHereToOsm(hereMain) { calls++; plainBody }  // OSRM ignoriert Vias
        assertNull(osm)
        assertEquals(OsmRouteMatcher.MAX_REQUESTS, calls)
        assertEquals(plainBody, plain)
        // Fallback-Anreicherung kann die Antwort ohne neue Anfrage nutzen
        assertTrue(repo.parseOsrmLaneData(plain!!).first.isNotEmpty())
    }

    @Test
    fun alternativeHereGegenOsmHauptroute_abzweigGeometrischGefunden() = runBlocking {
        val hereMain = here("here_testfahrt_214226.json").first
        val osm = repo.matchHereToOsm(hereMain, fixtureFetch("testfahrt", mutableListOf())).first!!
        val alt = here("here_a8_a99.json").first

        val result = repo.processAlternatives(osm, listOf(alt))
        assertEquals(1, result.size)
        val a = result.single()
        assertEquals(alt.duration - osm.duration, a.durationDifference, 0.01)
        assertEquals(osm.geometry[a.divergePointIndex], a.divergePoint)
        // Abzweig auf der Hauptroute liegt dort, wo die Alternative die OSM-Linie verlässt
        assertTrue(OsmRouteMatcher.deviations(listOf(a.divergePoint), alt.geometry)[0] < 60)
        assertTrue(a.divergingGeometry.size >= 10)
    }

    @Test
    fun osrmManoeverNormalisiert_kreisverkehrMitAusfahrt() {
        val route = repo.parseOsrmRoute(File(dir, "osrm_holzkirchen_miesbach.json").readText())!!
        val types = route.steps.map { it.maneuver.type }.toSet()
        assertTrue("end of road/roundabout turn → turn: $types", "end of road" !in types && "roundabout turn" !in types)
        assertTrue(route.steps.none { it.maneuver.type == "continue" && it.maneuver.modifier in setOf("left", "right") })
        assertTrue(route.steps.filter { it.maneuver.type == "fork" }.all { it.maneuver.modifier in setOf("left", "right") })
        val roundabouts = route.steps.filter { it.maneuver.type == "roundabout" || it.maneuver.type == "rotary" }
        assertTrue(roundabouts.isNotEmpty())
        assertTrue(roundabouts.all { (it.maneuver.exit ?: 0) > 0 })
    }
}
