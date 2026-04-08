package de.dalang.nav.navigation

import com.stadiamaps.ferrostar.core.CustomRouteProvider
import de.dalang.nav.config.HereConfig
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uniffi.ferrostar.*
import java.util.concurrent.TimeUnit

/**
 * Custom RouteProvider für Ferrostar der HERE API für Routing nutzt.
 * Fallback auf OSRM wenn HERE nicht verfügbar.
 *
 * Features:
 * - HERE Routing v8 API mit Echtzeit-Verkehrsdaten
 * - OSRM Fallback (kostenlos, ohne Traffic)
 * - Konvertiert Responses zu Ferrostar Route Format
 */
class HereRouteProvider : CustomRouteProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun getRoutes(
        userLocation: UserLocation,
        waypoints: List<Waypoint>
    ): List<Route> = withContext(Dispatchers.IO) {
        try {
            val from = userLocation.coordinates
            val to = waypoints.lastOrNull()?.coordinate
                ?: return@withContext emptyList()

            // HERE API wenn konfiguriert
            if (HereConfig.isConfigured() && HereConfig.canMakeRequest()) {
                val hereRoute = getRouteFromHere(from, to, waypoints)
                if (hereRoute != null) {
                    HereConfig.incrementUsage()
                    return@withContext listOf(hereRoute)
                }
            }

            // Fallback: OSRM
            val osrmRoute = getRouteFromOsrm(from, to, waypoints)
            if (osrmRoute != null) {
                return@withContext listOf(osrmRoute)
            }

            emptyList()
        } catch (e: Exception) {
            CrashLogger.logError("HereRouteProvider", "Route fetch failed", e)
            emptyList()
        }
    }

    private fun getRouteFromHere(
        from: GeographicCoordinate,
        to: GeographicCoordinate,
        waypoints: List<Waypoint>
    ): Route? {
        try {
            val apiKey = HereConfig.getApiKey()

            // Build waypoints string for HERE API
            val waypointsParam = buildString {
                append("origin=${from.lat},${from.lng}")
                // Intermediate waypoints
                waypoints.dropLast(1).forEachIndexed { index, wp ->
                    append("&via=${wp.coordinate.lat},${wp.coordinate.lng}")
                }
                append("&destination=${to.lat},${to.lng}")
            }

            val url = "${HereConfig.ROUTING_BASE_URL}/routes?" +
                    waypointsParam +
                    "&transportMode=car" +
                    "&return=polyline,actions,instructions,summary,typicalDuration,turnByTurnActions" +
                    "&spans=names,length,duration,speedLimit" +
                    "&apiKey=$apiKey"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null

            if (!response.isSuccessful) {
                CrashLogger.logError("HereRouteProvider", "HERE API error: ${response.code}")
                return null
            }

            return parseHereResponse(body, waypoints)
        } catch (e: Exception) {
            CrashLogger.logError("HereRouteProvider", "HERE routing failed", e)
            return null
        }
    }

    private fun getRouteFromOsrm(
        from: GeographicCoordinate,
        to: GeographicCoordinate,
        waypoints: List<Waypoint>
    ): Route? {
        try {
            // Build coordinates string: from;via1;via2;...;to
            val coords = buildString {
                append("${from.lng},${from.lat}")
                waypoints.dropLast(1).forEach { wp ->
                    append(";${wp.coordinate.lng},${wp.coordinate.lat}")
                }
                append(";${to.lng},${to.lat}")
            }

            val url = "$OSRM_BASE_URL/route/v1/driving/$coords" +
                    "?overview=full&geometries=geojson&steps=true&annotations=true"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null

            return parseOsrmResponse(body, waypoints)
        } catch (e: Exception) {
            CrashLogger.logError("HereRouteProvider", "OSRM routing failed", e)
            return null
        }
    }

    private fun parseHereResponse(json: String, waypoints: List<Waypoint>): Route? {
        try {
            val obj = JSONObject(json)
            if (!obj.has("routes")) return null

            val routes = obj.getJSONArray("routes")
            if (routes.length() == 0) return null

            val route = routes.getJSONObject(0)
            val sections = route.getJSONArray("sections")
            if (sections.length() == 0) return null

            val section = sections.getJSONObject(0)
            val summary = section.getJSONObject("summary")

            val distance = summary.getDouble("length")
            val duration = summary.getDouble("duration")

            // Decode polyline
            val polyline = section.getString("polyline")
            val geometry = FlexiblePolyline.decode(polyline).map { latLng ->
                GeographicCoordinate(latLng.lat, latLng.lng)
            }

            // Calculate bounding box
            val bbox = calculateBbox(geometry)

            // Parse actions/steps
            val steps = mutableListOf<RouteStep>()
            val actions = section.optJSONArray("actions")

            if (actions != null) {
                for (i in 0 until actions.length()) {
                    val action = actions.getJSONObject(i)
                    val step = parseHereAction(action, geometry)
                    if (step != null) {
                        steps.add(step)
                    }
                }
            }

            // If no steps, create a simple one
            if (steps.isEmpty()) {
                steps.add(createRouteStep(
                    geometry = geometry,
                    distance = distance,
                    duration = duration,
                    roadName = null,
                    instruction = "Route folgen"
                ))
            }

            return Route(
                geometry = geometry,
                bbox = bbox,
                distance = distance,
                waypoints = waypoints,
                steps = steps
            )
        } catch (e: Exception) {
            CrashLogger.logError("HereRouteProvider", "Parse HERE response failed", e)
            return null
        }
    }

    private fun parseHereAction(action: JSONObject, fullGeometry: List<GeographicCoordinate>): RouteStep? {
        try {
            val instruction = action.optString("instruction", "")
            val actionDuration = action.optDouble("duration", 0.0)
            val actionLength = action.optDouble("length", 0.0)
            val offset = action.optInt("offset", 0)

            // Get geometry segment for this step
            val nextOffset = action.optInt("nextOffset", fullGeometry.size)
            val stepGeometry = fullGeometry.subList(
                offset.coerceIn(0, fullGeometry.size),
                nextOffset.coerceIn(0, fullGeometry.size)
            ).ifEmpty { listOf(fullGeometry.getOrElse(offset) { fullGeometry.first() }) }

            return createRouteStep(
                geometry = stepGeometry,
                distance = actionLength,
                duration = actionDuration,
                roadName = action.optString("currentRoad.name.value", null),
                instruction = instruction
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseOsrmResponse(json: String, waypoints: List<Waypoint>): Route? {
        try {
            val obj = JSONObject(json)
            if (obj.getString("code") != "Ok") return null

            val routes = obj.getJSONArray("routes")
            if (routes.length() == 0) return null

            val route = routes.getJSONObject(0)
            val distance = route.getDouble("distance")
            val duration = route.getDouble("duration")

            // Parse geometry
            val geometryObj = route.getJSONObject("geometry")
            val coordinates = geometryObj.getJSONArray("coordinates")
            val geometry = mutableListOf<GeographicCoordinate>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                geometry.add(GeographicCoordinate(coord.getDouble(1), coord.getDouble(0)))
            }

            val bbox = calculateBbox(geometry)

            // Parse steps
            val steps = mutableListOf<RouteStep>()
            val legs = route.getJSONArray("legs")

            for (legIdx in 0 until legs.length()) {
                val leg = legs.getJSONObject(legIdx)
                val legSteps = leg.getJSONArray("steps")

                for (stepIdx in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(stepIdx)
                    val maneuver = step.getJSONObject("maneuver")

                    // Step geometry
                    val stepGeometryObj = step.getJSONObject("geometry")
                    val stepCoords = stepGeometryObj.getJSONArray("coordinates")
                    val stepGeometry = mutableListOf<GeographicCoordinate>()
                    for (i in 0 until stepCoords.length()) {
                        val coord = stepCoords.getJSONArray(i)
                        stepGeometry.add(GeographicCoordinate(coord.getDouble(1), coord.getDouble(0)))
                    }

                    val maneuverType = maneuver.getString("type")
                    val modifier = maneuver.optString("modifier", "")

                    steps.add(createRouteStep(
                        geometry = stepGeometry,
                        distance = step.getDouble("distance"),
                        duration = step.getDouble("duration"),
                        roadName = step.optString("name", null).takeIf { it.isNotBlank() },
                        instruction = buildInstruction(maneuverType, modifier)
                    ))
                }
            }

            return Route(
                geometry = geometry,
                bbox = bbox,
                distance = distance,
                waypoints = waypoints,
                steps = steps
            )
        } catch (e: Exception) {
            CrashLogger.logError("HereRouteProvider", "Parse OSRM response failed", e)
            return null
        }
    }

    private fun calculateBbox(geometry: List<GeographicCoordinate>): BoundingBox {
        var minLat = Double.MAX_VALUE
        var maxLat = Double.MIN_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = Double.MIN_VALUE

        geometry.forEach { coord ->
            minLat = minOf(minLat, coord.lat)
            maxLat = maxOf(maxLat, coord.lat)
            minLng = minOf(minLng, coord.lng)
            maxLng = maxOf(maxLng, coord.lng)
        }

        return BoundingBox(
            sw = GeographicCoordinate(minLat, minLng),
            ne = GeographicCoordinate(maxLat, maxLng)
        )
    }

    private fun buildInstruction(maneuverType: String, modifier: String): String {
        return when (maneuverType) {
            "depart" -> "Los"
            "arrive" -> "Ziel erreicht"
            "turn" -> when (modifier) {
                "left" -> "Links abbiegen"
                "right" -> "Rechts abbiegen"
                "slight left" -> "Leicht links halten"
                "slight right" -> "Leicht rechts halten"
                "sharp left" -> "Scharf links abbiegen"
                "sharp right" -> "Scharf rechts abbiegen"
                "uturn" -> "Wenden"
                else -> "Abbiegen"
            }
            "continue" -> "Geradeaus weiter"
            "roundabout" -> "In den Kreisverkehr einfahren"
            "exit roundabout" -> "Kreisverkehr verlassen"
            "merge" -> "Einfädeln"
            "fork" -> if (modifier.contains("left")) "Links halten" else "Rechts halten"
            else -> "Weiter"
        }
    }

    /**
     * Helper to create RouteStep with all required fields.
     * This ensures we always provide the correct structure expected by Ferrostar.
     */
    private fun createRouteStep(
        geometry: List<GeographicCoordinate>,
        distance: Double,
        duration: Double,
        roadName: String?,
        instruction: String,
        exits: List<String> = emptyList(),
        roundaboutExitNumber: UByte? = null
    ): RouteStep {
        return RouteStep(
            geometry = geometry,
            distance = distance,
            duration = duration,
            roadName = roadName,
            exits = exits,
            instruction = instruction,
            visualInstructions = emptyList(),
            spokenInstructions = emptyList(),
            annotations = null,
            incidents = emptyList(),
            drivingSide = DrivingSide.RIGHT,
            roundaboutExitNumber = roundaboutExitNumber
        )
    }

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
    }
}
