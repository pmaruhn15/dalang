package de.dalang.nav.navigation

import de.dalang.nav.config.HereConfig
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
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
    val laneInfo: LaneInfo? = null
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

data class Route(
    val distance: Double,
    val duration: Double,
    val geometry: List<LatLng>,
    val steps: List<RouteStep>,
    val hasTrafficData: Boolean = false,
    val typicalDuration: Double? = null  // Typische Dauer ohne Verkehr
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
            val hereRoute = getRouteFromHere(from, to)
            if (hereRoute != null && hereRoute.geometry.isNotEmpty()) {
                // HERE Route erfolgreich - jetzt OSRM Lane-Daten dazu holen
                enrichRouteWithOsrmLanes(hereRoute, from, to)
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
     * Reichert eine HERE-Route mit Lane-Daten von OSRM an.
     * HERE REST API liefert keine Lane-Daten, aber OSRM hat sie aus OSM turn:lanes Tags.
     */
    private suspend fun enrichRouteWithOsrmLanes(hereRoute: Route, from: LatLng, to: LatLng): Route {
        try {
            // OSRM nur für Lane-Daten abfragen
            val osrmLanes = getOsrmLaneData(from, to)
            if (osrmLanes.isEmpty()) {
                CrashLogger.log("RouteRepository: No OSRM lane data available")
                return hereRoute
            }

            CrashLogger.log("RouteRepository: Enriching HERE route with ${osrmLanes.size} OSRM lane infos")

            // Lane-Daten den HERE-Steps zuordnen basierend auf Location-Nähe
            val enrichedSteps = hereRoute.steps.map { step ->
                val nearestLaneInfo = findNearestLaneInfo(step.maneuver.location, osrmLanes)
                if (nearestLaneInfo != null) {
                    step.copy(laneInfo = nearestLaneInfo)
                } else {
                    step
                }
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
    private fun getOsrmLaneData(from: LatLng, to: LatLng): List<Pair<LatLng, LaneInfo>> {
        try {
            val url = "$OSRM_BASE_URL/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}" +
                    "?overview=false&geometries=geojson&steps=true"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()

            return parseOsrmLaneData(body)
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "OSRM lane fetch failed", e)
            return emptyList()
        }
    }

    /**
     * Parst nur die Lane-Daten aus OSRM Response (Location + LaneInfo Paare)
     */
    private fun parseOsrmLaneData(json: String): List<Pair<LatLng, LaneInfo>> {
        val result = mutableListOf<Pair<LatLng, LaneInfo>>()
        try {
            val obj = JSONObject(json)
            if (obj.getString("code") != "Ok") return emptyList()

            val routes = obj.getJSONArray("routes")
            if (routes.length() == 0) return emptyList()

            val route = routes.getJSONObject(0)
            val legs = route.getJSONArray("legs")

            for (legIdx in 0 until legs.length()) {
                val leg = legs.getJSONObject(legIdx)
                val steps = leg.getJSONArray("steps")

                for (stepIdx in 0 until steps.length()) {
                    val step = steps.getJSONObject(stepIdx)
                    val laneInfo = extractOsrmLaneInfo(step) ?: continue

                    // Location aus maneuver extrahieren
                    val maneuver = step.getJSONObject("maneuver")
                    val location = maneuver.getJSONArray("location")
                    val latLng = LatLng(location.getDouble(1), location.getDouble(0))

                    result.add(latLng to laneInfo)
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "Parse OSRM lane data failed", e)
        }
        return result
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

    private suspend fun getRouteFromHere(from: LatLng, to: LatLng): Route? {
        try {
            val apiKey = HereConfig.getApiKey()

            // HERE Routing v8 API
            // HINWEIS: Lane guidance (Spuranzeige) ist NUR im HERE SDK (Navigate Edition) verfügbar,
            // NICHT in der REST API. Die REST API bietet keine laneAssistance in spans.
            // Siehe: https://developer.here.com/documentation/android-sdk-navigate/dev_guide/topics/navigation.html
            val url = "${HereConfig.ROUTING_BASE_URL}/routes" +
                    "?origin=${from.lat},${from.lng}" +
                    "&destination=${to.lat},${to.lng}" +
                    "&transportMode=car" +
                    "&return=polyline,actions,instructions,summary,typicalDuration,turnByTurnActions" +
                    "&spans=names,length,duration,speedLimit,maxSpeed" +
                    "&apiKey=$apiKey"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body == null) {
                // Log error details for debugging
                CrashLogger.logError("RouteRepository", "HERE API error: ${response.code} - ${response.message}")
                if (body != null && body.length < 500) {
                    CrashLogger.log("RouteRepository: Error response: $body")
                }
                return null
            }

            // Zaehler erhoehen nach erfolgreicher Anfrage
            HereConfig.incrementUsage()

            val route = parseHereRoute(body, from)
            if (route == null) {
                CrashLogger.logError("RouteRepository", "HERE route parsing returned null")
            }
            return route
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "HERE routing failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
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

            val route = routes.getJSONObject(0)
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

            return Route(
                distance = distance,
                duration = duration,
                geometry = geometry,
                steps = steps,
                hasTrafficData = true,
                typicalDuration = typicalDuration
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
            "merge" -> "merge" to direction
            "highway" -> "new name" to null
            else -> action to null
        }
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

    private fun parseOsrmRoute(json: String): Route? {
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

            // Schritte parsen
            val legs = route.getJSONArray("legs")
            val steps = mutableListOf<RouteStep>()

            for (legIdx in 0 until legs.length()) {
                val leg = legs.getJSONObject(legIdx)
                val legSteps = leg.getJSONArray("steps")

                for (stepIdx in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(stepIdx)
                    val maneuverObj = step.getJSONObject("maneuver")
                    val location = maneuverObj.getJSONArray("location")

                    val stepGeometryObj = step.getJSONObject("geometry")
                    val stepCoordinates = stepGeometryObj.getJSONArray("coordinates")
                    val stepGeometry = mutableListOf<LatLng>()
                    for (i in 0 until stepCoordinates.length()) {
                        val coord = stepCoordinates.getJSONArray(i)
                        stepGeometry.add(LatLng(coord.getDouble(1), coord.getDouble(0)))
                    }

                    // Lane-Info aus intersections extrahieren (OSRM liefert lanes aus OSM turn:lanes Tag)
                    val laneInfo = extractOsrmLaneInfo(step)

                    // Kreisverkehr-Ausfahrt extrahieren (OSRM liefert "exit" bei roundabout/rotary)
                    val maneuverType = maneuverObj.getString("type")
                    val exitNumber = if (maneuverType in listOf("roundabout", "rotary", "exit roundabout", "exit rotary")) {
                        maneuverObj.optInt("exit", 0).takeIf { it > 0 }
                    } else null

                    steps.add(
                        RouteStep(
                            instruction = step.optString("name", ""),
                            distance = step.getDouble("distance"),
                            duration = step.getDouble("duration"),
                            maneuver = Maneuver(
                                type = maneuverType,
                                modifier = maneuverObj.optString("modifier", null),
                                location = LatLng(location.getDouble(1), location.getDouble(0)),
                                exit = exitNumber
                            ),
                            geometry = stepGeometry,
                            laneInfo = laneInfo
                        )
                    )
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

    /**
     * Extrahiert Lane-Info aus OSRM step.intersections[].lanes
     * OSRM liefert Spurinformationen aus OSM turn:lanes Tags
     *
     * WICHTIG: Wir nehmen die LETZTE intersection mit lanes, da diese
     * die Spuren direkt vor dem Manöver beschreibt (nicht die erste!)
     */
    private fun extractOsrmLaneInfo(step: JSONObject): LaneInfo? {
        try {
            val intersections = step.optJSONArray("intersections") ?: return null

            // LETZTE Intersection mit lanes nehmen (direkt vor dem Manöver!)
            // Rückwärts durch die Intersections gehen
            var bestLaneInfo: LaneInfo? = null
            var bestIntersectionIdx = -1

            for (i in intersections.length() - 1 downTo 0) {
                val intersection = intersections.getJSONObject(i)
                val lanesArray = intersection.optJSONArray("lanes") ?: continue

                if (lanesArray.length() == 0) continue

                val lanes = mutableListOf<Lane>()
                var recommendedIndex = -1

                for (j in 0 until lanesArray.length()) {
                    val laneObj = lanesArray.getJSONObject(j)
                    val isValid = laneObj.optBoolean("valid", false)

                    // indications ist ein Array von ALLEN Richtungen pro Spur
                    // z.B. ["straight", "right"] für eine Spur die geradeaus ODER rechts führt
                    val indications = laneObj.optJSONArray("indications")
                    val directions = if (indications != null && indications.length() > 0) {
                        (0 until indications.length()).map { idx ->
                            mapOsrmLaneDirection(indications.getString(idx))
                        }
                    } else {
                        listOf("straight")
                    }

                    if (isValid && recommendedIndex == -1) {
                        recommendedIndex = j
                    }

                    lanes.add(Lane(directions = directions, isRecommended = isValid))
                }

                if (lanes.isNotEmpty()) {
                    bestLaneInfo = LaneInfo(lanes = lanes, recommendedLaneIndex = recommendedIndex)
                    bestIntersectionIdx = i
                    break  // Letzte gefundene nehmen (von hinten gezählt)
                }
            }

            if (bestLaneInfo != null) {
                // Debug: Lane-Infos loggen mit Intersection-Index
                val stepName = step.optString("name", "unnamed")
                CrashLogger.log("RouteRepository: OSRM Lanes at '$stepName' (intersection $bestIntersectionIdx/${intersections.length()-1}): " +
                    "${bestLaneInfo.lanes.size} lanes: ${bestLaneInfo.lanes.map { "${it.directions.joinToString("+")}${if(it.isRecommended) "*" else ""}" }}")
            }

            return bestLaneInfo
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "Failed to parse OSRM lanes", e)
            return null
        }
    }

    /**
     * Konvertiert OSRM Lane-Richtungen zu unseren internen Namen
     */
    private fun mapOsrmLaneDirection(osrmDirection: String): String {
        return when (osrmDirection) {
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
