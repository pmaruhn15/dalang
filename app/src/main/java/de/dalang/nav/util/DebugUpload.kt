package de.dalang.nav.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import de.dalang.nav.BuildConfig
import de.dalang.nav.settings.SettingsRepository
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Debug-Report-Upload in eine Nextcloud-"Datei-Ablage" (öffentlicher Freigabelink
 * `https://<host>/s/<token>`, nur Upload).
 *
 * Upload-Methode (Nextcloud Developer Manual, "WebDAV basic APIs", Abschnitt public shares):
 * https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html
 *   PUT https://<host>/public.php/dav/files/<token>/<datei>
 *   Basic-Auth user "anonymous", Passwort = Share-Passwort (leer, wenn keins),
 *   Header "X-Requested-With: XMLHttpRequest" (sonst 401).
 * Ältere Server: Legacy-Endpunkt PUT /public.php/webdav/<datei>, Basic-Auth user=<token>
 * (Fallback bei 401/404/405 des neuen Endpunkts).
 *
 * Offline-fest ohne WorkManager: Zips liegen in debug_reports/outbox; flush() läuft bei
 * App-Start, Netz-zurück (NetworkCallback) und alle 10 min, solange der Prozess lebt.
 * Erfolg = Zip löschen. WorkManager wäre nur für Uploads bei toter App nötig
 * (ponytail: bei Bedarf nachrüsten; neue Dependency + Boilerplate für den Fahrt-Alltag unnötig).
 */
object DebugUpload {
    private const val MAX_QUEUE_BYTES = 50L * 1024 * 1024
    private const val MAX_TRIP_BYTES = 300L * 1024 * 1024
    const val TRIP_PREFIX = "trip_"
    private const val LOG_LINES = 2000

    /** Debug-Reports in der Warteschlange. */
    var pending by mutableIntStateOf(0)
        private set
    /** Fahrten (trip_*.zip) in der Warteschlange; gehen nur über unmetered Netz (WLAN) raus. */
    var pendingTrips by mutableIntStateOf(0)
        private set

    private val io = Executors.newSingleThreadExecutor()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private lateinit var appCtx: Context

    // ---------- reine Funktionen (Unit-Tests) ----------

    /** Share-Link -> (Basis-URL, Token) oder null. Unterpfad-Installationen (host/nextcloud) werden unterstützt. */
    fun parseShare(link: String): Pair<String, String>? =
        Regex("""^(https?://[^/]+(?:/.*?)?)/s/([A-Za-z0-9]+)/?(?:[?#].*)?$""")
            .matchEntire(link.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    fun stamp(time: Date): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(time)

    fun fileName(time: Date, tester: String, prefix: String = "dalang_report_"): String =
        prefix + stamp(time) +
            "_" + tester.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "tester" }.take(40) + ".zip"

    fun putRequest(base: String, token: String, name: String, file: File, legacy: Boolean): Request {
        val url = if (legacy) "$base/public.php/webdav/$name" else "$base/public.php/dav/files/$token/$name"
        return Request.Builder().url(url)
            .header("Authorization", Credentials.basic(if (legacy) token else "anonymous", ""))
            .header("X-Requested-With", "XMLHttpRequest")
            .put(file.asRequestBody("application/zip".toMediaType()))
            .build()
    }

    fun writeZip(out: File, files: Map<String, File?>, texts: Map<String, String>) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for ((n, t) in texts) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
            for ((n, f) in files) if (f != null && f.exists()) {
                z.putNextEntry(ZipEntry(n)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    fun tail(log: String?, n: Int = LOG_LINES): String =
        log?.lines()?.takeLast(n)?.joinToString("\n")?.let { CrashLogger.redact(it) } ?: "-"  // auch Alt-Logs maskieren

    // ---------- Android-Teil ----------

    fun uploadUrl(ctx: Context): String =
        SettingsRepository(ctx).debugUploadUrl.ifBlank { BuildConfig.DALANG_DEBUG_UPLOAD_URL }

    private fun outbox(ctx: Context) = File(ctx.getExternalFilesDir(null), "debug_reports/outbox").apply { mkdirs() }

    private fun queued(): List<File> =
        outbox(appCtx).listFiles { f -> f.extension == "zip" }?.sortedBy { it.name } ?: emptyList()

    private fun isTrip(f: File) = f.name.startsWith(TRIP_PREFIX)

    private fun refresh() {
        val q = queued()
        val t = q.count { isTrip(it) }
        main.post { pending = q.size - t; pendingTrips = t }
    }

    /** WLAN o.ä.: Verbindung ist nicht datenvolumenpflichtig. */
    private fun unmetered(): Boolean = try {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (e: Exception) { false }

    private fun toast(msg: String) = main.post { Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show() }

    /** Einmal aus DaLangApp: Retry bei App-Start, Netz zurück, alle 10 min. */
    fun init(app: Context) {
        appCtx = app.applicationContext
        refresh()
        flush(silent = true)
        try {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = flush(silent = true)
                })
        } catch (e: Exception) { CrashLogger.logError("DebugUpload", "netcb failed", e) }
        val tick = object : Runnable {
            override fun run() { flush(silent = true); main.postDelayed(this, 10 * 60_000L) }
        }
        main.postDelayed(tick, 10 * 60_000L)
    }

    /** Aus DebugReporter: Report-Dateien zippen, in die Warteschlange legen, Upload versuchen. */
    fun enqueue(ctx: Context, snapshotTxt: File, audio: File, gpx: File) {
        appCtx = ctx.applicationContext
        io.execute {
            try {
                val tester = SettingsRepository(appCtx).testerName
                val now = Date()
                val info = "Gerät: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                    "App: ${appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName}\nTester: $tester\nZeit: $now\n"
                val zip = File(outbox(appCtx), fileName(now, tester))
                val screenshot = File(snapshotTxt.path.removeSuffix(".txt") + ".jpg")
                writeZip(
                    zip, mapOf("audio.m4a" to audio, "track.gpx" to gpx, "screenshot.jpg" to screenshot),
                    mapOf("info.txt" to info, "snapshot.txt" to (snapshotTxt.takeIf { it.exists() }?.readText() ?: "-"))
                )
                listOf(snapshotTxt, audio, gpx, screenshot).forEach { it.delete() }
                trim(false, MAX_QUEUE_BYTES)
                refresh()
                toast("Report gespeichert")
                flush(silent = false)
            } catch (e: Exception) { CrashLogger.logError("DebugUpload", "enqueue failed", e) }
        }
    }

    /**
     * Aus TripRecorder (eigener Thread): Fahrt-Ordner zippen (trip_*.zip, erst .tmp, dann atomar umbenennen -
     * flush() läuft parallel), Ordner löschen, Fahrten-Limit durchsetzen. Upload nur im WLAN (siehe flush).
     */
    fun enqueueTrip(ctx: Context, name: String, dir: File) {
        appCtx = ctx.applicationContext
        io.execute {
            try {
                val tmp = File(outbox(appCtx), "$name.tmp")
                writeZip(tmp, dir.listFiles().orEmpty().sortedBy { it.name }.associate { it.name to it }, emptyMap())
                tmp.renameTo(File(outbox(appCtx), name))
                dir.deleteRecursively()
                trim(true, MAX_TRIP_BYTES)
                refresh()
                flush(silent = true)
            } catch (e: Exception) { CrashLogger.logError("DebugUpload", "enqueueTrip failed", e) }
        }
    }

    /** Älteste Zips (Fahrten bzw. Reports) löschen, bis die Gruppe unter [limit] liegt; das neueste bleibt immer. */
    private fun trim(trips: Boolean, limit: Long) {
        val files = queued().filter { isTrip(it) == trips }.toMutableList()
        while (files.size > 1 && files.sumOf { it.length() } > limit) files.removeAt(0).delete()
    }

    fun flush(silent: Boolean) {
        io.execute {
            val share = parseShare(uploadUrl(appCtx)) ?: return@execute
            // Reports immer, Fahrten (trip_ sortiert nach den Reports) nur im WLAN
            val files = queued().filter { !isTrip(it) || unmetered() }
            var ok = 0
            for (f in files) {
                if (!send(share, f)) break
                f.delete(); ok++
            }
            if (files.isNotEmpty()) refresh()
            if (ok > 0 && !silent) toast("Report hochgeladen")
        }
    }

    private fun send(share: Pair<String, String>, f: File): Boolean {
        try {
            for (legacy in listOf(false, true)) {
                client.newCall(putRequest(share.first, share.second, f.name, f, legacy)).execute().use { r ->
                    if (r.isSuccessful) return true
                    CrashLogger.log("DebugUpload: ${f.name} -> HTTP ${r.code} (legacy=$legacy)")
                    if (r.code !in listOf(401, 404, 405)) return false
                }
            }
        } catch (e: Exception) {
            CrashLogger.log("DebugUpload: ${f.name} offline/Fehler: ${e.javaClass.simpleName}")
        }
        return false
    }
}
