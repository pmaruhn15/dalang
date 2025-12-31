package de.dalang.nav.navigation

import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * Kraftstoffpreise für eine Tankstelle
 */
data class FuelPrices(
    val diesel: Double?,  // Preis in Euro
    val e5: Double?,      // Super E5
    val e10: Double?      // Super E10
)

/**
 * POI (Point of Interest) Daten
 */
data class Poi(
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val distanceKm: Double,  // Entfernung vom aktuellen Standort
    val estimatedArrivalMinutes: Int,  // Geschätzte Ankunftszeit in Minuten
    val fuelPrices: FuelPrices? = null  // Nur für Tankstellen
)

enum class PoiType(val searchQuery: String, val displayName: String) {
    MCDONALDS("McDonald's", "McDonald's"),
    GAS_STATION("Tankstelle", "Tankstelle")
}

/**
 * Repository für POI-Suche (McDonald's, Tankstellen, etc.)
 */
class PoiRepository {

    // Tankerkönig API Key (öffentlicher Demo-Key)
    private val tankerkoenigApiKey = "00000000-0000-0000-0000-000000000002"

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

            val results = when (type) {
                PoiType.GAS_STATION -> searchGasStationsWithTankerkoenig(currentLocation, radiusKm)
                else -> searchWithNominatim(type, currentLocation, radiusKm)
            }

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

    /**
     * Sucht Tankstellen mit Tankerkönig API inkl. Spritpreise
     */
    private suspend fun searchGasStationsWithTankerkoenig(
        center: LatLng,
        radiusKm: Double
    ): List<Poi> = withContext(Dispatchers.IO) {
        try {
            // Tankerkönig API - radius in km (max 25)
            val radius = radiusKm.coerceAtMost(25.0)
            val url = "https://creativecommons.tankerkoenig.de/json/list.php?" +
                "lat=${center.lat}" +
                "&lng=${center.lng}" +
                "&rad=$radius" +
                "&sort=dist" +
                "&type=all" +
                "&apikey=$tankerkoenigApiKey"

            CrashLogger.log("PoiRepository: Tankerkönig URL: $url")

            val connection = URL(url).openConnection()
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.getInputStream().bufferedReader().readText()
            val json = JSONObject(response)

            if (!json.optBoolean("ok", false)) {
                CrashLogger.log("PoiRepository: Tankerkönig returned error: ${json.optString("message")}")
                return@withContext searchWithNominatim(PoiType.GAS_STATION, center, radiusKm)
            }

            val stations = json.optJSONArray("stations") ?: return@withContext emptyList()
            val results = mutableListOf<Poi>()

            for (i in 0 until stations.length()) {
                val station = stations.getJSONObject(i)
                val lat = station.optDouble("lat", 0.0)
                val lng = station.optDouble("lng", 0.0)
                val name = station.optString("brand", "Tankstelle")
                val street = station.optString("street", "")
                val houseNumber = station.optString("houseNumber", "")
                val place = station.optString("place", "")

                val address = buildString {
                    if (street.isNotEmpty()) {
                        append(street)
                        if (houseNumber.isNotEmpty()) append(" $houseNumber")
                    }
                    if (place.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append(place)
                    }
                }

                val distance = station.optDouble("dist",
                    calculateDistance(center.lat, center.lng, lat, lng))
                val arrivalMinutes = estimateArrivalTime(distance)

                // Kraftstoffpreise
                val diesel = station.optDouble("diesel", Double.NaN).takeIf { !it.isNaN() }
                val e5 = station.optDouble("e5", Double.NaN).takeIf { !it.isNaN() }
                val e10 = station.optDouble("e10", Double.NaN).takeIf { !it.isNaN() }

                val fuelPrices = if (diesel != null || e5 != null || e10 != null) {
                    FuelPrices(diesel = diesel, e5 = e5, e10 = e10)
                } else null

                results.add(
                    Poi(
                        name = name,
                        lat = lat,
                        lng = lng,
                        address = address.ifEmpty { null },
                        distanceKm = distance,
                        estimatedArrivalMinutes = arrivalMinutes,
                        fuelPrices = fuelPrices
                    )
                )
            }

            results

        } catch (e: Exception) {
            CrashLogger.logError("PoiRepository", "Tankerkönig search failed, falling back to Nominatim", e)
            searchWithNominatim(PoiType.GAS_STATION, center, radiusKm)
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
