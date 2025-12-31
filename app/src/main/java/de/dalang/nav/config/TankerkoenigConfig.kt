package de.dalang.nav.config

import android.content.Context
import de.dalang.nav.settings.SettingsRepository

/**
 * Tankerkönig API Konfiguration
 *
 * Der API Key wird in den App-Einstellungen gespeichert.
 * Registriere dich auf https://creativecommons.tankerkoenig.de um einen Key zu erhalten.
 *
 * Der Service ist kostenlos, erfordert aber einen eigenen API Key.
 */
object TankerkoenigConfig {
    private var settingsRepository: SettingsRepository? = null

    // Tankerkönig API Base URL
    const val API_BASE_URL = "https://creativecommons.tankerkoenig.de/json/list.php"

    fun init(context: Context) {
        if (settingsRepository == null) {
            settingsRepository = SettingsRepository(context.applicationContext)
        }
    }

    fun getApiKey(): String = settingsRepository?.tankerkoenigApiKey ?: ""

    fun setApiKey(key: String) {
        settingsRepository?.tankerkoenigApiKey = key
    }

    fun isConfigured(): Boolean = settingsRepository?.isTankerkoenigConfigured() == true
}
