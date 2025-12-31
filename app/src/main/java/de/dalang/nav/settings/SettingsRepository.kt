package de.dalang.nav.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * API Usage Info für Fortschrittsanzeige
 */
data class ApiUsageInfo(
    val apiName: String,
    val used: Int,
    val limit: Int,
    val periodType: PeriodType,
    val periodStart: String,  // z.B. "Dezember 2025"
    val status: UsageStatus
) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val percentage: Float get() = if (limit > 0) (used.toFloat() / limit * 100) else 0f
}

enum class PeriodType {
    MONTHLY,
    DAILY
}

enum class UsageStatus {
    OK,         // < 80%
    WARNING,    // 80-99%
    BLOCKED     // >= 100%
}

/**
 * Verfügbare Farben für Route und Marker
 */
enum class MapColor(
    val displayName: String,
    val colorValue: Long  // ARGB Color
) {
    WHITE("Weiß", 0xFFFFFFFF),
    BLACK("Schwarz", 0xFF000000),
    BLUE("Blau", 0xFF2196F3),
    RED("Rot", 0xFFE53935),
    GREEN("Grün", 0xFF4CAF50),
    ORANGE("Orange", 0xFFFF9800),
    PURPLE("Lila", 0xFF9C27B0),
    CYAN("Cyan", 0xFF00BCD4)
}

/**
 * Verfügbare Map Styles von OpenFreeMap
 */
enum class MapStyle(
    val displayName: String,
    val description: String,
    val lightUrl: String,
    val darkUrl: String? = null,  // null = kein Dark-Pendant
    val previewBgColor: Long,     // Hintergrundfarbe für Vorschau
    val previewFgColor: Long,     // Vordergrundfarbe für Vorschau
    val isDark: Boolean = false
) {
    AUTO(
        displayName = "Automatisch",
        description = "Wechselt mit System-Theme",
        lightUrl = "https://tiles.openfreemap.org/styles/positron",
        darkUrl = "https://tiles.openfreemap.org/styles/dark",
        previewBgColor = 0xFFE8E8E8,
        previewFgColor = 0xFF333333
    ),
    POSITRON(
        displayName = "Positron",
        description = "Hell, minimalistisch",
        lightUrl = "https://tiles.openfreemap.org/styles/positron",
        previewBgColor = 0xFFE8E8E8,
        previewFgColor = 0xFF666666
    ),
    DARK(
        displayName = "Dark",
        description = "Dunkel, augenschonend",
        lightUrl = "https://tiles.openfreemap.org/styles/dark",
        previewBgColor = 0xFF1A1A2E,
        previewFgColor = 0xFF888888,
        isDark = true
    ),
    BRIGHT(
        displayName = "Bright",
        description = "Farbenfroh, detailliert",
        lightUrl = "https://tiles.openfreemap.org/styles/bright",
        previewBgColor = 0xFFF5F5DC,
        previewFgColor = 0xFF2E7D32
    ),
    LIBERTY(
        displayName = "Liberty",
        description = "Klassischer OSM-Look",
        lightUrl = "https://tiles.openfreemap.org/styles/liberty",
        previewBgColor = 0xFFF2EFE9,
        previewFgColor = 0xFF725A42
    ),
    FIORD(
        displayName = "Fiord",
        description = "Natürliche Farben",
        lightUrl = "https://tiles.openfreemap.org/styles/fiord",
        previewBgColor = 0xFF3E4A5C,
        previewFgColor = 0xFF8FA4B8,
        isDark = true
    );

    /**
     * Gibt die richtige URL basierend auf isDarkTheme zurück
     */
    fun getUrl(isDarkTheme: Boolean): String {
        return if (isDarkTheme && darkUrl != null) {
            darkUrl
        } else {
            lightUrl
        }
    }
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var hereApiKey: String
        get() = prefs.getString(KEY_HERE_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_HERE_API_KEY, value) }

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_VOICE_ENABLED, value) }

    var mapStyle: MapStyle
        get() {
            val styleName = prefs.getString(KEY_MAP_STYLE, MapStyle.AUTO.name) ?: MapStyle.AUTO.name
            return try {
                MapStyle.valueOf(styleName)
            } catch (e: IllegalArgumentException) {
                MapStyle.AUTO
            }
        }
        set(value) = prefs.edit { putString(KEY_MAP_STYLE, value.name) }

    var routeColor: MapColor
        get() {
            val colorName = prefs.getString(KEY_ROUTE_COLOR, MapColor.WHITE.name) ?: MapColor.WHITE.name
            return try {
                MapColor.valueOf(colorName)
            } catch (e: IllegalArgumentException) {
                MapColor.WHITE
            }
        }
        set(value) = prefs.edit { putString(KEY_ROUTE_COLOR, value.name) }

    var markerColor: MapColor
        get() {
            val colorName = prefs.getString(KEY_MARKER_COLOR, MapColor.WHITE.name) ?: MapColor.WHITE.name
            return try {
                MapColor.valueOf(colorName)
            } catch (e: IllegalArgumentException) {
                MapColor.WHITE
            }
        }
        set(value) = prefs.edit { putString(KEY_MARKER_COLOR, value.name) }

    // HERE API - Monatliches Limit (Free Tier: 250.000/Monat)
    var hereMonthlyLimit: Int
        get() = prefs.getInt(KEY_HERE_MONTHLY_LIMIT, DEFAULT_HERE_MONTHLY_LIMIT)
        set(value) = prefs.edit { putInt(KEY_HERE_MONTHLY_LIMIT, value) }

    fun isHereConfigured(): Boolean = hereApiKey.isNotBlank()

    // ========== HERE API Usage Tracking (Monatlich) ==========

    private fun getCurrentMonthKey(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM", Locale.US)
        return dateFormat.format(Date())
    }

    private fun getCurrentMonthName(): String {
        val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.GERMANY)
        return dateFormat.format(Date())
    }

    fun getHereMonthlyUsage(): Int {
        val monthKey = getCurrentMonthKey()
        val storedMonth = prefs.getString(KEY_HERE_USAGE_MONTH, "") ?: ""

        // Reset counter if it's a new month
        if (storedMonth != monthKey) {
            prefs.edit {
                putString(KEY_HERE_USAGE_MONTH, monthKey)
                putInt(KEY_HERE_USAGE_COUNT, 0)
            }
            return 0
        }

        return prefs.getInt(KEY_HERE_USAGE_COUNT, 0)
    }

    fun incrementHereUsage(): Int {
        val monthKey = getCurrentMonthKey()
        val storedMonth = prefs.getString(KEY_HERE_USAGE_MONTH, "") ?: ""

        val currentCount = if (storedMonth != monthKey) {
            // New month, reset counter
            0
        } else {
            prefs.getInt(KEY_HERE_USAGE_COUNT, 0)
        }

        val newCount = currentCount + 1
        prefs.edit {
            putString(KEY_HERE_USAGE_MONTH, monthKey)
            putInt(KEY_HERE_USAGE_COUNT, newCount)
        }

        return newCount
    }

    fun canMakeHereRequest(): Boolean {
        return getHereMonthlyUsage() < hereMonthlyLimit
    }

    fun getHereUsageInfo(): ApiUsageInfo {
        val used = getHereMonthlyUsage()
        val limit = hereMonthlyLimit
        val status = when {
            used >= limit -> UsageStatus.BLOCKED
            used >= (limit * 0.8).toInt() -> UsageStatus.WARNING
            else -> UsageStatus.OK
        }

        return ApiUsageInfo(
            apiName = "HERE Routing",
            used = used,
            limit = limit,
            periodType = PeriodType.MONTHLY,
            periodStart = getCurrentMonthName(),
            status = status
        )
    }

    // ========== Legacy methods for backward compatibility ==========

    // Diese werden von HereConfig aufgerufen
    @Deprecated("Use getHereMonthlyUsage() instead")
    fun getTodayUsage(): Int = getHereMonthlyUsage()

    @Deprecated("Use incrementHereUsage() instead")
    fun incrementUsage(): Int = incrementHereUsage()

    @Deprecated("Use canMakeHereRequest() instead")
    fun canMakeRequest(): Boolean = canMakeHereRequest()

    @Deprecated("Use hereMonthlyLimit instead")
    var dailyLimit: Int
        get() = hereMonthlyLimit
        set(value) { hereMonthlyLimit = value }

    @Deprecated("Not used anymore")
    var warningThreshold: Int
        get() = (hereMonthlyLimit * 0.8).toInt()
        set(_) { /* ignored */ }

    fun getRemainingRequests(): Int {
        return (hereMonthlyLimit - getHereMonthlyUsage()).coerceAtLeast(0)
    }

    fun getUsageStatus(): UsageStatus {
        return getHereUsageInfo().status
    }

    // ========== Alle API Usage Infos ==========

    fun getAllApiUsageInfos(): List<ApiUsageInfo> {
        val list = mutableListOf<ApiUsageInfo>()

        // HERE nur anzeigen wenn konfiguriert
        if (isHereConfigured()) {
            list.add(getHereUsageInfo())
        }

        // Weitere APIs könnten hier hinzugefügt werden
        // z.B. wenn wir Photon/Nominatim tracken wollen

        return list
    }

    companion object {
        private const val PREFS_NAME = "dalang_settings"
        private const val KEY_HERE_API_KEY = "here_api_key"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_MAP_STYLE = "map_style"
        private const val KEY_ROUTE_COLOR = "route_color"
        private const val KEY_MARKER_COLOR = "marker_color"

        // HERE Usage Tracking (monatlich)
        private const val KEY_HERE_MONTHLY_LIMIT = "here_monthly_limit"
        private const val KEY_HERE_USAGE_MONTH = "here_usage_month"
        private const val KEY_HERE_USAGE_COUNT = "here_usage_count"

        // HERE Free Tier: 250.000 Transaktionen/Monat
        const val DEFAULT_HERE_MONTHLY_LIMIT = 250_000
    }
}
