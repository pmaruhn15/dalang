package de.dalang.nav.navigation

import android.app.Notification
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
import java.util.Locale

class NavigationService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    var voiceEnabled = true

    inner class LocalBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundNavigation()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.GERMAN)
            isTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

            // Spracheinstellungen optimieren
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
        }
    }

    fun speak(text: String) {
        if (isTtsReady && voiceEnabled) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        }
    }

    fun speakNow(text: String) {
        if (isTtsReady && voiceEnabled) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        }
    }

    private fun startForegroundNavigation() {
        val notification = createNotification("Navigation aktiv", "")
        startForeground(NOTIFICATION_ID, notification)
    }

    fun updateNotification(instruction: String, distance: String) {
        val notification = createNotification(instruction, distance)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
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
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "de.dalang.nav.START_NAVIGATION"
        const val ACTION_STOP = "de.dalang.nav.STOP_NAVIGATION"
        const val NOTIFICATION_ID = 1
    }
}
