package de.dalang.nav.search

import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val type: String,
    val distance: Double = 0.0 // Distanz zum aktuellen Standort in km
)

class SearchRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Suche mit Standort-Bias - Ergebnisse in der Nähe werden bevorzugt
     */
    suspend fun search(
        query: String,
        currentLat: Double? = null,
        currentLon: Double? = null
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")

            // URL mit optionalem Viewbox für lokale Priorisierung
            var url = "$NOMINATIM_BASE_URL/search?q=$encodedQuery&format=json&limit=15&countrycodes=de&addressdetails=1"

            // Wenn Standort vorhanden, Viewbox hinzufügen (ca. 100km um den Standort)
            if (currentLat != null && currentLon != null) {
                val delta = 0.9 // ca. 100km
                val minLon = currentLon - delta
                val maxLon = currentLon + delta
                val minLat = currentLat - delta
                val maxLat = currentLat + delta
                url += "&viewbox=$minLon,$maxLat,$maxLon,$minLat"
                // bounded=0 bedeutet: bevorzuge viewbox, aber zeige auch andere Ergebnisse
            }

            CrashLogger.log("SearchRepository: Searching for '$query'")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val results = parseSearchResults(body, currentLat, currentLon)

            // Nach Distanz sortieren wenn Standort bekannt
            val sortedResults = if (currentLat != null && currentLon != null) {
                results.sortedBy { it.distance }
            } else {
                results
            }

            CrashLogger.log("SearchRepository: Found ${sortedResults.size} results")
            sortedResults.take(10)
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

            val json = org.json.JSONObject(body)
            json.optString("display_name", null)
        } catch (e: Exception) {
            CrashLogger.logError("SearchRepository", "Reverse geocode failed", e)
            null
        }
    }

    private fun parseSearchResults(
        json: String,
        currentLat: Double?,
        currentLon: Double?
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val lat = obj.getString("lat").toDouble()
            val lon = obj.getString("lon").toDouble()

            // Distanz berechnen wenn Standort bekannt
            val distance = if (currentLat != null && currentLon != null) {
                haversineDistance(currentLat, currentLon, lat, lon)
            } else {
                0.0
            }

            results.add(
                SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = lat,
                    lon = lon,
                    type = obj.optString("type", "place"),
                    distance = distance
                )
            )
        }

        return results
    }

    /**
     * Haversine-Formel für Distanzberechnung zwischen zwei Koordinaten
     * Gibt Distanz in Kilometern zurück
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Erdradius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    companion object {
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
    }
}
