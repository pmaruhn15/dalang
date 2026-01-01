package de.dalang.nav.config

import android.content.Context
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.settings.UsageStatus

/**
 * HERE API Konfiguration
 *
 * Der API Key wird in den App-Einstellungen gespeichert.
 * Registriere dich auf https://developer.here.com um einen Key zu erhalten.
 *
 * Free Tier: 250.000 Transaktionen/Monat
 */
object HereConfig {
    private var settingsRepository: SettingsRepository? = null

    // HERE Routing API v8 Base URL
    const val ROUTING_BASE_URL = "https://router.hereapi.com/v8"

    fun init(context: Context) {
        if (settingsRepository == null) {
            settingsRepository = SettingsRepository(context.applicationContext)
        }
    }

    fun getApiKey(): String = settingsRepository?.hereApiKey ?: ""

    fun setApiKey(key: String) {
        settingsRepository?.hereApiKey = key
    }

    fun isConfigured(): Boolean = settingsRepository?.isHereConfigured() == true

    // Usage Tracking (monatlich) - HERE Routing API
    fun canMakeRequest(): Boolean = settingsRepository?.canMakeHereRequest() ?: false

    fun incrementUsage(): Int = settingsRepository?.incrementHereUsage() ?: 0

    fun getMonthlyUsage(): Int = settingsRepository?.getHereMonthlyUsage() ?: 0

    fun getMonthlyLimit(): Int = settingsRepository?.hereMonthlyLimit ?: SettingsRepository.DEFAULT_HERE_MONTHLY_LIMIT

    fun setMonthlyLimit(limit: Int) {
        settingsRepository?.hereMonthlyLimit = limit
    }

    fun getRemainingRequests(): Int = settingsRepository?.getRemainingRequests() ?: 0

    fun getUsageStatus(): UsageStatus =
        settingsRepository?.getUsageStatus() ?: UsageStatus.OK

    // Fuel Prices API Usage Tracking (separates Limit: 100/Monat)
    fun canMakeFuelPricesRequest(): Boolean = settingsRepository?.canMakeFuelPricesRequest() ?: false

    fun incrementFuelPricesUsage(): Int = settingsRepository?.incrementFuelPricesUsage() ?: 0

    fun getFuelPricesMonthlyUsage(): Int = settingsRepository?.getFuelPricesMonthlyUsage() ?: 0

    fun getFuelPricesMonthlyLimit(): Int = settingsRepository?.fuelPricesMonthlyLimit ?: SettingsRepository.DEFAULT_FUEL_PRICES_MONTHLY_LIMIT

    // Legacy methods for backward compatibility
    @Deprecated("Use getMonthlyUsage() instead", ReplaceWith("getMonthlyUsage()"))
    fun getTodayUsage(): Int = getMonthlyUsage()

    @Deprecated("Use getMonthlyLimit() instead", ReplaceWith("getMonthlyLimit()"))
    fun getDailyLimit(): Int = getMonthlyLimit()

    @Deprecated("Use setMonthlyLimit() instead", ReplaceWith("setMonthlyLimit(limit)"))
    fun setDailyLimit(limit: Int) = setMonthlyLimit(limit)

    @Deprecated("Warning threshold is now automatic at 80%")
    fun getWarningThreshold(): Int = (getMonthlyLimit() * 0.8).toInt()

    @Deprecated("Warning threshold is now automatic at 80%")
    fun setWarningThreshold(threshold: Int) { /* ignored */ }
}
