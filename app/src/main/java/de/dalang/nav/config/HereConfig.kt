package de.dalang.nav.config

import android.content.Context
import de.dalang.nav.settings.SettingsRepository

/**
 * HERE API Konfiguration
 *
 * Der API Key wird in den App-Einstellungen gespeichert.
 * Registriere dich auf https://developer.here.com um einen Key zu erhalten.
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

    // Usage Tracking
    fun canMakeRequest(): Boolean = settingsRepository?.canMakeRequest() ?: false

    fun incrementUsage(): Int = settingsRepository?.incrementUsage() ?: 0

    fun getTodayUsage(): Int = settingsRepository?.getTodayUsage() ?: 0

    fun getDailyLimit(): Int = settingsRepository?.dailyLimit ?: SettingsRepository.DEFAULT_DAILY_LIMIT

    fun setDailyLimit(limit: Int) {
        settingsRepository?.dailyLimit = limit
    }

    fun getWarningThreshold(): Int = settingsRepository?.warningThreshold ?: SettingsRepository.DEFAULT_WARNING_THRESHOLD

    fun setWarningThreshold(threshold: Int) {
        settingsRepository?.warningThreshold = threshold
    }

    fun getRemainingRequests(): Int = settingsRepository?.getRemainingRequests() ?: 0

    fun getUsageStatus(): SettingsRepository.UsageStatus =
        settingsRepository?.getUsageStatus() ?: SettingsRepository.UsageStatus.OK
}
