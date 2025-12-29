package de.dalang.nav.search

import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val type: String,
    val distance: Double = 0.0
)

class SearchRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Suche mit Photon (Komoot) - bessere Autocomplete/Fuzzy-Suche als Nominatim
     */
    suspend fun search(
        query: String,
        currentLat: Double? = null,
        currentLon: Double? = null
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")

            // Photon API - bessere Autocomplete-Unterstuetzung
            var url = "$PHOTON_BASE_URL?q=$encodedQuery&limit=15"

            // Standort-Bias hinzufuegen wenn vorhanden
            if (currentLat != null && currentLon != null) {
                url += "&lat=$currentLat&lon=$currentLon"
            }

            CrashLogger.log("SearchRepository: Searching for '$query'")

            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val results = parsePhotonResults(body, currentLat, currentLon)

            CrashLogger.log("SearchRepository: Found ${results.size} results")
            results.take(10)
        } catch (e: Exception) {
            CrashLogger.logError("SearchRepository", "Search failed", e)
            emptyList()
        }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$NOMINATIM_BASE_URL/reverse?lat=$lat&lon=$lon&format=json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            val json = JSONObject(body)
            json.optString("display_name", null)
        } catch (e: Exception) {
            CrashLogger.logError("SearchRepository", "Reverse geocode failed", e)
            null
        }
    }

    private fun parsePhotonResults(
        json: String,
        currentLat: Double?,
        currentLon: Double?
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val root = JSONObject(json)
            val features = root.getJSONArray("features")

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val props = feature.getJSONObject("properties")

                val coordinates = geometry.getJSONArray("coordinates")
                val lon = coordinates.getDouble(0)
                val lat = coordinates.getDouble(1)

                // Display-Name zusammenbauen
                val name = props.optString("name", "")
                val street = props.optString("street", "")
                val housenumber = props.optString("housenumber", "")
                val city = props.optString("city", "")
                val state = props.optString("state", "")
                val country = props.optString("country", "")

                val displayParts = mutableListOf<String>()
                if (name.isNotBlank()) displayParts.add(name)
                if (street.isNotBlank()) {
                    val streetFull = if (housenumber.isNotBlank()) "$street $housenumber" else street
                    if (streetFull != name) displayParts.add(streetFull)
                }
                if (city.isNotBlank() && city != name) displayParts.add(city)
                if (state.isNotBlank() && state != city) displayParts.add(state)
                if (country.isNotBlank()) displayParts.add(country)

                val displayName = displayParts.joinToString(", ")

                // Distanz berechnen
                val distance = if (currentLat != null && currentLon != null) {
                    haversineDistance(currentLat, currentLon, lat, lon)
                } else {
                    0.0
                }

                val type = props.optString("osm_value", props.optString("type", "place"))

                results.add(
                    SearchResult(
                        displayName = displayName,
                        lat = lat,
                        lon = lon,
                        type = type,
                        distance = distance
                    )
                )
            }
        } catch (e: Exception) {
            CrashLogger.logError("SearchRepository", "Parse Photon results failed", e)
        }

        // Photon sortiert bereits nach Relevanz (inkl. Standort-Bias)
        // aber wir sortieren nochmal nach Distanz falls gewuenscht
        return if (currentLat != null && currentLon != null) {
            results.sortedBy { it.distance }
        } else {
            results
        }
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    companion object {
        private const val PHOTON_BASE_URL = "https://photon.komoot.io/api"
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
    }
}
