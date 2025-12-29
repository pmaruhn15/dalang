package de.dalang.nav.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RouteStep(
    val instruction: String,
    val distance: Double,
    val duration: Double,
    val maneuver: Maneuver,
    val geometry: List<LatLng>
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
    val steps: List<RouteStep>
)

class RouteRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getRoute(from: LatLng, to: LatLng): Route? = withContext(Dispatchers.IO) {
        try {
            val url = "$OSRM_BASE_URL/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}" +
                    "?overview=full&geometries=geojson&steps=true&annotations=true"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            parseRoute(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRoute(json: String): Route? {
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

        return Route(distance, duration, geometry, steps)
    }

    companion object {
        private const val OSRM_BASE_URL = "https://router.project-osrm.org"
    }
}
