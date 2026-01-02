package de.dalang.nav.destinations

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.util.CrashLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gespeichertes Ziel (Favorit oder letztes Ziel)
 */
data class SavedDestination(
    val name: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toLatLng() = LatLng(lat, lng)
}

/**
 * Typ des Favoriten-Ziels
 */
enum class FavoriteType(val displayName: String, val icon: String) {
    HOME("Zuhause", "\uD83C\uDFE0"),  // 🏠
    WORK("Arbeit", "\uD83C\uDFE2")    // 🏢
}

/**
 * Repository für Favoriten und letzte Ziele
 */
class DestinationsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    // ========== Favoriten (Zuhause, Arbeit) ==========

    fun getFavorite(type: FavoriteType): SavedDestination? {
        val key = when (type) {
            FavoriteType.HOME -> KEY_HOME_ADDRESS
            FavoriteType.WORK -> KEY_WORK_ADDRESS
        }
        val json = prefs.getString(key, null) ?: return null
        return try {
            parseDestination(JSONObject(json))
        } catch (e: Exception) {
            CrashLogger.logError("DestinationsRepository", "Failed to parse favorite $type", e)
            null
        }
    }

    fun setFavorite(type: FavoriteType, destination: SavedDestination?) {
        val key = when (type) {
            FavoriteType.HOME -> KEY_HOME_ADDRESS
            FavoriteType.WORK -> KEY_WORK_ADDRESS
        }
        if (destination == null) {
            prefs.edit { remove(key) }
        } else {
            prefs.edit { putString(key, destinationToJson(destination).toString()) }
        }
        CrashLogger.log("DestinationsRepository: Set $type to ${destination?.name}")
    }

    fun hasFavorite(type: FavoriteType): Boolean = getFavorite(type) != null

    // ========== Letzte Ziele ==========

    fun getRecentDestinations(): List<SavedDestination> {
        val json = prefs.getString(KEY_RECENT_DESTINATIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val destinations = mutableListOf<SavedDestination>()
            for (i in 0 until array.length()) {
                parseDestination(array.getJSONObject(i))?.let { destinations.add(it) }
            }
            destinations.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            CrashLogger.logError("DestinationsRepository", "Failed to parse recent destinations", e)
            emptyList()
        }
    }

    fun addRecentDestination(destination: SavedDestination) {
        val current = getRecentDestinations().toMutableList()

        // Duplikate entfernen (gleiche Koordinaten, Toleranz 10m)
        current.removeAll { existing ->
            val latDiff = kotlin.math.abs(existing.lat - destination.lat)
            val lngDiff = kotlin.math.abs(existing.lng - destination.lng)
            latDiff < 0.0001 && lngDiff < 0.0001
        }

        // Neues Ziel an den Anfang
        current.add(0, destination)

        // Auf MAX_RECENT_DESTINATIONS begrenzen
        val limited = current.take(MAX_RECENT_DESTINATIONS)

        // Speichern
        val array = JSONArray()
        limited.forEach { array.put(destinationToJson(it)) }
        prefs.edit { putString(KEY_RECENT_DESTINATIONS, array.toString()) }

        CrashLogger.log("DestinationsRepository: Added recent destination '${destination.name}', total: ${limited.size}")
    }

    fun removeRecentDestination(destination: SavedDestination) {
        val current = getRecentDestinations().toMutableList()
        current.removeAll { existing ->
            val latDiff = kotlin.math.abs(existing.lat - destination.lat)
            val lngDiff = kotlin.math.abs(existing.lng - destination.lng)
            latDiff < 0.0001 && lngDiff < 0.0001
        }

        val array = JSONArray()
        current.forEach { array.put(destinationToJson(it)) }
        prefs.edit { putString(KEY_RECENT_DESTINATIONS, array.toString()) }

        CrashLogger.log("DestinationsRepository: Removed recent destination '${destination.name}'")
    }

    fun clearRecentDestinations() {
        prefs.edit { remove(KEY_RECENT_DESTINATIONS) }
        CrashLogger.log("DestinationsRepository: Cleared all recent destinations")
    }

    // ========== Helper ==========

    private fun parseDestination(json: JSONObject): SavedDestination? {
        return try {
            SavedDestination(
                name = json.getString("name"),
                lat = json.getDouble("lat"),
                lng = json.getDouble("lng"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun destinationToJson(destination: SavedDestination): JSONObject {
        return JSONObject().apply {
            put("name", destination.name)
            put("lat", destination.lat)
            put("lng", destination.lng)
            put("timestamp", destination.timestamp)
        }
    }

    companion object {
        private const val PREFS_NAME = "dalang_destinations"
        private const val KEY_HOME_ADDRESS = "home_address"
        private const val KEY_WORK_ADDRESS = "work_address"
        private const val KEY_RECENT_DESTINATIONS = "recent_destinations"

        const val MAX_RECENT_DESTINATIONS = 10
    }
}
