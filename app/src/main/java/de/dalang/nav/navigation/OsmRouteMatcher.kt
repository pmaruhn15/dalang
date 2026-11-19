package de.dalang.nav.navigation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Legt eine HERE-Strecke auf das OSM-Netz: HERE entscheidet die Strecke (Verkehr), OSRM liefert
 * Geometrie, Manöver, Spuren — passend zu den OSM-Kacheln der Karte.
 *
 * Verfahren (OSRM /route, NICHT /match): Iteration 0 = normale OSRM-Route Start→Ziel. Wo die
 * HERE-Strecke > [DEVIATION_M] neben der OSM-Linie liegt, kommen stille Via-Punkte
 * (`waypoints=0;n` → keine arrive/depart-Steps an Vias) mit Fahrtrichtung (`bearings`) auf die
 * HERE-Strecke, dann neue Anfrage. Gründe: /match ist auf router.project-osrm.org auf 10 Punkte
 * begrenzt (TooBig ab 11, gemessen 2026-11-19); fest gesampelte Vias rasten an Tunneln/Parallel-
 * fahrbahnen falsch ein (Richard-Strauss-Tunnel: +2 km Umweg) — Vias nur wo nötig vermeidet das.
 */
internal object OsmRouteMatcher {

    const val DEVIATION_M = 30.0          // HERE-Punkt weiter weg von OSM-Linie = andere Strecke
    const val MAX_OSM_DEVIATION_M = 75.0  // OSM-Punkt weiter weg von HERE = Schleife/Umweg
    const val MAX_VIAS = 100              // URL bleibt ~3 KB; Demo-Server: 414 ab ~8 KB (~400 Koord.)
    const val MAX_REQUESTS = 3
    private const val VIA_SPACING_M = 2000.0
    private const val VIA_RADIUS_M = 30
    private const val BEARING_RANGE = 45
    private const val ACTION_CLEARANCE_M = 30.0  // Vias nicht direkt auf Kreuzungen setzen

    data class Via(val point: LatLng, val bearing: Int, val along: Double)

    fun osrmUrl(baseUrl: String, here: List<LatLng>, vias: List<Via>): String {
        val start = here.first()
        val end = here.last()
        val coords = (listOf(start) + vias.map { it.point } + listOf(end))
            .joinToString(";") { "%.6f,%.6f".format(java.util.Locale.US, it.lng, it.lat) }
        // Start mit HERE-Fahrtrichtung, damit OSRM auf derselben Fahrbahn losfährt
        val startBearing = here.firstOrNull { it.distanceTo(start) > 20.0 }
            ?.let { "${bearing(start, it)},60" } ?: ""
        val bearings = (listOf(startBearing) + vias.map { "${it.bearing},$BEARING_RANGE" } + listOf("")).joinToString(";")
        var url = "$baseUrl/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true" +
                "&continue_straight=true&bearings=$bearings"
        if (vias.isNotEmpty()) {
            url += "&radiuses=" + (listOf("") + vias.map { "$VIA_RADIUS_M" } + listOf("")).joinToString(";") +
                    "&waypoints=0;${vias.size + 1}"
        }
        return url
    }

    /**
     * Neue Vias für alle Abschnitte, in denen [dev] (Abstand HERE-Punkt → OSM-Linie) über
     * [DEVIATION_M] liegt: einer pro angefangene 2 km, mindestens einer in der Mitte.
     */
    fun viasForDeviations(here: List<LatLng>, dev: DoubleArray, actionsAlong: List<Double>): List<Via> {
        val cum = cumulative(here)
        val result = mutableListOf<Via>()
        var i = 0
        while (i < dev.size) {
            if (dev[i] <= DEVIATION_M) { i++; continue }
            var j = i
            while (j + 1 < dev.size && dev[j + 1] > DEVIATION_M) j++
            val s = cum[max(0, i - 1)]
            val e = cum[min(here.size - 1, j + 1)]
            val n = max(1, floor((e - s) / VIA_SPACING_M).toInt())
            for (k in 1..n) {
                var d = s + (e - s) * k / (n + 1)
                if (actionsAlong.any { abs(it - d) < ACTION_CLEARANCE_M }) d = min(e, d + 2 * ACTION_CLEARANCE_M)
                result += pointAt(here, cum, d)
            }
            i = j + 1
        }
        return result
    }

    /** Grund, warum die OSM-Route nicht zur HERE-Strecke passt — null = passt. */
    fun rejectReason(here: Route, osm: Route): String? {
        val osmDev = deviations(osm.geometry, here.geometry).maxOrNull() ?: 0.0
        if (osmDev > MAX_OSM_DEVIATION_M) return "OSM-Linie bis ${osmDev.toInt()} m neben HERE"
        // Hin-und-zurück auf derselben Straße (falsch eingerastetes Via) sieht man nur an der Länge.
        // Gemessen bei passenden echten HERE-Routen: Abweichung <= 0,5 %. Wendemanöver selbst
        // sind kein Kriterium: HERE wendet z. B. ab Sackgassen-Start genauso.
        val diff = abs(osm.distance - here.distance)
        if (diff > max(300.0, 0.015 * here.distance)) return "Länge OSM ${osm.distance.toInt()} m vs HERE ${here.distance.toInt()} m"
        return null
    }

    /**
     * OSM-Route mit HERE-Verkehrsdaten: Dauer/typicalDuration von HERE, Step-Dauern auf HERE-Dauer
     * skaliert, Verkehrssegmente auf OSM-Geometrie-Indizes umgerechnet.
     */
    fun merge(here: Route, osm: Route): Route {
        val factor = if (osm.duration > 0) here.duration / osm.duration else 1.0
        return osm.copy(
            duration = here.duration,
            typicalDuration = here.typicalDuration,
            hasTrafficData = here.hasTrafficData,
            trafficSegments = remapTraffic(here.trafficSegments, here.geometry, osm.geometry),
            steps = osm.steps.map { it.copy(duration = it.duration * factor) }
        )
    }

    fun remapTraffic(segments: List<TrafficSegment>, src: List<LatLng>, dst: List<LatLng>): List<TrafficSegment> {
        if (segments.isEmpty() || src.isEmpty() || dst.isEmpty()) return emptyList()
        val bounds = segments.flatMap { listOf(it.startIndex, it.endIndex) }.distinct().sorted()
        val mapped = bounds.zip(mapIndices(src, dst, bounds)).toMap()
        return segments.map { TrafficSegment(mapped.getValue(it.startIndex), mapped.getValue(it.endIndex), it.level) }
            .filter { it.endIndex > it.startIndex }
    }

    /**
     * Für aufsteigende Indizes in [src] den nächstgelegenen Index in [dst], monoton fortschreitend
     * (Suchfenster nach Strecke, damit Hin-/Rückweg derselben Straße nicht verwechselt werden).
     * Index >= src.size (exklusives Ende) → dst.size.
     */
    fun mapIndices(src: List<LatLng>, dst: List<LatLng>, srcIdx: List<Int>): List<Int> {
        val srcCum = cumulative(src)
        val dstCum = cumulative(dst)
        var j = 0
        var prevAlong = 0.0
        return srcIdx.map { si ->
            if (si >= src.size) return@map dst.size
            val idx = si.coerceAtLeast(0)
            val limit = dstCum[j] + (srcCum[idx] - prevAlong) * 1.5 + 300.0
            var best = j
            var bestD = Double.MAX_VALUE
            var k = j
            while (k < dst.size && dstCum[k] <= limit) {
                val d = src[idx].distanceTo(dst[k])
                if (d < bestD) { bestD = d; best = k }
                k++
            }
            j = best
            prevAlong = srcCum[idx]
            best
        }
    }

    /**
     * Abstand (m) jedes Punkts zur Polylinie [line], lokal planar genähert.
     * ponytail: brute force O(n·m) — bei 800 × 5000 Punkten wenige 10 ms; bei sehr langen Routen
     * (> 500 km) Gitter-Index oder monotones Suchfenster nachrüsten.
     */
    fun deviations(points: List<LatLng>, line: List<LatLng>): DoubleArray {
        if (points.isEmpty() || line.isEmpty()) return DoubleArray(points.size) { Double.MAX_VALUE }
        val lat0 = points[0].lat
        val kx = M_PER_DEG * cos(Math.toRadians(lat0))
        val lx = DoubleArray(line.size) { line[it].lng * kx }
        val ly = DoubleArray(line.size) { line[it].lat * M_PER_DEG }
        return DoubleArray(points.size) { pi ->
            val px = points[pi].lng * kx
            val py = points[pi].lat * M_PER_DEG
            var best = if (line.size == 1) Math.hypot(px - lx[0], py - ly[0]) else Double.MAX_VALUE
            for (i in 0 until line.size - 1) {
                val dx = lx[i + 1] - lx[i]
                val dy = ly[i + 1] - ly[i]
                val len = dx * dx + dy * dy
                val t = if (len < 1e-9) 0.0 else (((px - lx[i]) * dx + (py - ly[i]) * dy) / len).coerceIn(0.0, 1.0)
                val ex = lx[i] + t * dx - px
                val ey = ly[i] + t * dy - py
                val d = ex * ex + ey * ey
                if (d < best) best = d
            }
            if (line.size == 1) best else sqrt(best)
        }
    }

    fun nearestIndex(line: List<LatLng>, p: LatLng): Int =
        line.indices.minByOrNull { line[it].distanceTo(p) } ?: 0

    fun cumulative(line: List<LatLng>): DoubleArray {
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + line[i - 1].distanceTo(line[i])
        return cum
    }

    private fun pointAt(line: List<LatLng>, cum: DoubleArray, along: Double): Via {
        // Richtung über ±20 m statt über das (evtl. winzige, verrauschte) Einzelsegment
        return Via(interpolate(line, cum, along), bearing(interpolate(line, cum, along - 20), interpolate(line, cum, along + 20)), along)
    }

    private fun interpolate(line: List<LatLng>, cum: DoubleArray, along: Double): LatLng {
        var i = 0
        while (i < line.size - 2 && cum[i + 1] < along) i++
        val a = line[i]
        val b = line[min(i + 1, line.size - 1)]
        val segLen = cum[min(i + 1, line.size - 1)] - cum[i]
        val t = if (segLen < 1e-6) 0.0 else ((along - cum[i]) / segLen).coerceIn(0.0, 1.0)
        return LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
    }

    private fun bearing(a: LatLng, b: LatLng): Int {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lng - a.lng)
        val y = Math.sin(dl) * cos(la2)
        val x = cos(la1) * Math.sin(la2) - Math.sin(la1) * cos(la2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toInt()
    }

    private const val M_PER_DEG = 111_320.0
}
