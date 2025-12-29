package de.dalang.nav

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class DaLangApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NAVIGATION_CHANNEL_ID = "dalang_navigation"
    }
}
