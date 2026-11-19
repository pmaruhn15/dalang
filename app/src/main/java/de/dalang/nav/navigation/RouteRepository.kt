package de.dalang.nav.navigation

import de.dalang.nav.config.HereConfig
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LaneInfo(
    val lanes: List<Lane>,
    val recommendedLaneIndex: Int  // -1 wenn keine Empfehlung
)

data class Lane(
    val directions: List<String>,  // Mehrere Richtungen möglich: ["straight", "right"]
    val isRecommended: Boolean
)

data class RouteStep(
    val instruction: String,
    val distance: Double,
    val duration: Double,
    val maneuver: Maneuver,
    val geometry: List<LatLng>,
    val laneInfo: LaneInfo? = null,
    val isMotorway: Boolean? = null,  // aus OSM via OSRM-"classes"; null = unbekannt
    val freeFlowDuration: Double? = null  // unskalierte OSRM-Dauer (Straßentyp-Einstufung)
)

data class Maneuver(
    val type: String,
    val modifier: String?,
    val location: LatLng,
    val exit: Int? = null  // Kreisverkehr-Ausfahrt (1, 2, 3, etc.)
)

data class LatLng(
    val lat: Double,
    val lng: Double
) {
    fun distanceTo(other: LatLng): Double {
        val r = 6371000.0 // Erdradius in Metern
        val lat1 = Math.toRadians(lat)
        val lat2 = Math.toRadians(other.lat)
        val deltaLat = Math.toRadians(other.lat - lat)
        val deltaLng = Math.toRadians(other.lng - lng)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return r * c
    }
}

/**
 * Traffic-Stufe basierend auf jamFactor (HERE) oder Geschwindigkeitsverhältnis
 * GREEN: Frei fließend (jamFactor 0-3)
 * YELLOW: Leicht verlangsamt (jamFactor 3-6)
 * ORANGE: Zähfließend (jamFactor 6-8)
 * RED: Stau (jamFactor 8-10)
 */
enum class TrafficLevel {
    GREEN, YELLOW, ORANGE, RED
}

data class TrafficSegment(
    val startIndex: Int,  // Index in route geometry
    val endIndex: Int,
    val level: TrafficLevel
)

/**
 * Alternative Route an einer Abzweigung.
 * Zeigt den abweichenden Pfad und die Zeitdifferenz zur Hauptroute.
 */
data class AlternativeRoute(
    val divergePoint: LatLng,           // Punkt wo Alternative abzweigt
    val divergePointIndex: Int,         // Index in Hauptrouten-Geometrie
    val geometry: List<LatLng>,         // Komplette alternative Geometrie
    val divergingGeometry: List<LatLng>, // Nur der abweichende Teil (für Anzeige)
    val duration: Double,               // Gesamtdauer der Alternative
    val durationDifference: Double,     // Differenz zur Hauptroute (+ = länger)
    val distance: Double,               // Gesamtdistanz der Alternative
    // Vollständige Routen-Daten — werden benötigt um eine Alternative ohne
    // erneuten API-Call zur Hauptroute zu promoten (Click-to-Select).
    val steps: List<RouteStep> = emptyList(),
    val hasTrafficData: Boolean = false,
    val typicalDuration: Double? = null,
    val trafficSegments: List<TrafficSegment> = emptyList()
)

data class Route(
    val distance: Double,
    val duration: Double,
    val geometry: List<LatLng>,
    val steps: List<RouteStep>,
    val hasTrafficData: Boolean = false,
    val typicalDuration: Double? = null,  // Typische Dauer ohne Verkehr
    val trafficSegments: List<TrafficSegment> = emptyList(),  // Verkehrs-Segmente für Farbcodierung
    val alternatives: List<AlternativeRoute> = emptyList()    // Alternative Routen an Abzweigungen
)

class RouteRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getRoute(from: LatLng, to: LatLng): Route? = withContext(Dispatchers.IO) {
        // HERE API nutzen wenn konfiguriert UND Limit nicht erreicht
        val isConfigured = HereConfig.isConfigured()
        val canMakeRequest = HereConfig.canMakeRequest()

        if (isConfigured && canMakeRequest) {
            val hereResult = getRouteFromHereWithAlternatives(from, to)
            if (hereResult != null && hereResult.first.geometry.isNotEmpty()) {
                val hereMain = hereResult.first

                // HERE wählt die Strecke (Verkehr), OSM liefert Linie/Manöver/Spuren passend zur Karte.
                // Scheitert das, wie bisher: HERE-Route + OSRM-Spuren per Nähe.
                val (osmMain, plainOsrmBody) = matchHereToOsm(hereMain)
                val mainRoute = osmMain ?: enrichRouteWithOsrmLanes(hereMain, from, to, plainOsrmBody)

                // Alternativen behalten HERE-Geometrie (nur graue Anzeige; OSM-Anpassung kostete je
                // 1-3 weitere OSRM-Anfragen à >= 1 s wegen Rate-Limit)
                mainRoute.copy(alternatives = processAlternatives(mainRoute, hereResult.second))
            } else {
                CrashLogger.log("RouteRepository: HERE failed, fallback to OSRM")
                getRouteFromOsrm(from, to)
            }
        } else {
            // Debug: Warum wird HERE nicht verwendet?
            val hasKey = HereConfig.getApiKey().isNotBlank()
            val isEnabled = HereConfig.isEnabled()
            CrashLogger.log("RouteRepository: Using OSRM (HERE: key=$hasKey, enabled=$isEnabled, canRequest=$canMakeRequest)")
            getRouteFromOsrm(from, to)
        }
    }

    /**
     * Legt die HERE-Strecke aufs OSM-Netz (Verfahren: [OsmRouteMatcher]). Liefert die OSM-Route mit
     * HERE-Dauer/Verkehr oder null (zu große Abweichung, OSRM-Fehler) — plus die Antwort der ersten,
     * via-freien OSRM-Anfrage, die der Fallback für die Spur-Anreicherung wiederverwendet (keine
     * Zusatzanfrage gegenüber früher).
     */
    internal suspend fun matchHereToOsm(here: Route, fetch: (String) -> String? = ::httpGet): Pair<Route?, String?> {
        if (here.geometry.size < 2) return null to null
        val hereCum = OsmRouteMatcher.cumulative(here.geometry)
        val actionsAlong = here.steps.map { hereCum[OsmRouteMatcher.nearestIndex(here.geometry, it.maneuver.location)] }
        var vias = emptyList<OsmRouteMatcher.Via>()
        var plainBody: String? = null
        for (attempt in 0 until OsmRouteMatcher.MAX_REQUESTS) {
            if (attempt > 0) delay(1100)  // Demo-Server-Policy: max. 1 Anfrage/s
            val body = fetch(OsmRouteMatcher.osrmUrl(OSRM_BASE_URL, here.geometry, vias))
            if (attempt == 0) plainBody = body
            val osm = body?.let { parseOsrmRoute(it) }
            if (osm == null || osm.geometry.size < 2) {
                CrashLogger.log("RouteRepository: OSM-Anpassung gescheitert (Anfrage ${attempt + 1}, ${vias.size} Vias): ${body?.take(150)} → HERE-Geometrie")
                return null to plainBody
            }
            val dev = OsmRouteMatcher.deviations(here.geometry, osm.geometry)
            val newVias = OsmRouteMatcher.viasForDeviations(here.geometry, dev, actionsAlong)
            if (newVias.isEmpty()) {
                val reason = OsmRouteMatcher.rejectReason(here, osm)
                if (reason != null) {
                    CrashLogger.log("RouteRepository: OSM-Anpassung verworfen ($reason, ${vias.size} Vias) → HERE-Geometrie")
                    return null to plainBody
                }
                CrashLogger.log("RouteRepository: OSM-Anpassung ok nach ${attempt + 1} Anfrage(n), ${vias.size} Vias: " +
                    "${osm.geometry.size} Punkte, ${osm.steps.size} Steps, ${osm.distance.toInt()} m (HERE ${here.distance.toInt()} m)")
                return OsmRouteMatcher.merge(here, osm) to plainBody
            }
            vias = (vias + newVias).sortedBy { it.along }
            CrashLogger.log("RouteRepository: OSM weicht bis ${dev.max().toInt()} m von HERE ab → ${vias.size} Vias")
            if (vias.size > OsmRouteMatcher.MAX_VIAS) break
        }
        CrashLogger.log("RouteRepository: OSM-Anpassung nicht konvergiert (${vias.size} Vias) → HERE-Geometrie")
        return null to plainBody
    }

    /** GET, Body auch bei HTTP-Fehler (OSRM schickt dann JSON mit code/message). */
    private fun httpGet(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", "DaLang Navigation App").build()
        client.newCall(request).execute().use { it.body?.string() }
    } catch (e: Exception) {
        CrashLogger.logError("RouteRepository", "OSRM request failed", e)
        null
    }

    /**
     * Reichert eine HERE-Route mit Lane-Daten von OSRM an.
     * HERE REST API liefert keine Lane-Daten, aber OSRM hat sie aus OSM turn:lanes Tags.
     */
    private suspend fun enrichRouteWithOsrmLanes(hereRoute: Route, from: LatLng, to: LatLng, osrmBody: String? = null): Route {
        try {
            // OSRM nur für Lane-Daten (Antwort aus matchHereToOsm wiederverwenden, sonst neu holen)
            val (osrmLanes, motorwaySegments) = osrmBody?.let { parseOsrmLaneData(it) } ?: getOsrmLaneData(from, to)
            if (osrmLanes.isEmpty() && motorwaySegments.isEmpty()) {
                CrashLogger.log("RouteRepository: No OSRM lane/road-class data available")
                return hereRoute
            }

            CrashLogger.log("RouteRepository: Enriching HERE route with ${osrmLanes.size} OSRM lane infos, ${motorwaySegments.size} motorway steps")

            // Lane-Daten per Manöver-Nähe, Straßentyp per Geometrie-Nähe (Mitte des HERE-Steps
            // liegt auf einem OSRM-Motorway-Step) zuordnen.
            val enrichedSteps = hereRoute.steps.map { step ->
                val nearestLaneInfo = findNearestLaneInfo(step.maneuver.location, osrmLanes)
                val mid = step.geometry.getOrNull(step.geometry.size / 2)
                val onMotorway = mid?.let { m -> motorwaySegments.any { seg -> distanceToPolyline(m, seg) < 40.0 } }
                step.copy(laneInfo = nearestLaneInfo ?: step.laneInfo, isMotorway = onMotorway)
            }

            return hereRoute.copy(steps = enrichedSteps)
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "Failed to enrich with OSRM lanes", e)
            return hereRoute
        }
    }

    /**
     * Holt nur Lane-Daten von OSRM (ohne die Route selbst zu verwenden)
     */
    private fun getOsrmLaneData(from: LatLng, to: LatLng): Pair<List<Pair<LatLng, LaneInfo>>, List<List<LatLng>>> {
        try {
            val url = "$OSRM_BASE_URL/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}" +
                    "?overview=false&geometries=geojson&steps=true"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList<Pair<LatLng, LaneInfo>>() to emptyList()

            return parseOsrmLaneData(body)
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "OSRM lane fetch failed", e)
            return emptyList<Pair<LatLng, LaneInfo>>() to emptyList()
        }
    }

    /**
     * Parst nur die Lane-Daten aus OSRM Response (Location + LaneInfo Paare)
     */
    internal fun parseOsrmLaneData(json: String): Pair<List<Pair<LatLng, LaneInfo>>, List<List<LatLng>>> {
        val result = mutableListOf<Pair<LatLng, LaneInfo>>()
        val motorway = mutableListOf<List<LatLng>>()
        try {
            val obj = JSONObject(json)
            if (obj.getString("code") != "Ok") return result to motorway

            val routes = obj.getJSONArray("routes")
            if (routes.length() == 0) return result to motorway

            val route = routes.getJSONObject(0)
            val legs = route.getJSONArray("legs")

            for (legIdx in 0 until legs.length()) {
                val leg = legs.getJSONObject(legIdx)
                val steps = leg.getJSONArray("steps")

                for (stepIdx in 0 until steps.length()) {
                    val step = steps.getJSONObject(stepIdx)
                    if (isOsrmMotorwayStep(step)) {
                        step.optJSONObject("geometry")?.optJSONArray("coordinates")?.let { c ->
                            motorway.add((0 until c.length()).map { i ->
                                c.getJSONArray(i).let { LatLng(it.getDouble(1), it.getDouble(0)) }
                            })
                        }
                    }
                    // Spuren ALLER Intersections mit ihrem Ort sammeln (OSRM: lanes gelten für genau
                    // diese Kreuzung). Vorher: nur letzte Intersection, abgelegt unter dem Step-START
                    // -> Spuren landeten beim falschen Manöver (Fahrer-Report Georg-Brauchle-Ring:
                    // Gabelung zeigte 4x geradeaus statt 2 linke markiert).
                    val ints = step.optJSONArray("intersections") ?: continue
                    for (k in 0 until ints.length()) {
                        val inter = ints.getJSONObject(k)
                        val info = parseIntersectionLanes(inter) ?: continue
                        val loc = inter.getJSONArray("location").let { LatLng(it.getDouble(1), it.getDouble(0)) }
                        result.add(loc to info)
                    }
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "Parse OSRM lane data failed", e)
        }
        return result to motorway
    }

    /** OSRM-Car-Profil markiert motorway/motorway_link (OSM highway=*) in intersections[0].classes. */
    private fun isOsrmMotorwayStep(step: JSONObject): Boolean =
        step.optJSONArray("intersections")?.optJSONObject(0)?.let { isOsrmMotorwayIntersection(it) } ?: false

    /** classes = Klassen der Straße, die die Kreuzung verlässt (OSRM-Doku Intersection). */
    private fun isOsrmMotorwayIntersection(intersection: JSONObject): Boolean {
        val classes = intersection.optJSONArray("classes") ?: return false
        return (0 until classes.length()).any { classes.optString(it) == "motorway" }
    }

    private fun distanceToPolyline(p: LatLng, line: List<LatLng>): Double {
        if (line.size == 1) return p.distanceTo(line[0])
        return line.zipWithNext().minOfOrNull { (a, b) ->
            val dx = b.lng - a.lng; val dy = b.lat - a.lat
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq < 1e-12) 0.0 else (((p.lng - a.lng) * dx + (p.lat - a.lat) * dy) / lenSq).coerceIn(0.0, 1.0)
            p.distanceTo(LatLng(a.lat + t * dy, a.lng + t * dx))
        } ?: Double.MAX_VALUE
    }

    /**
     * Findet die nächste Lane-Info für eine gegebene Location (max 100m Abstand)
     */
    private fun findNearestLaneInfo(location: LatLng, laneData: List<Pair<LatLng, LaneInfo>>): LaneInfo? {
        val maxDistance = 100.0 // Meter
        return laneData
            .map { (loc, info) -> loc.distanceTo(location) to info }
            .filter { it.first < maxDistance }
            .minByOrNull { it.first }
            ?.second
    }

    /**
     * Holt Route mit Alternativen von HERE.
     * Gibt Pair zurück: (Hauptroute, Liste von Alternativ-Routen)
     */
    private suspend fun getRouteFromHereWithAlternatives(from: LatLng, to: LatLng): Pair<Route, List<Route>>? {
        try {
            val apiKey = HereConfig.getApiKey()

            // HERE Routing v8 API mit alternatives=3
            val url = "${HereConfig.ROUTING_BASE_URL}/routes" +
                    "?origin=${from.lat},${from.lng}" +
                    "&destination=${to.lat},${to.lng}" +
                    "&transportMode=car" +
                    "&alternatives=3" +
                    "&return=polyline,actions,instructions,summary,typicalDuration,turnByTurnActions" +
                    "&spans=names,length,duration,speedLimit,maxSpeed,dynamicSpeedInfo" +
                    "&apiKey=$apiKey"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                CrashLogger.logError("RouteRepository", "HERE API error: ${response.code} - ${response.message}")
                return null
            }

            HereConfig.incrementUsage()

            return parseHereRoutesWithAlternatives(body, from)
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "HERE routing with alternatives failed", e)
            return null
        }
    }

    /**
     * Parst HERE Response mit mehreren Routen.
     */
    internal fun parseHereRoutesWithAlternatives(json: String, origin: LatLng): Pair<Route, List<Route>>? {
        try {
            val obj = JSONObject(json)
            if (!obj.has("routes")) return null

            val routes = obj.getJSONArray("routes")
            if (routes.length() == 0) return null

            // Erste Route ist die Hauptroute
            val mainRoute = parseHereRouteFromJson(routes.getJSONObject(0), origin) ?: return null

            // Restliche Routen sind Alternativen
            val alternatives = mutableListOf<Route>()
            for (i in 1 until routes.length()) {
                val altRoute = parseHereRouteFromJson(routes.getJSONObject(i), origin)
                if (altRoute != null) {
                    alternatives.add(altRoute)
                }
            }

            CrashLogger.log("RouteRepository: Parsed ${alternatives.size} alternative routes")
            return mainRoute to alternatives
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "parseHereRoutesWithAlternatives failed", e)
            return null
        }
    }

    /**
     * Analysiert Alternativen und findet Abzweigpunkte.
     * Filtert sinnlose Alternativen (z.B. Ausfahrt und wieder Auffahrt auf gleiche Autobahn).
     */
    internal fun processAlternatives(mainRoute: Route, alternatives: List<Route>): List<AlternativeRoute> {
        val result = mutableListOf<AlternativeRoute>()

        for (alt in alternatives) {
            // Differenz zur Hauptroute berechnen
            val durationDiff = alt.duration - mainRoute.duration

            // Filter: nicht doppelt so lang wie Hauptroute UND mindestens 90s anders.
            // Why: <90s ist im echten Verkehr Rauschen — der User soll nicht zwischen
            // zwei "1-min-Alternativen" wählen müssen.
            val maxDurationDiff = mainRoute.duration  // Max 100% länger
            if (durationDiff > maxDurationDiff || kotlin.math.abs(durationDiff) < 90) {
                continue
            }

            // Abzweigpunkt finden: Wo divergiert die Alternative von der Hauptroute?
            val divergeResult = findDivergencePoint(mainRoute.geometry, alt.geometry)
            if (divergeResult == null) {
                continue
            }

            val (divergeIndex, convergIndex, altDivergeIndex, altConvergeIndex) = divergeResult

            // Nur anzeigen wenn Abzweigung sinnvoll ist (nicht zu früh, nicht zu spät)
            if (divergeIndex < 5 || divergeIndex > mainRoute.geometry.size - 20) {
                continue
            }

            // Abweichenden Teil extrahieren
            val divergingGeometry = if (altDivergeIndex < altConvergeIndex && altConvergeIndex <= alt.geometry.size) {
                alt.geometry.subList(altDivergeIndex, altConvergeIndex.coerceAtMost(alt.geometry.size))
            } else {
                continue
            }

            // Filter: Abweichender Teil muss substanziell sein
            if (divergingGeometry.size < 10) {
                continue
            }

            result.add(AlternativeRoute(
                divergePoint = mainRoute.geometry[divergeIndex],
                divergePointIndex = divergeIndex,
                geometry = alt.geometry,
                divergingGeometry = divergingGeometry,
                duration = alt.duration,
                durationDifference = durationDiff,
                distance = alt.distance,
                steps = alt.steps,
                hasTrafficData = alt.hasTrafficData,
                typicalDuration = alt.typicalDuration,
                trafficSegments = alt.trafficSegments
            ))
        }

        // Cap auf 2 Alternativen — mehr wird in der UI redundant.
        // Bevorzuge Alternativen mit kleiner |Differenz| (= ähnlich schnell, aber distinct genug).
        val capped = result
            .sortedBy { kotlin.math.abs(it.durationDifference) }
            .take(2)

        CrashLogger.log("RouteRepository: ${capped.size} alternatives after filter+cap (raw=${result.size})")
        return capped
    }

    /**
     * Promotet eine Alternative zur neuen Hauptroute, ohne neuen API-Call.
     * Die alte Hauptroute und die übrigen Alternativen werden relativ zur
     * neuen Hauptroute neu als Alternativen klassifiziert.
     */
    fun promoteAlternativeToMain(currentRoute: Route, alternativeIndex: Int): Route? {
        val alternatives = currentRoute.alternatives
        if (alternativeIndex !in alternatives.indices) {
            CrashLogger.log("RouteRepository: promote — invalid index $alternativeIndex")
            return null
        }

        val selected = alternatives[alternativeIndex]
        val newMain = Route(
            distance = selected.distance,
            duration = selected.duration,
            geometry = selected.geometry,
            steps = selected.steps,
            hasTrafficData = selected.hasTrafficData,
            typicalDuration = selected.typicalDuration,
            trafficSegments = selected.trafficSegments,
            alternatives = emptyList()
        )

        val candidates = mutableListOf<Route>()
        candidates += currentRoute.copy(alternatives = emptyList())
        alternatives.forEachIndexed { i, alt ->
            if (i != alternativeIndex) {
                candidates += Route(
                    distance = alt.distance,
                    duration = alt.duration,
                    geometry = alt.geometry,
                    steps = alt.steps,
                    hasTrafficData = alt.hasTrafficData,
                    typicalDuration = alt.typicalDuration,
                    trafficSegments = alt.trafficSegments
                )
            }
        }

        val newAlternatives = processAlternatives(newMain, candidates)
        CrashLogger.log("RouteRepository: promoted alt $alternativeIndex; ${newAlternatives.size} alternatives remain")
        return newMain.copy(alternatives = newAlternatives)
    }

    /**
     * Findet den Punkt wo zwei Routen divergieren und wieder zusammenkommen.
     * Gibt (mainDivergeIdx, mainConvergeIdx, altDivergeIdx, altConvergeIdx) zurück.
     */
    private fun findDivergencePoint(mainGeom: List<LatLng>, altGeom: List<LatLng>): IntArray? {
        val threshold = 50.0 // Meter - Punkte näher als das gelten als "gleich"

        // Geometrisch statt Index-für-Index: Hauptroute (OSM, dicht) und Alternative (HERE) haben
        // verschiedene Stützpunkte, Index i der einen entspricht nicht Index i der anderen.
        val dev = OsmRouteMatcher.deviations(altGeom, mainGeom)
        val divergeAltIdx = dev.indexOfFirst { it > threshold }
        if (divergeAltIdx < 0) return null
        val convergeAltIdx = dev.indexOfLast { it > threshold } + 1
        val divergeMainIdx = OsmRouteMatcher.nearestIndex(mainGeom, altGeom[maxOf(0, divergeAltIdx - 1)])
        val convergeMainIdx = OsmRouteMatcher.nearestIndex(mainGeom, altGeom[minOf(convergeAltIdx, altGeom.size - 1)])

        // Sanity check
        if (divergeMainIdx >= convergeMainIdx || divergeAltIdx >= convergeAltIdx) {
            return null
        }

        return intArrayOf(divergeMainIdx, convergeMainIdx, divergeAltIdx, convergeAltIdx)
    }

    private suspend fun getRouteFromHere(from: LatLng, to: LatLng): Route? {
        val result = getRouteFromHereWithAlternatives(from, to)
        return result?.first
    }

    private fun parseHereRoute(json: String, origin: LatLng): Route? {
        try {
            val obj = JSONObject(json)

            // Check for error response
            if (obj.has("error")) {
                val error = obj.optString("error", "unknown")
                val errorDesc = obj.optString("error_description", "no description")
                CrashLogger.logError("RouteRepository", "HERE API error: $error - $errorDesc")
                return null
            }

            if (!obj.has("routes")) {
                CrashLogger.logError("RouteRepository", "HERE response has no 'routes' field")
                return null
            }

            val routes = obj.getJSONArray("routes")
            if (routes.length() == 0) {
                CrashLogger.logError("RouteRepository", "HERE returned 0 routes")
                return null
            }

            return parseHereRouteFromJson(routes.getJSONObject(0), origin)
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "Parse HERE route failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }

    /**
     * Parst ein einzelnes Route-Objekt aus HERE JSON.
     */
    private fun parseHereRouteFromJson(route: JSONObject, origin: LatLng): Route? {
        try {
            val sections = route.getJSONArray("sections")
            if (sections.length() == 0) {
                CrashLogger.logError("RouteRepository", "HERE route has 0 sections")
                return null
            }

            val section = sections.getJSONObject(0)
            val summary = section.getJSONObject("summary")

            val distance = summary.getDouble("length")
            val duration = summary.getDouble("duration").toDouble()
            val typicalDuration = summary.optDouble("typicalDuration", duration)

            // Geometrie dekodieren (HERE Flexible Polyline)
            val polyline = section.getString("polyline")
            val geometry = FlexiblePolyline.decode(polyline)

            CrashLogger.log("RouteRepository: HERE route with ${geometry.size} points")

            // Actions/Instructions parsen
            val steps = mutableListOf<RouteStep>()
            val actions = section.optJSONArray("actions")

            if (actions != null) {
                for (i in 0 until actions.length()) {
                    val action = actions.getJSONObject(i)
                    val actionType = action.getString("action")
                    val instruction = action.optString("instruction", "")
                    val actionDuration = action.optDouble("duration", 0.0)
                    val actionLength = action.optDouble("length", 0.0)

                    // Position aus offset ermitteln
                    val offset = action.optInt("offset", 0)
                    val location = if (offset < geometry.size) geometry[offset] else geometry.firstOrNull() ?: continue

                    // HERE action types zu OSRM-kompatiblen Typen mappen
                    val (maneuverType, modifier) = mapHereAction(actionType, action.optString("direction", ""))

                    // Kreisverkehr-Ausfahrt extrahieren (HERE liefert "roundaboutExitNumber")
                    val exitNumber = if (actionType in listOf("roundaboutEnter", "roundaboutExit")) {
                        action.optInt("roundaboutExitNumber", 0).takeIf { it > 0 }
                    } else null

                    // Geometrie-Segment fuer diesen Schritt
                    val nextOffset = if (i < actions.length() - 1) {
                        actions.getJSONObject(i + 1).optInt("offset", geometry.size)
                    } else {
                        geometry.size
                    }
                    val stepGeometry = geometry.subList(
                        offset.coerceIn(0, geometry.size),
                        nextOffset.coerceIn(0, geometry.size)
                    )

                    steps.add(
                        RouteStep(
                            instruction = instruction,
                            distance = actionLength,
                            duration = actionDuration,
                            maneuver = Maneuver(
                                type = maneuverType,
                                modifier = modifier,
                                location = location,
                                exit = exitNumber
                            ),
                            geometry = stepGeometry,
                            laneInfo = null  // Lane guidance nur im HERE SDK verfügbar, nicht REST API
                        )
                    )
                }
            }

            // Falls keine Actions, einen generischen Schritt erstellen
            if (steps.isEmpty() && geometry.isNotEmpty()) {
                steps.add(
                    RouteStep(
                        instruction = "Route folgen",
                        distance = distance,
                        duration = duration,
                        maneuver = Maneuver(
                            type = "depart",
                            modifier = null,
                            location = geometry.first()
                        ),
                        geometry = geometry
                    )
                )
            }

            // Traffic-Segmente aus spans parsen
            val trafficSegments = parseTrafficSpans(section)

            return Route(
                distance = distance,
                duration = duration,
                geometry = geometry,
                steps = steps,
                hasTrafficData = trafficSegments.isNotEmpty(),
                typicalDuration = typicalDuration,
                trafficSegments = trafficSegments
            )
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "Parse HERE route failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }

    private fun mapHereAction(action: String, direction: String): Pair<String, String?> {
        return when (action) {
            "depart" -> "depart" to null
            "arrive" -> "arrive" to null
            "turn" -> when (direction) {
                "left" -> "turn" to "left"
                "slightlyLeft" -> "turn" to "slight left"
                "sharpLeft" -> "turn" to "sharp left"
                "right" -> "turn" to "right"
                "slightlyRight" -> "turn" to "slight right"
                "sharpRight" -> "turn" to "sharp right"
                "uTurnLeft", "uTurnRight" -> "turn" to "uturn"
                else -> "turn" to direction
            }
            "continue" -> "continue" to null
            "roundaboutEnter" -> "roundabout" to null
            "roundaboutExit" -> "exit roundabout" to null
            "ramp" -> "on ramp" to direction
            // HERE "keep" = Spur halten an Kreuz/Gabelung, "exit" = Ausfahrt. Bisher unbekannt ->
            // modifier null -> als irrelevant übersprungen: keine Ansage an Autobahnkreuzen/Ausfahrten.
            "keep" -> "fork" to when (direction) { "left" -> "left"; "right" -> "right"; else -> null }
            "exit" -> "off ramp" to when (direction) { "left" -> "slight left"; else -> "slight right" }
            "merge" -> "merge" to direction
            "highway" -> "new name" to null
            else -> action to null
        }
    }

    /**
     * Parst Traffic-Segmente aus HERE spans.
     * HERE liefert dynamicSpeedInfo mit trafficSpeed, baseSpeed, jamFactor etc.
     */
    private fun parseTrafficSpans(section: JSONObject): List<TrafficSegment> {
        val segments = mutableListOf<TrafficSegment>()
        try {
            val spans = section.optJSONArray("spans") ?: return emptyList()

            for (i in 0 until spans.length()) {
                val span = spans.getJSONObject(i)
                val offset = span.optInt("offset", 0)

                // Nächsten Span-Offset ermitteln oder Ende der Geometrie
                val nextOffset = if (i < spans.length() - 1) {
                    spans.getJSONObject(i + 1).optInt("offset", offset + 1)
                } else {
                    offset + 100  // Approximation für letzten Span
                }

                // dynamicSpeedInfo enthält Traffic-Daten
                val dynamicSpeed = span.optJSONObject("dynamicSpeedInfo")
                if (dynamicSpeed != null) {
                    val trafficSpeed = dynamicSpeed.optDouble("trafficSpeed", -1.0)
                    val baseSpeed = dynamicSpeed.optDouble("baseSpeed", -1.0)
                    val jamFactor = dynamicSpeed.optDouble("jamFactor", -1.0)

                    val level = when {
                        jamFactor >= 0 -> jamFactorToLevel(jamFactor)
                        trafficSpeed > 0 && baseSpeed > 0 -> speedRatioToLevel(trafficSpeed / baseSpeed)
                        else -> TrafficLevel.GREEN  // Default: frei
                    }

                    segments.add(TrafficSegment(offset, nextOffset, level))
                }
            }

            // Benachbarte Segmente mit gleicher Farbe zusammenfassen
            return mergeTrafficSegments(segments)
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "parseTrafficSpans failed", e)
            return emptyList()
        }
    }

    private fun jamFactorToLevel(jamFactor: Double): TrafficLevel {
        return when {
            jamFactor <= 3.0 -> TrafficLevel.GREEN   // Frei fließend
            jamFactor <= 6.0 -> TrafficLevel.YELLOW  // Leicht verlangsamt
            jamFactor <= 8.0 -> TrafficLevel.ORANGE  // Zähfließend
            else -> TrafficLevel.RED                  // Stau
        }
    }

    private fun speedRatioToLevel(ratio: Double): TrafficLevel {
        return when {
            ratio >= 0.8 -> TrafficLevel.GREEN   // >= 80% der Normalgeschwindigkeit
            ratio >= 0.5 -> TrafficLevel.YELLOW  // 50-80%
            ratio >= 0.25 -> TrafficLevel.ORANGE // 25-50%
            else -> TrafficLevel.RED              // < 25%
        }
    }

    private fun mergeTrafficSegments(segments: List<TrafficSegment>): List<TrafficSegment> {
        if (segments.isEmpty()) return emptyList()

        val merged = mutableListOf<TrafficSegment>()
        var current = segments.first()

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.level == current.level && next.startIndex == current.endIndex) {
                // Zusammenfassen
                current = current.copy(endIndex = next.endIndex)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    private suspend fun getRouteFromOsrm(from: LatLng, to: LatLng): Route? {
        try {
            val url = "$OSRM_BASE_URL/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}" +
                    "?overview=full&geometries=geojson&steps=true&annotations=true"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null

            return parseOsrmRoute(body)
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "OSRM routing failed", e)
            return null
        }
    }

    internal fun parseOsrmRoute(json: String): Route? {
        try {
            val obj = JSONObject(json)
            if (obj.getString("code") != "Ok") return null

            val routes = obj.getJSONArray("routes")
            if (routes.length() == 0) return null

            val route = routes.getJSONObject(0)
            val distance = route.getDouble("distance")
            val duration = route.getDouble("duration")

            // Geometrie parsen
            val geometryObj = route.getJSONObject("geometry")
            val coordinates = geometryObj.getJSONArray("coordinates")
            val geometry = mutableListOf<LatLng>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                geometry.add(LatLng(coord.getDouble(1), coord.getDouble(0)))
            }

            // Schritte parsen. router.project-osrm.org liefert (Stand 2026-11) überhaupt keine
            // intersection-classes → Straßentyp dann unbekannt (null) statt "keine Autobahn".
            val legs = route.getJSONArray("legs")
            val steps = mutableListOf<RouteStep>()
            val hasClasses = json.contains("\"classes\"")

            for (legIdx in 0 until legs.length()) {
                val leg = legs.getJSONObject(legIdx)
                val legSteps = leg.getJSONArray("steps")

                var lanesForNextManeuver: LaneInfo? = null  // Lanes kurz vor dem Step-Ende gelten fürs nächste Manöver
                for (stepIdx in 0 until legSteps.length()) {
                    val (parts, carry) = parseOsrmStep(legSteps.getJSONObject(stepIdx), lanesForNextManeuver, hasClasses)
                    steps += parts
                    lanesForNextManeuver = carry
                }
            }

            return Route(
                distance = distance,
                duration = duration,
                geometry = geometry,
                steps = steps,
                hasTrafficData = false
            )
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "Parse OSRM route failed", e)
            return null
        }
    }

    /** Spuren an EINER OSRM-Intersection (gelten laut OSRM-Doku für genau diese Kreuzung). */
    private fun parseIntersectionLanes(intersection: JSONObject): LaneInfo? {
        val lanesArray = intersection.optJSONArray("lanes") ?: return null
        if (lanesArray.length() == 0) return null
        val lanes = mutableListOf<Lane>()
        var recommendedIndex = -1
        for (j in 0 until lanesArray.length()) {
            val laneObj = lanesArray.getJSONObject(j)
            val isValid = laneObj.optBoolean("valid", false)
            // indications: ALLE Richtungen pro Spur, z.B. ["straight", "right"]
            val indications = laneObj.optJSONArray("indications")
            val directions = if (indications != null && indications.length() > 0) {
                (0 until indications.length()).map { idx -> mapOsrmLaneDirection(indications.getString(idx)) }
            } else {
                listOf("straight")
            }
            if (isValid && recommendedIndex == -1) recommendedIndex = j
            lanes.add(Lane(directions = directions, isRecommended = isValid))
        }
        return LaneInfo(lanes = lanes, recommendedLaneIndex = recommendedIndex)
    }

    /**
     * Ein OSRM-Step → ein oder mehrere RouteSteps. OSRM unterdrückt "geradeaus weiter" an
     * Spuraufteilungen (Autobahnkreuz, Gabelung Mittlerer Ring) — dort fehlte dem Fahrer die
     * Spuranzeige. Solche Intersections werden zu eigenen "fork"-Steps mit ihren Spuren.
     * Rückgabe: (Steps, Spuren der letzten Spur-Kreuzung <= 200 m vor Step-Ende fürs nächste Manöver).
     */
    private fun parseOsrmStep(step: JSONObject, lanesBefore: LaneInfo?, hasClasses: Boolean): Pair<List<RouteStep>, LaneInfo?> {
        val maneuverObj = step.getJSONObject("maneuver")
        val rawType = maneuverObj.getString("type")
        val (type, modifier) = normalizeOsrmManeuver(rawType, maneuverObj.optString("modifier", null))
        // Kreisverkehr-Ausfahrt extrahieren (OSRM liefert "exit" bei roundabout/rotary)
        val exitNumber = if (rawType in listOf("roundabout", "rotary", "exit roundabout", "exit rotary")) {
            maneuverObj.optInt("exit", 0).takeIf { it > 0 }
        } else null
        val geometry = step.getJSONObject("geometry").getJSONArray("coordinates").let { c ->
            (0 until c.length()).map { c.getJSONArray(it).toLatLng() }
        }
        val ints = step.optJSONArray("intersections")
        val inter = (0 until (ints?.length() ?: 0)).map { ints!!.getJSONObject(it) }
        val name = step.optString("name", "")
        fun motorway(i: JSONObject?) = if (hasClasses) i?.let { isOsrmMotorwayIntersection(it) } ?: false else null

        // Spuraufteilungen innerhalb des Steps (k >= 1; k = 0 ist das Manöver selbst)
        data class Split(val k: Int, val loc: LatLng, val lanes: LaneInfo, val side: String, val geomIdx: Int)
        val splits = mutableListOf<Split>()
        var searchFrom = 0
        for (k in 1 until inter.size) {
            val lanes = parseIntersectionLanes(inter[k]) ?: continue
            val side = laneForkSide(lanes) ?: continue
            val loc = inter[k].getJSONArray("location").toLatLng()
            // gleiche Aufteilung an direkt aufeinanderfolgenden Knoten nur einmal
            val prev = splits.lastOrNull()
            if (prev != null && prev.lanes.lanes.size == lanes.lanes.size && prev.loc.distanceTo(loc) < 150.0) continue
            val gi = (searchFrom until geometry.size).minByOrNull { geometry[it].distanceTo(loc) } ?: continue
            if (gi == 0 || gi >= geometry.size - 1) continue
            searchFrom = gi
            splits += Split(k, loc, lanes, side, gi)
        }

        val cuts = listOf(0) + splits.map { it.geomIdx } + listOf(geometry.size - 1)
        val cum = OsmRouteMatcher.cumulative(geometry)
        val total = cum.lastOrNull() ?: 0.0
        val stepDistance = step.getDouble("distance")
        val stepDuration = step.getDouble("duration")
        val parts = (0 until cuts.size - 1).map { p ->
            val share = if (total > 0) (cum[cuts[p + 1]] - cum[cuts[p]]) / total else if (p == 0) 1.0 else 0.0
            val partGeom = geometry.subList(cuts[p], cuts[p + 1] + 1)
            if (p == 0) {
                RouteStep(
                    instruction = name,
                    distance = stepDistance * share,
                    duration = stepDuration * share,
                    maneuver = Maneuver(type, modifier, maneuverObj.getJSONArray("location").toLatLng(), exitNumber),
                    geometry = partGeom,
                    // Lane-Info aus intersections[0] = Kreuzung des Manövers (OSM turn:lanes)
                    laneInfo = inter.firstOrNull()?.let { parseIntersectionLanes(it) } ?: lanesBefore,
                    isMotorway = motorway(inter.firstOrNull())
                )
            } else {
                val s = splits[p - 1]
                CrashLogger.log("RouteRepository: Spuraufteilung als fork ${s.side} auf '$name' @ ${s.loc.lat},${s.loc.lng}: " +
                    s.lanes.lanes.joinToString("|") { it.directions.joinToString("+") + if (it.isRecommended) "*" else "" })
                RouteStep(
                    instruction = name,
                    distance = stepDistance * share,
                    duration = stepDuration * share,
                    maneuver = Maneuver("fork", s.side, s.loc),
                    geometry = partGeom,
                    laneInfo = s.lanes,
                    isMotorway = motorway(inter[s.k])
                )
            }
        }

        // Nur Zwischenkreuzungen (nicht das eigene Manöver: sonst erschienen z. B. Ausfahrt-Spuren
        // nochmal am Rampenende) und nur aussagekräftige Spuren (nicht alle empfohlen: sonst
        // "4x geradeaus" vor einem Rechtsabbiegen, Testfahrt Dachauer Str.)
        val end = geometry.lastOrNull()
        val carry = (inter.size - 1 downTo 1)
            .firstNotNullOfOrNull { k -> parseIntersectionLanes(inter[k])?.let { inter[k] to it } }
            ?.takeIf { (i, lanes) ->
                lanes.lanes.any { !it.isRecommended } &&
                    end != null && i.getJSONArray("location").toLatLng().distanceTo(end) <= 200.0
            }
            ?.second
        return parts to carry
    }

    /**
     * Spuren, an denen sich die Fahrbahn aufteilt (>= 2 nicht empfohlene Spuren, die nur geradeaus/
     * leicht abzweigen — keine Abbiegespuren einer Stadtkreuzung) und die empfohlenen Spuren
     * zusammenhängend links oder rechts liegen → "left"/"right" (wie HERE "keep"), sonst null.
     */
    private fun laneForkSide(info: LaneInfo): String? {
        val rec = info.lanes.map { it.isRecommended }
        val notRec = info.lanes.filter { !it.isRecommended }
        if (notRec.size < 2 || notRec.size == info.lanes.size) return null
        if (notRec.any { lane -> lane.directions.any { it !in setOf("straight", "slightLeft", "slightRight") } }) return null
        val first = rec.indexOf(true)
        val last = rec.lastIndexOf(true)
        if ((first..last).any { !rec[it] }) return null
        return when {
            first == 0 -> "left"
            last == rec.lastIndex -> "right"
            else -> null
        }
    }

    /**
     * OSRM-Manöver auf die Typen abbilden, die NavigationState/NavigationPanel kennen
     * (dort aus der HERE-Zeit: fork nur left/right, "end of road"/"continue left" unbekannt).
     */
    private fun normalizeOsrmManeuver(type: String, modifier: String?): Pair<String, String?> = when {
        type == "fork" -> "fork" to when {
            modifier?.contains("left") == true -> "left"
            modifier?.contains("right") == true -> "right"
            else -> modifier
        }
        // "roundabout turn" = Mini-Kreisel, laut OSRM-Doku wie normales Abbiegen zu behandeln
        type == "end of road" || type == "roundabout turn" -> "turn" to modifier
        // "turn straight" = geradeaus über Kreuzung mit Namenswechsel — kein "Abbiegen" ansagen
        type == "turn" && modifier == "straight" -> "continue" to modifier
        // "continue" mit echter Richtung = abbiegen, um auf derselben Straße zu bleiben
        type == "continue" && modifier != null && modifier != "straight" && !modifier.startsWith("slight") -> "turn" to modifier
        else -> type to modifier
    }

    private fun org.json.JSONArray.toLatLng() = LatLng(getDouble(1), getDouble(0))

    /**
     * Konvertiert OSRM Lane-Richtungen zu unseren internen Namen
     */
    private fun mapOsrmLaneDirection(osrmDirection: String): String {
        // OSRM liefert laut Doku und real "slight right", "sharp left" (Leerzeichen) — die alten
        // Schlüssel mit "_" griffen nie, NavigationPanel zeigte dafür das Geradeaus-Icon.
        return when (osrmDirection.replace(' ', '_')) {
            "left" -> "left"
            "slight_left" -> "slightLeft"
            "sharp_left" -> "sharpLeft"
            "right" -> "right"
            "slight_right" -> "slightRight"
            "sharp_right" -> "sharpRight"
            "straight" -> "straight"
            "uturn" -> "uTurn"
            "merge_to_left" -> "mergeLeft"
            "merge_to_right" -> "mergeRight"
            "none" -> "straight"  // Keine Markierung = geradeaus
            else -> osrmDirection
        }
    }

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
    }
}
