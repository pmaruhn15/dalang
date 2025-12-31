package de.dalang.nav

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import de.dalang.nav.config.HereConfig
import de.dalang.nav.util.CrashLogger
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class DaLangApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Globalen Crash-Handler ZUERST setzen
        setupCrashHandler()

        // CrashLogger initialisieren
        try {
            CrashLogger.init(this)
        } catch (e: Exception) {
            // Ignorieren
        }

        // HERE Config initialisieren
        try {
            HereConfig.init(this)
            CrashLogger.log("DaLangApp: HereConfig initialized")
        } catch (e: Exception) {
            CrashLogger.logError("DaLangApp", "HereConfig init failed", e)
        }

        // MapLibre MUSS vor jeder View initialisiert werden
        try {
            CrashLogger.log("DaLangApp: Initializing MapLibre")
            MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
            CrashLogger.log("DaLangApp: MapLibre initialized successfully")
        } catch (e: Exception) {
            CrashLogger.logError("DaLangApp", "MapLibre init failed", e)
        }

        try {
            createNotificationChannel()
        } catch (e: Exception) {
            CrashLogger.logError("DaLangApp", "createNotificationChannel failed", e)
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Fehler loggen
                CrashLogger.logCrash("FATAL", throwable)

                // CrashActivity starten um den Fehler anzuzeigen
                val intent = Intent(this, CrashActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    putExtra("error_message", throwable.message ?: "Unbekannter Fehler")
                    putExtra("error_class", throwable.javaClass.simpleName)
                    putExtra("stack_trace", throwable.stackTraceToString())
                }
                startActivity(intent)

                // Kurz warten damit Activity starten kann
                Thread.sleep(500)

            } catch (e: Exception) {
                // Falls alles fehlschlägt, default handler nutzen
            }

            // App beenden (aber CrashActivity bleibt offen)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
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
