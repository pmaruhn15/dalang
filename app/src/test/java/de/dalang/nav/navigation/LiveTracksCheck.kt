package de.dalang.nav.navigation

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Opt-in-Live-Check gegen router.project-osrm.org mit echter HERE-Geometrie aus der App
 * (track_*.route.json: {"distance","duration","geometry":[[lat,lng],...]}). Läuft nur mit
 * TRACKS_DIR=<Ordner> ./gradlew testDebugUnitTest --tests '*LiveTracksCheck*'  (optional TRACK=<Namensteil>),
 * Ergebnis im system-out des Test-Reports.
 */
class LiveTracksCheck {
    @Test
    fun live() = runBlocking {
        val dir = System.getenv("TRACKS_DIR")?.let(::File) ?: return@runBlocking
        val only = System.getenv("TRACK")
        val repo = RouteRepository()
        val files = dir.listFiles()!!.filter { it.name.endsWith(".route.json") && (only == null || it.name.contains(only)) }
        for (f in files.sortedBy { it.name }) {
            val o = JSONObject(f.readText())
            val g = o.getJSONArray("geometry")
            val geom = (0 until g.length()).map { LatLng(g.getJSONArray(it).getDouble(0), g.getJSONArray(it).getDouble(1)) }
            if (geom.size < 2) continue
            val here = Route(o.getDouble("distance"), o.getDouble("duration"), geom, emptyList())
            var requests = 0
            val t0 = System.currentTimeMillis()
            val (osm, _) = repo.matchHereToOsm(here) { url ->
                requests++
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "DaLang test").build())
                    .execute().use { it.body?.string() }
            }
            println("${f.name} HERE ${here.distance.toInt()} m -> " +
                (osm?.let { "OK OSM ${it.distance.toInt()} m, ${it.steps.size} Steps, ${it.steps.count { s -> s.laneInfo != null }} mit Spuren" } ?: "FALLBACK") +
                ", $requests Anfrage(n), ${System.currentTimeMillis() - t0} ms")
            if (only != null) osm?.steps?.forEach { s ->
                println("  ${s.maneuver.type} ${s.maneuver.modifier} '${s.instruction}' ${s.distance.toInt()} m @${s.maneuver.location.lat},${s.maneuver.location.lng} " +
                    "${s.laneInfo?.lanes?.joinToString("|") { it.directions.joinToString("+") + if (it.isRecommended) "*" else "" } ?: ""}")
            }
            Thread.sleep(1100)  // Demo-Server: max. 1 Anfrage/s
        }
    }
}
