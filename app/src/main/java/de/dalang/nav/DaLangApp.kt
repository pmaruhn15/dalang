package de.dalang.nav

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import de.dalang.nav.config.HereConfig
import de.dalang.nav.tts.PiperTts
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class DaLangApp : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Piper TTS Singleton - wird beim App-Start initialisiert
    private var _piperTts: PiperTts? = null
    val piperTts: PiperTts? get() = _piperTts

    // Loading State für UI (kleiner Indikator während TTS lädt)
    private val _isInitializing = MutableStateFlow(true)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    var isPiperReady = false
        private set

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

        // Piper TTS im Hintergrund initialisieren
        appScope.launch {
            initializePiperTts()
        }
    }

    private suspend fun initializePiperTts() {
        try {
            CrashLogger.log("DaLangApp: Starting Piper TTS initialization...")

            // Kurz warten damit App vollständig gestartet ist
            delay(1000)

            CrashLogger.log("DaLangApp: Creating PiperTts instance...")
            val piper = PiperTts(this@DaLangApp)

            CrashLogger.log("DaLangApp: Calling initialize()...")
            val success = piper.initialize()

            if (success) {
                _piperTts = piper
                isPiperReady = true
                CrashLogger.log("DaLangApp: Piper TTS ready")
            } else {
                CrashLogger.log("DaLangApp: Piper TTS init returned false, will use Android TTS")
                isPiperReady = false
            }
        } catch (e: Exception) {
            CrashLogger.logError("DaLangApp", "Piper TTS init failed", e)
            isPiperReady = false
        } finally {
            _isInitializing.value = false
            CrashLogger.log("DaLangApp: Piper TTS initialization finished (ready=$isPiperReady)")
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
