package de.dalang.nav.navigation

import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * POI (Point of Interest) Daten
 */
data class Poi(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val distanceKm: Double,  // Entfernung vom aktuellen Standort
    val estimatedArrivalMinutes: Int  // Geschätzte Ankunftszeit in Minuten
)

enum class PoiType(val searchQuery: String, val displayName: String) {
    MCDONALDS("McDonald's", "McDonald's"),
    GAS_STATION("Tankstelle", "Tankstelle")
}

/**
 * Repository für POI-Suche (McDonald's, Tankstellen, etc.)
 */
class PoiRepository {

    /**
     * Sucht POIs in der Nähe einer Position
     */
    suspend fun searchNearby(
        type: PoiType,
        currentLocation: LatLng,
        radiusKm: Double = 10.0,
        limit: Int = 10
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            CrashLogger.log("PoiRepository: Searching for ${type.displayName} near ${currentLocation.lat},${currentLocation.lng}")

            // Nominatim Overpass-ähnliche Suche
            val results = searchWithNominatim(type, currentLocation, radiusKm)

            // Nach Entfernung sortieren und limitieren
            val sortedResults = results
                .sortedBy { it.distanceKm }
                .take(limit)

            CrashLogger.log("PoiRepository: Found ${sortedResults.size} ${type.displayName} locations")
            sortedResults

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Search failed for ${type.displayName}", e)
            emptyList()
        }
    }

    private suspend fun searchWithNominatim(
        type: PoiType,
        center: LatLng,
        radiusKm: Double
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            // Bounding Box berechnen
            val latDelta = radiusKm / 111.0  // ~111 km pro Grad Latitude
            val lngDelta = radiusKm / (111.0 * cos(Math.toRadians(center.lat)))

            val minLat = center.lat - latDelta
            val maxLat = center.lat + latDelta
            val minLng = center.lng - lngDelta
            val maxLng = center.lng + lngDelta

            val query = URLEncoder.encode(type.searchQuery, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?" +
                "q=$query" +
                "&format=json" +
                "&limit=20" +
                "&viewbox=$minLng,$maxLat,$maxLng,$minLat" +
                "&bounded=1"

            CrashLogger.log("PoiRepository: Nominatim URL: $url")

            val connection = URL(url).openConnection()
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.getInputStream().bufferedReader().readText()
            val jsonArray = JSONArray(response)

            val results = mutableListOf<Poi>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val lat = item.optDouble("lat", 0.0)
                val lng = item.optDouble("lon", 0.0)
                val displayName = item.optString("display_name", type.displayName)

                // Name extrahieren (erster Teil vor dem Komma)
                val name = displayName.split(",").firstOrNull()?.trim() ?: type.displayName

                // Adresse (Rest nach dem Namen)
                val address = displayName.split(",").drop(1).take(3).joinToString(", ").trim()

                val distance = calculateDistance(center.lat, center.lng, lat, lng)
                val arrivalMinutes = estimateArrivalTime(distance)

                results.add(
                    Poi(
                        name = name,
                        lat = lat,
                        lng = lng,
                        address = address.ifEmpty { null },
                        distanceKm = distance,
                        estimatedArrivalMinutes = arrivalMinutes
                    )
                )
            }

            results

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Nominatim search failed", e)
            emptyList()
        }
    }

    /**
     * Berechnet die Entfernung zwischen zwei Punkten in km (Haversine)
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0 // Erdradius in km

        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLng = Math.toRadians(lng2 - lng1)

        val a = sin(deltaLat / 2).pow(2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }

    /**
     * Schätzt die Ankunftszeit basierend auf Entfernung
     * Annahme: ~40 km/h Durchschnittsgeschwindigkeit in der Stadt
     */
    private fun estimateArrivalTime(distanceKm: Double): Int {
        val avgSpeedKmh = 40.0
        val timeHours = distanceKm / avgSpeedKmh
        return (timeHours * 60).toInt().coerceAtLeast(1)
    }
}
