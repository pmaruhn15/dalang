package de.dalang.nav.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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

    fun isHereConfigured(): Boolean = hereApiKey.isNotBlank()

    companion object {
        private const val PREFS_NAME = "dalang_settings"
        private const val KEY_HERE_API_KEY = "here_api_key"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
    }
}
