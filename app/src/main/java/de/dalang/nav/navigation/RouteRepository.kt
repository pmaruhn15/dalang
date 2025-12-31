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
    val direction: String,  // "straight", "left", "right", "slightLeft", etc.
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
    val location: LatLng
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
        if (HereConfig.isConfigured() && HereConfig.canMakeRequest()) {
            CrashLogger.log("RouteRepository: Using HERE API with traffic (${HereConfig.getTodayUsage()}/${HereConfig.getDailyLimit()} today)")
            val hereRoute = getRouteFromHere(from, to)
            if (hereRoute != null && hereRoute.geometry.isNotEmpty()) {
                CrashLogger.log("RouteRepository: HERE route OK with ${hereRoute.geometry.size} points")
                hereRoute
            } else {
                if (hereRoute != null) {
                    CrashLogger.log("RouteRepository: HERE route has empty geometry, falling back to OSRM")
                } else {
                    CrashLogger.log("RouteRepository: HERE failed, falling back to OSRM")
                }
                getRouteFromOsrm(from, to)
            }
        } else if (HereConfig.isConfigured() && !HereConfig.canMakeRequest()) {
            CrashLogger.log("RouteRepository: HERE limit reached, falling back to OSRM")
            getRouteFromOsrm(from, to)
        } else {
            CrashLogger.log("RouteRepository: Using OSRM (no HERE API key)")
            getRouteFromOsrm(from, to)
        }
    }

    private suspend fun getRouteFromHere(from: LatLng, to: LatLng): Route? {
        try {
            val apiKey = HereConfig.getApiKey()
            CrashLogger.log("RouteRepository: HERE API key length: ${apiKey.length}")

            val url = "${HereConfig.ROUTING_BASE_URL}/routes" +
                    "?origin=${from.lat},${from.lng}" +
                    "&destination=${to.lat},${to.lng}" +
                    "&transportMode=car" +
                    "&return=polyline,actions,instructions,summary,typicalDuration" +
                    "&spans=names,length,duration,speedLimit,laneAssistance" +
                    "&apiKey=$apiKey"

            CrashLogger.log("RouteRepository: HERE request from ${from.lat},${from.lng} to ${to.lat},${to.lng}")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            CrashLogger.log("RouteRepository: Sending HERE request...")
            val response = client.newCall(request).execute()
            val body = response.body?.string()

            CrashLogger.log("RouteRepository: HERE response code: ${response.code}")

            if (body != null) {
                // Log first 500 chars of response for debugging
                val preview = if (body.length > 500) body.substring(0, 500) + "..." else body
                CrashLogger.log("RouteRepository: HERE response preview: $preview")
            } else {
                CrashLogger.log("RouteRepository: HERE response body is NULL")
            }

            if (!response.isSuccessful || body == null) {
                CrashLogger.logError("RouteRepository", "HERE API error: ${response.code} - ${response.message}")
                return null
            }

            // Zaehler erhoehen nach erfolgreicher Anfrage
            val newCount = HereConfig.incrementUsage()
            CrashLogger.log("RouteRepository: HERE usage now $newCount/${HereConfig.getDailyLimit()}")

            val route = parseHereRoute(body, from)
            if (route == null) {
                CrashLogger.logError("RouteRepository", "HERE route parsing returned null")
            } else {
                CrashLogger.log("RouteRepository: HERE route parsed: ${route.distance}m, ${route.duration}s, ${route.geometry.size} points")
            }
            return route
        } catch (e: Exception) {
            CrashLogger.logError("RouteRepository", "HERE routing failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return null
        }
    }

    private fun parseHereRoute(json: String, origin: LatLng): Route? {
        try {
            CrashLogger.log("RouteRepository: Parsing HERE response...")
            val obj = JSONObject(json)

            // Check for error response
            if (obj.has("error")) {
                val error = obj.optString("error", "unknown")
                val errorDesc = obj.optString("error_description", "no description")
                CrashLogger.logError("RouteRepository", "HERE API error: $error - $errorDesc")
                return null
            }

            if (!obj.has("routes")) {
                CrashLogger.logError("RouteRepository", "HERE response has no 'routes' field. Keys: ${obj.keys().asSequence().toList()}")
                return null
            }

            val routes = obj.getJSONArray("routes")
            CrashLogger.log("RouteRepository: Found ${routes.length()} routes")
            if (routes.length() == 0) {
                CrashLogger.logError("RouteRepository", "HERE returned 0 routes")
                return null
            }

            val route = routes.getJSONObject(0)
            val sections = route.getJSONArray("sections")
            CrashLogger.log("RouteRepository: Route has ${sections.length()} sections")
            if (sections.length() == 0) {
                CrashLogger.logError("RouteRepository", "HERE route has 0 sections")
                return null
            }

            val section = sections.getJSONObject(0)
            val summary = section.getJSONObject("summary")

            val distance = summary.getDouble("length")
            val duration = summary.getDouble("duration").toDouble()
            val typicalDuration = summary.optDouble("typicalDuration", duration)
            CrashLogger.log("RouteRepository: Summary - distance: $distance, duration: $duration")

            // Geometrie dekodieren (HERE Flexible Polyline)
            val polyline = section.getString("polyline")
            CrashLogger.log("RouteRepository: Polyline length: ${polyline.length}")
            val decodedGeometry = FlexiblePolyline.decode(polyline)

            // Fix: HERE polyline encoding has a bug where first point's longitude is nearly 0
            // If first point's longitude is clearly wrong, use origin coordinates
            val geometry = if (decodedGeometry.isNotEmpty()) {
                val first = decodedGeometry.first()
                if (Math.abs(first.lng) < 1.0 && decodedGeometry.size > 1) {
                    // First point longitude is wrong, use origin
                    CrashLogger.log("RouteRepository: Fixing first point longitude from ${first.lng} to ${origin.lng}")
                    listOf(LatLng(first.lat, origin.lng)) + decodedGeometry.drop(1)
                } else {
                    decodedGeometry
                }
            } else {
                decodedGeometry
            }

            CrashLogger.log("RouteRepository: HERE route decoded with ${geometry.size} points")

            // Lane-Info aus Spans extrahieren
            val laneInfoMap = mutableMapOf<Int, LaneInfo>()  // offset -> LaneInfo
            val spans = section.optJSONArray("spans")
            if (spans != null) {
                for (i in 0 until spans.length()) {
                    val span = spans.getJSONObject(i)
                    val laneAssistanceArray = span.optJSONArray("laneAssistance")
                    if (laneAssistanceArray != null && laneAssistanceArray.length() > 0) {
                        val offset = span.optInt("offset", 0)
                        val lanes = mutableListOf<Lane>()
                        var recommendedIndex = -1

                        // Erste lane group nehmen
                        val laneGroup = laneAssistanceArray.getJSONObject(0)
                        val lanesArray = laneGroup.optJSONArray("lanes")
                        if (lanesArray != null) {
                            for (j in 0 until lanesArray.length()) {
                                val laneObj = lanesArray.getJSONObject(j)
                                val directionsArray = laneObj.optJSONArray("directions")
                                val direction = if (directionsArray != null && directionsArray.length() > 0) {
                                    directionsArray.getString(0)
                                } else {
                                    "straight"
                                }
                                val isRecommended = laneObj.optBoolean("isRecommended", false)
                                if (isRecommended && recommendedIndex == -1) {
                                    recommendedIndex = j
                                }
                                lanes.add(Lane(direction = direction, isRecommended = isRecommended))
                            }
                        }
                        if (lanes.isNotEmpty()) {
                            laneInfoMap[offset] = LaneInfo(lanes = lanes, recommendedLaneIndex = recommendedIndex)
                        }
                    }
                }
            }
            CrashLogger.log("RouteRepository: Found ${laneInfoMap.size} spans with lane info")

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

                    // Lane-Info für diesen Offset oder nächstliegenden davor finden
                    val laneInfo = laneInfoMap[offset] ?: laneInfoMap.entries
                        .filter { it.key < offset }
                        .maxByOrNull { it.key }?.value

                    steps.add(
                        RouteStep(
                            instruction = instruction,
                            distance = actionLength,
                            duration = actionDuration,
                            maneuver = Maneuver(
                                type = maneuverType,
                                modifier = modifier,
                                location = location
                            ),
                            geometry = stepGeometry,
                            laneInfo = laneInfo
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

                    steps.add(
                        RouteStep(
                            instruction = step.optString("name", ""),
                            distance = step.getDouble("distance"),
                            duration = step.getDouble("duration"),
                            maneuver = Maneuver(
                                type = maneuverObj.getString("type"),
                                modifier = maneuverObj.optString("modifier", null),
                                location = LatLng(location.getDouble(1), location.getDouble(0))
                            ),
                            geometry = stepGeometry
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

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
    }
}
