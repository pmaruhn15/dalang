package de.dalang.nav

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import de.dalang.nav.util.CrashLogger

class DaLangApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // CrashLogger zuerst initialisieren
        try {
            CrashLogger.init(this)
        } catch (e: Exception) {
            // Ignorieren - wir wollen nicht wegen dem Logger crashen
        }

        try {
            createNotificationChannel()
        } catch (e: Exception) {
            CrashLogger.logError("DaLangApp", "createNotificationChannel failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NAVIGATION_CHANNEL_ID,
                "Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Navigationsanweisungen"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(channel)
            } else {
                CrashLogger.logError("DaLangApp", "NotificationManager is null")
            }
        }
    }

    companion object {
        const val NAVIGATION_CHANNEL_ID = "dalang_navigation"
    }
}
