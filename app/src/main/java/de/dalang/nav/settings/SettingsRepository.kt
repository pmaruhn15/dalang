package de.dalang.nav.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    var dailyLimit: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT)
        set(value) = prefs.edit { putInt(KEY_DAILY_LIMIT, value) }

    var warningThreshold: Int
        get() = prefs.getInt(KEY_WARNING_THRESHOLD, DEFAULT_WARNING_THRESHOLD)
        set(value) = prefs.edit { putInt(KEY_WARNING_THRESHOLD, value) }

    fun isHereConfigured(): Boolean = hereApiKey.isNotBlank()

    // Usage Tracking
    private fun getTodayKey(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return dateFormat.format(Date())
    }

    fun getTodayUsage(): Int {
        val todayKey = getTodayKey()
        val storedDate = prefs.getString(KEY_USAGE_DATE, "") ?: ""

        // Reset counter if it's a new day
        if (storedDate != todayKey) {
            prefs.edit {
                putString(KEY_USAGE_DATE, todayKey)
                putInt(KEY_USAGE_COUNT, 0)
            }
            return 0
        }

        return prefs.getInt(KEY_USAGE_COUNT, 0)
    }

    fun incrementUsage(): Int {
        val todayKey = getTodayKey()
        val storedDate = prefs.getString(KEY_USAGE_DATE, "") ?: ""

        val currentCount = if (storedDate != todayKey) {
            // New day, reset counter
            0
        } else {
            prefs.getInt(KEY_USAGE_COUNT, 0)
        }

        val newCount = currentCount + 1
        prefs.edit {
            putString(KEY_USAGE_DATE, todayKey)
            putInt(KEY_USAGE_COUNT, newCount)
        }

        return newCount
    }

    fun canMakeRequest(): Boolean {
        return getTodayUsage() < dailyLimit
    }

    fun shouldShowWarning(): Boolean {
        return getTodayUsage() >= warningThreshold
    }

    fun getRemainingRequests(): Int {
        return (dailyLimit - getTodayUsage()).coerceAtLeast(0)
    }

    fun getUsageStatus(): UsageStatus {
        val usage = getTodayUsage()
        val limit = dailyLimit
        val warning = warningThreshold

        return when {
            usage >= limit -> UsageStatus.BLOCKED
            usage >= warning -> UsageStatus.WARNING
            else -> UsageStatus.OK
        }
    }

    enum class UsageStatus {
        OK,
        WARNING,
        BLOCKED
    }

    companion object {
        private const val PREFS_NAME = "dalang_settings"
        private const val KEY_HERE_API_KEY = "here_api_key"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_WARNING_THRESHOLD = "warning_threshold"
        private const val KEY_USAGE_DATE = "usage_date"
        private const val KEY_USAGE_COUNT = "usage_count"

        const val DEFAULT_DAILY_LIMIT = 100
        const val DEFAULT_WARNING_THRESHOLD = 80
    }
}
