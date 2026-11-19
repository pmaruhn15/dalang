package de.dalang.nav.util

import android.content.Context
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sprach-Bugreport während der Fahrt: nimmt eine Sprachnotiz auf und legt daneben
 * einen Snapshot des App-Zustands + GPX + Log ab. Nach dem Stopp wird alles als
 * ein Zip in die Upload-Warteschlange gelegt (siehe DebugUpload); Fallback:
 * `adb pull /sdcard/Android/data/de.dalang.nav/files/debug_reports/outbox`.
 * Transkription passiert am Rechner (kein Speech-Recognizer auf LineageOS).
 */
object DebugReporter {

    private var recorder: MediaRecorder? = null
    var isRecording by mutableStateOf(false)  // Compose-beobachtbar (auch Auto-Stop)
        private set

    fun start(context: Context, snapshot: String): Boolean {
        if (recorder != null) return true
        appCtx = context.applicationContext
        return try {
            val dir = File(context.getExternalFilesDir(null), "debug_reports").apply { mkdirs() }
            val base = "report_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())

            // Snapshot/GPX/Log im Hintergrund schreiben: vorher blockierte das den UI-Thread
            // ~1-2 s, der Fahrer tippte nochmal und stoppte die Aufnahme sofort (Mini-Dateien).
            snapshotThread = Thread {
                try {
                    File(dir, "$base.txt").writeText(
                        snapshot + "\n\n" + GpsTrackLogger.getDebugSummary() +
                            "\n\n=== Log (Ende) ===\n" + DebugUpload.tail(CrashLogger.getLastCrashLog())
                    )
                    GpsTrackLogger.exportToGpx(context)?.let { it.copyTo(File(dir, "$base.gpx"), overwrite = true) }
                } catch (e: Exception) {
                    CrashLogger.logError("DebugReporter", "snapshot failed", e)
                }
            }.also { it.start() }

            current = File(dir, "$base.txt") to File(dir, "$base.m4a")
            startedAt = System.currentTimeMillis()
            @Suppress("DEPRECATION")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setMaxDuration(120_000)  // vergessener Stop → nach 2 min Schluss
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop(force = true)
                }
                setOutputFile(File(dir, "$base.m4a").absolutePath)
                prepare()
                start()
            }
            isRecording = true
            CrashLogger.log("DebugReporter: recording $base")
            true
        } catch (e: Exception) {
            CrashLogger.logError("DebugReporter", "start failed", e)
            recorder?.release()
            recorder = null
            false
        }
    }

    private var startedAt = 0L
    private var snapshotThread: Thread? = null
    private var current: Pair<File, File>? = null
    private lateinit var appCtx: Context

    /** force=true nur für Auto-Stop; Tipp innerhalb 1,5 s nach Start = Doppeltipp, ignorieren. */
    fun stop(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - startedAt < 1500) return
        val r = recorder ?: return
        recorder = null
        isRecording = false
        try { r.stop() } catch (e: Exception) { CrashLogger.logError("DebugReporter", "stop failed", e) }
        r.release()
        CrashLogger.log("DebugReporter: recording stopped")
        val (txt, m4a) = current ?: return
        val st = snapshotThread
        Thread {  // auf Snapshot-Thread warten, dann zippen + hochladen (nie auf dem UI-Thread)
            st?.join()
            DebugUpload.enqueue(appCtx, txt, m4a, File(txt.path.removeSuffix(".txt") + ".gpx"))
        }.start()
    }
}
