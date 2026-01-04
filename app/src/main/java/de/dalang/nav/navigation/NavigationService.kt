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
import de.dalang.nav.DaLangApp
import de.dalang.nav.MainActivity
import de.dalang.nav.R
import de.dalang.nav.tts.DownloadState
import de.dalang.nav.tts.PiperTts
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class NavigationService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()

    // Piper TTS (primär - hochwertige Stimme)
    private var piperTts: PiperTts? = null
    private var isPiperReady = false

    // Android TTS (Fallback)
    private var androidTts: TextToSpeech? = null
    private var isAndroidTtsReady = false

    var voiceEnabled = true

    // TTS Model Download State (für UI)
    private val _ttsDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val ttsDownloadState: StateFlow<DownloadState> = _ttsDownloadState.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.log("NavigationService onCreate")

        // Android TTS als Fallback initialisieren
        try {
            androidTts = TextToSpeech(this, this)
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "Android TTS init failed", e)
        }

        // Piper TTS asynchron initialisieren
        serviceScope.launch {
            initializePiperTts()
        }
    }

    private suspend fun initializePiperTts() {
        try {
            CrashLogger.log("NavigationService: Initializing Piper TTS...")
            piperTts = PiperTts(this@NavigationService)

            // Prüfe ob Model heruntergeladen werden muss
            if (!piperTts!!.isModelAvailable()) {
                CrashLogger.log("NavigationService: TTS model not available, starting download...")
                downloadTtsModel()
            } else {
                CrashLogger.log("NavigationService: TTS model already available")
                _ttsDownloadState.value = DownloadState.Completed
            }

            // Versuche Piper zu initialisieren
            isPiperReady = piperTts?.initialize() == true

            if (isPiperReady) {
                CrashLogger.log("NavigationService: Piper TTS ready - using Thorsten-high voice")
            } else {
                CrashLogger.log("NavigationService: Piper TTS failed, using Android TTS fallback")
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "Piper TTS init failed", e)
            isPiperReady = false
        }
    }

    /**
     * Lädt das TTS Model herunter
     */
    private suspend fun downloadTtsModel() {
        val piper = piperTts ?: return

        piper.modelDownloader.downloadModel().collect { state ->
            _ttsDownloadState.value = state

            when (state) {
                is DownloadState.Downloading -> {
                    CrashLogger.log("NavigationService: TTS download ${state.progress}%")
                }
                is DownloadState.Completed -> {
                    CrashLogger.log("NavigationService: TTS model download completed")
                }
                is DownloadState.Error -> {
                    CrashLogger.log("NavigationService: TTS download error: ${state.message}")
                }
                else -> {}
            }
        }
    }

    /**
     * Startet manuellen Download des TTS Models
     */
    fun startTtsModelDownload() {
        serviceScope.launch {
            downloadTtsModel()
            // Nach Download erneut initialisieren
            if (piperTts?.isModelAvailable() == true) {
                isPiperReady = piperTts?.initialize() == true
                if (isPiperReady) {
                    CrashLogger.log("NavigationService: Piper TTS now ready after download")
                }
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
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "onStartCommand failed", e)
        }
        return START_STICKY
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

        try {
            if (isPiperReady && piperTts != null) {
                // Piper TTS verwenden (hochwertige Stimme)
                piperTts?.speak(text)
            } else if (isAndroidTtsReady) {
                // Fallback zu Android TTS
                androidTts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
            }
        } catch (e: Exception) {
            CrashLogger.logError("NavigationService", "speak failed", e)
        }
    }

    fun speakNow(text: String) {
        if (!voiceEnabled) return

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

        return NotificationCompat.Builder(this, DaLangApp.NAVIGATION_CHANNEL_ID)
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
    }
}
