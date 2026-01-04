package de.dalang.nav.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kraftstofftyp für die Preisanzeige
 */
enum class FuelType(val displayName: String) {
    DIESEL("Diesel"),
    SUPER("Super")
}

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

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var hereApiKey: String
        get() = prefs.getString(KEY_HERE_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_HERE_API_KEY, value) }

    // Toggle ob HERE API genutzt werden soll (unabhängig vom Key)
    var hereApiEnabled: Boolean
        get() = prefs.getBoolean(KEY_HERE_API_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_HERE_API_ENABLED, value) }

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_VOICE_ENABLED, value) }

    // Kraftstofftyp für Preisanzeige (Diesel oder Super)
    var preferredFuelType: FuelType
        get() {
            val stored = prefs.getString(KEY_FUEL_TYPE, FuelType.DIESEL.name) ?: FuelType.DIESEL.name
            return try {
                FuelType.valueOf(stored)
            } catch (e: Exception) {
                FuelType.DIESEL
            }
        }
        set(value) = prefs.edit { putString(KEY_FUEL_TYPE, value.name) }

    // Fahrzeug-Reichweite in km (für Tankstellen-Filterung)
    var vehicleRangeKm: Int
        get() = prefs.getInt(KEY_VEHICLE_RANGE_KM, 0) // 0 = nicht gesetzt/unbegrenzt
        set(value) = prefs.edit { putInt(KEY_VEHICLE_RANGE_KM, value) }

    // MapTiler API Key für Karten-Tiles
    var mapTilerApiKey: String
        get() = prefs.getString(KEY_MAPTILER_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_MAPTILER_API_KEY, value) }

    fun hasMapTilerApiKey(): Boolean = mapTilerApiKey.isNotBlank()

    // HERE API - Monatliches Limit (Free Tier: 250.000/Monat)
    var hereMonthlyLimit: Int
        get() = prefs.getInt(KEY_HERE_MONTHLY_LIMIT, DEFAULT_HERE_MONTHLY_LIMIT)
        set(value) = prefs.edit { putInt(KEY_HERE_MONTHLY_LIMIT, value) }

    // HERE ist "konfiguriert" wenn Key vorhanden UND aktiviert
    fun isHereConfigured(): Boolean = hereApiKey.isNotBlank() && hereApiEnabled

    // Nur prüfen ob Key vorhanden ist (für UI Anzeige)
    fun hasHereApiKey(): Boolean = hereApiKey.isNotBlank()

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

    // ========== HERE Fuel Prices API Usage Tracking (Monatlich, separates Limit) ==========

    // Fuel Prices API - Monatliches Limit (Free Tier: 100/Monat)
    var fuelPricesMonthlyLimit: Int
        get() = prefs.getInt(KEY_FUEL_PRICES_MONTHLY_LIMIT, DEFAULT_FUEL_PRICES_MONTHLY_LIMIT)
        set(value) = prefs.edit { putInt(KEY_FUEL_PRICES_MONTHLY_LIMIT, value) }

    fun getFuelPricesMonthlyUsage(): Int {
        val monthKey = getCurrentMonthKey()
        val storedMonth = prefs.getString(KEY_FUEL_PRICES_USAGE_MONTH, "") ?: ""

        // Reset counter if it's a new month
        if (storedMonth != monthKey) {
            prefs.edit {
                putString(KEY_FUEL_PRICES_USAGE_MONTH, monthKey)
                putInt(KEY_FUEL_PRICES_USAGE_COUNT, 0)
            }
            return 0
        }

        return prefs.getInt(KEY_FUEL_PRICES_USAGE_COUNT, 0)
    }

    fun incrementFuelPricesUsage(): Int {
        val monthKey = getCurrentMonthKey()
        val storedMonth = prefs.getString(KEY_FUEL_PRICES_USAGE_MONTH, "") ?: ""

        val currentCount = if (storedMonth != monthKey) {
            // New month, reset counter
            0
        } else {
            prefs.getInt(KEY_FUEL_PRICES_USAGE_COUNT, 0)
        }

        val newCount = currentCount + 1
        prefs.edit {
            putString(KEY_FUEL_PRICES_USAGE_MONTH, monthKey)
            putInt(KEY_FUEL_PRICES_USAGE_COUNT, newCount)
        }

        return newCount
    }

    fun canMakeFuelPricesRequest(): Boolean {
        return getFuelPricesMonthlyUsage() < fuelPricesMonthlyLimit
    }

    fun getFuelPricesUsageInfo(): ApiUsageInfo {
        val used = getFuelPricesMonthlyUsage()
        val limit = fuelPricesMonthlyLimit
        val status = when {
            used >= limit -> UsageStatus.BLOCKED
            used >= (limit * 0.8).toInt() -> UsageStatus.WARNING
            else -> UsageStatus.OK
        }

        return ApiUsageInfo(
            apiName = "HERE Fuel Prices",
            used = used,
            limit = limit,
            periodType = PeriodType.MONTHLY,
            periodStart = getCurrentMonthName(),
            status = status
        )
    }

    // ========== Legacy methods for backward compatibility ==========

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
            list.add(getFuelPricesUsageInfo())
        }

        return list
    }

    // ========== POI Layer Visibility ==========

    var showPoiRestaurants: Boolean
        get() = prefs.getBoolean(KEY_POI_RESTAURANTS, false)
        set(value) = prefs.edit { putBoolean(KEY_POI_RESTAURANTS, value) }

    var showPoiCafes: Boolean
        get() = prefs.getBoolean(KEY_POI_CAFES, false)
        set(value) = prefs.edit { putBoolean(KEY_POI_CAFES, value) }

    var showPoiSupermarkets: Boolean
        get() = prefs.getBoolean(KEY_POI_SUPERMARKETS, false)
        set(value) = prefs.edit { putBoolean(KEY_POI_SUPERMARKETS, value) }

    var showPoiSwimming: Boolean
        get() = prefs.getBoolean(KEY_POI_SWIMMING, false)
        set(value) = prefs.edit { putBoolean(KEY_POI_SWIMMING, value) }

    var showPoiParking: Boolean
        get() = prefs.getBoolean(KEY_POI_PARKING, false)
        set(value) = prefs.edit { putBoolean(KEY_POI_PARKING, value) }

    var showPoiHotels: Boolean
        get() = prefs.getBoolean(KEY_POI_HOTELS, false)
        set(value) = prefs.edit { putBoolean(KEY_POI_HOTELS, value) }

    /**
     * POI-Einstellungen als Map für MapView
     */
    fun getPoiVisibility(): Map<String, Boolean> = mapOf(
        "restaurant" to showPoiRestaurants,
        "cafe" to showPoiCafes,
        "supermarket" to showPoiSupermarkets,
        "swimming_pool" to showPoiSwimming,
        "parking" to showPoiParking,
        "hotel" to showPoiHotels
    )

    companion object {
        private const val PREFS_NAME = "dalang_settings"
        private const val KEY_HERE_API_KEY = "here_api_key"
        private const val KEY_HERE_API_ENABLED = "here_api_enabled"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_FUEL_TYPE = "preferred_fuel_type"
        private const val KEY_VEHICLE_RANGE_KM = "vehicle_range_km"
        private const val KEY_MAPTILER_API_KEY = "maptiler_api_key"

        // HERE Usage Tracking (monatlich)
        private const val KEY_HERE_MONTHLY_LIMIT = "here_monthly_limit"
        private const val KEY_HERE_USAGE_MONTH = "here_usage_month"
        private const val KEY_HERE_USAGE_COUNT = "here_usage_count"

        // HERE Fuel Prices Usage Tracking (monatlich, separates Limit)
        private const val KEY_FUEL_PRICES_MONTHLY_LIMIT = "fuel_prices_monthly_limit"
        private const val KEY_FUEL_PRICES_USAGE_MONTH = "fuel_prices_usage_month"
        private const val KEY_FUEL_PRICES_USAGE_COUNT = "fuel_prices_usage_count"

        // POI Layer Visibility
        private const val KEY_POI_RESTAURANTS = "poi_restaurants"
        private const val KEY_POI_CAFES = "poi_cafes"
        private const val KEY_POI_SUPERMARKETS = "poi_supermarkets"
        private const val KEY_POI_SWIMMING = "poi_swimming"
        private const val KEY_POI_PARKING = "poi_parking"
        private const val KEY_POI_HOTELS = "poi_hotels"

        // HERE Free Tier: 250.000 Transaktionen/Monat
        const val DEFAULT_HERE_MONTHLY_LIMIT = 250_000

        // HERE Fuel Prices Free Tier: 100 Transaktionen/Monat
        const val DEFAULT_FUEL_PRICES_MONTHLY_LIMIT = 100
    }
}
