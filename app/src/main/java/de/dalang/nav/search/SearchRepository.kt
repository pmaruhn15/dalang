package de.dalang.nav.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val type: String
)

class SearchRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$NOMINATIM_BASE_URL/search?q=$encodedQuery&format=json&limit=10&countrycodes=de&addressdetails=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "DaLang Navigation App")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            parseSearchResults(body)
        } catch (e: Exception) {
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
            null
        }
    }

    private fun parseSearchResults(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            results.add(
                SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = obj.getString("lat").toDouble(),
                    lon = obj.getString("lon").toDouble(),
                    type = obj.optString("type", "place")
                )
            )
        }

        return results
    }

    companion object {
        private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org"
    }
}
