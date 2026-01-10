package de.dalang.nav.navigation

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import de.dalang.nav.MainActivity
import de.dalang.nav.R
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.tts.PiperTts
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.*
import java.util.Locale

class NavigationService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()

    // Piper TTS - Lazy Init beim ersten Sprachgebrauch
    private var piperTts: PiperTts? = null
    private var isPiperReady = false
    private var piperInitStarted = false

    // Android TTS (wird sofort verwendet, bis Piper bereit)
    private var androidTts: TextToSpeech? = null
    private var isAndroidTtsReady = false

    var voiceEnabled = true

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.log("NavigationService onCreate")

        // Android TTS sofort initialisieren (schnell, blockiert nicht)
        try {
            androidTts = TextToSpeech(this, this)
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "Android TTS init failed", e)
        }

        // Piper TTS wird NICHT hier initialisiert - erst bei erstem Sprachgebrauch
        CrashLogger.log("NavigationService: Piper TTS will be initialized lazily on first speak")
    }

    /**
     * Startet Piper TTS Initialisierung im Hintergrund.
     * Wird beim ersten speak() aufgerufen.
     */
    private fun startPiperInitialization() {
        if (piperInitStarted) return
        piperInitStarted = true

        // Prüfe ob Piper TTS in Einstellungen aktiviert ist
        val settings = SettingsRepository(this)
        if (!settings.piperTtsEnabled) {
            CrashLogger.log("NavigationService: Piper TTS disabled in settings, using Android TTS only")
            return
        }

        serviceScope.launch {
            try {
                CrashLogger.log("NavigationService: Starting lazy Piper TTS init...")
                val piper = PiperTts(this@NavigationService)
                val success = piper.initialize()

                if (success) {
                    piperTts = piper
                    isPiperReady = true
                    CrashLogger.log("NavigationService: Piper TTS ready (lazy init)")
                } else {
                    CrashLogger.log("NavigationService: Piper TTS init failed, staying with Android TTS")
                }
            } catch (e: Exception) {
                CrashLogger.logError("NavigationService", "Piper TTS lazy init failed", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        CrashLogger.log("NavigationService onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLogger.log("NavigationService onStartCommand: ${intent?.action}")
        try {
            when (intent?.action) {
                ACTION_START -> startForegroundNavigation()
                ACTION_STOP -> stopSelf()
                null -> {
                    // Service wurde vom System neu gestartet - stoppen um Crash-Loop zu vermeiden
                    CrashLogger.log("NavigationService: Restarted by system with null intent, stopping")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "onStartCommand failed", e)
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        CrashLogger.log("NavigationService Android TTS onInit: status=$status")
        try {
            if (status == TextToSpeech.SUCCESS) {
                val result = androidTts?.setLanguage(Locale.GERMAN)
                isAndroidTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                androidTts?.setSpeechRate(1.0f)
                androidTts?.setPitch(1.0f)

                CrashLogger.log("Android TTS ready: $isAndroidTtsReady")
            } else {
                CrashLogger.logError("NavigationService", "Android TTS init failed with status: $status")
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "Android TTS configuration failed", e)
        }
    }

    fun speak(text: String) {
        if (!voiceEnabled) return

        // Piper TTS lazy init beim ersten Sprachgebrauch starten
        if (!piperInitStarted) {
            startPiperInitialization()
        }

        try {
            if (isPiperReady && piperTts != null) {
                // Piper TTS verwenden (hochwertige Stimme)
                piperTts?.speak(text)
            } else if (isAndroidTtsReady) {
                // Android TTS verwenden (bis Piper bereit)
                androidTts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "speak failed", e)
        }
    }

    fun speakNow(text: String) {
        if (!voiceEnabled) return

        // Piper TTS lazy init beim ersten Sprachgebrauch starten
        if (!piperInitStarted) {
            startPiperInitialization()
        }

        try {
            if (isPiperReady && piperTts != null) {
                piperTts?.speakNow(text)
            } else if (isAndroidTtsReady) {
                androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "speakNow failed", e)
        }
    }

    private fun startForegroundNavigation() {
        CrashLogger.log("startForegroundNavigation")
        try {
            val notification = createNotification("Navigation aktiv", "")
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "startForeground failed", e)
        }
    }

    fun updateNotification(instruction: String, distance: String) {
        try {
            val notification = createNotification(instruction, distance)
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification)
            } else {
                CrashLogger.logError("NavigationService", "NotificationManager is null")
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "updateNotification failed", e)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NAVIGATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    override fun onDestroy() {
        CrashLogger.log("NavigationService onDestroy")

        // Piper TTS beenden
        try {
            piperTts?.shutdown()
            piperTts = null
            isPiperReady = false
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "Piper TTS shutdown failed", e)
        }

        // Android TTS beenden
        try {
            androidTts?.stop()
            androidTts?.shutdown()
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "Android TTS shutdown failed", e)
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "de.dalang.nav.START_NAVIGATION"
        const val ACTION_STOP = "de.dalang.nav.STOP_NAVIGATION"
        const val NOTIFICATION_ID = 1
        const val NAVIGATION_CHANNEL_ID = "dalang_navigation"
    }
}
