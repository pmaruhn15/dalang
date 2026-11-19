package de.dalang.nav.util

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
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

            (context as? Activity)?.let { captureScreen(it, File(dir, "$base.jpg")) }
            current = File(dir, "$base.txt") to File(dir, "$base.m4a")
            startedAt = System.currentTimeMillis()
            @Suppress("DEPRECATION")
            // Erst zuweisen, dann konfigurieren: wirft prepare()/start(), gibt der catch-Block frei
            recorder = MediaRecorder()
            recorder!!.apply {
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

    /**
     * Screenshot des eigenen Fensters im Moment des Tippens (für visuelle Bugs). PixelCopy statt
     * View.drawToBitmap, weil die MapLibre-Karte eine GL-Surface ist und sonst schwarz bliebe.
     * Braucht keine Berechtigung (nur eigenes Fenster).
     */
    private fun captureScreen(activity: Activity, out: File) {
        try {
            val view = activity.window.decorView
            if (view.width == 0 || view.height == 0) return
            val main = Handler(Looper.getMainLooper())
            val win = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            // Die Karte ist eine eigene GL-SurfaceView (im Fenster-Puffer nur ein Loch) ->
            // separat kopieren und unter das Fenster legen.
            val surface = findSurfaceView(view)
            val loc = IntArray(2).also { surface?.getLocationInWindow(it) }
            val map = surface?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) }

            fun compose() = Thread {
                try {
                    val outBmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    Canvas(outBmp).apply {
                        drawColor(Color.BLACK)
                        map?.let { drawBitmap(it, loc[0].toFloat(), loc[1].toFloat(), null) }
                        drawBitmap(win, 0f, 0f, null)
                    }
                    out.outputStream().use { outBmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    outBmp.recycle()
                } catch (e: Exception) {
                    CrashLogger.logError("DebugReporter", "screenshot write failed", e)
                } finally { win.recycle(); map?.recycle() }
            }.start()

            PixelCopy.request(activity.window, win, { r ->
                if (r != PixelCopy.SUCCESS) { CrashLogger.log("DebugReporter: window copy failed ($r)"); return@request }
                if (surface == null || map == null) { compose(); return@request }
                PixelCopy.request(surface, map, { r2 ->
                    if (r2 != PixelCopy.SUCCESS) CrashLogger.log("DebugReporter: map copy failed ($r2)")
                    compose()
                }, main)
            }, main)
        } catch (e: Exception) {
            CrashLogger.logError("DebugReporter", "screenshot failed", e)
        }
    }

    private fun findSurfaceView(v: View): SurfaceView? = when (v) {
        is SurfaceView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findSurfaceView(v.getChildAt(it)) }
        else -> null
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
