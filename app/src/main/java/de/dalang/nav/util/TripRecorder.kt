package de.dalang.nav.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.navigation.Route
import de.dalang.nav.navigation.RouteProgress
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Date
import java.util.concurrent.Executor
import java.util.zip.GZIPOutputStream

/**
 * Fahrtenschreiber: zeichnet jede Navigation auf (Format siehe [TripFormat]) und legt sie als Zip in die
 * Upload-Warteschlange ([DebugUpload.enqueueTrip], nur WLAN).
 *
 * Threading: alle Datei-I/O, Sensor- und GNSS-Callbacks laufen auf EINEM eigenen HandlerThread; die
 * öffentlichen Methoden (Main-Thread) lesen nur Werte und posten. Jede Methode fängt alle Exceptions —
 * die Navigation darf durch den Schreiber nie beeinflusst werden.
 *
 * Zeitbasis: Location.getElapsedRealtimeNanos() und SensorEvent.timestamp. Die Android-Doku sagt zu
 * SensorEvent.timestamp nur: "each new sensor event should be monotonically increasing using the same time
 * base as SystemClock.elapsedRealtimeNanos()"
 * (https://developer.android.com/reference/android/hardware/SensorEvent#timestamp) — "should", keine Garantie.
 * Deshalb wird min(elapsedRealtimeNanos() beim Eintreffen - event.timestamp) gemessen und als
 * `imuClockOffsetNs` in meta.json geschrieben (~Zustellverzögerung, wenige ms; Sekunden/Stunden = andere Basis).
 *
 * Absturz-fest: Dateien liegen in filesDir/trips/<id>/ und werden alle 5 s geflusht (GZIP mit syncFlush);
 * [recoverIncomplete] finalisiert beim nächsten App-Start liegengebliebene Fahrten mit incomplete=true.
 * IMU-gz einer abgebrochenen Fahrt hat keinen Trailer ("unexpected end of file") — Daten bis zum letzten
 * Flush sind lesbar.
 */
object TripRecorder {
    private const val FLUSH_MS = 5000L
    private const val IMU_PERIOD_US = 20_000      // 50 Hz
    private const val PRE_PERIOD_US = 200_000     // 5 Hz: Barometer liefert höher nur Rauschen, spart Größe/Akku
    private const val IMU_DRAIN_CHARS = 16_384

    @Volatile private var active: Session? = null
    private val thread by lazy { HandlerThread("TripRecorder").apply { start() } }
    private val h by lazy { Handler(thread.looper) }

    private fun nowNs() = SystemClock.elapsedRealtimeNanos()

    private inline fun safe(what: String, f: () -> Unit) {
        try { f() } catch (e: Exception) { CrashLogger.logError("TripRecorder", what, e) }
    }

    /** Auf dem Recorder-Thread ausführen; ignoriert, wenn keine Fahrt läuft. */
    private fun post(s: Session? = active, f: Session.() -> Unit) {
        s ?: return
        h.post { safe("write") { s.f() } }
    }

    // ---------- Aufruf aus MainViewModel / NavigationService ----------

    fun start(ctx: Context, route: Route, dest: LatLng?, destName: String?) {
        try {
        val app = ctx.applicationContext
        val settings = SettingsRepository(app)
        if (!settings.tripUploadEnabled) return
        if (active != null) stop(incomplete = true, reason = "restart")
        val s = Session(app, System.currentTimeMillis(), nowNs(), settings.testerName, dest, destName)
        active = s
        h.post { safe("open") { s.open() } }
        routeActivated("start", route)
        } catch (e: Exception) { CrashLogger.logError("TripRecorder", "start", e) }
    }

    /** Ende der Fahrt (Ankunft, Abbruch, ViewModel weg). */
    fun stop(incomplete: Boolean = false, reason: String = "stop") {
        try {
        val s = active ?: return
        event("navStop", null, mapOf("reason" to reason))
        active = null
        h.post { safe("close") { s.close(incomplete) } }
        } catch (e: Exception) { CrashLogger.logError("TripRecorder", "stop", e) }
    }

    fun routeActivated(reason: String, route: Route) {
        val t = nowNs()
        post { line("routes.ndjson", TripFormat.routeLine(t, reason, route)) }
    }

    fun event(type: String, text: String? = null, data: Map<String, Any?>? = null, t: Long = nowNs()) {
        post { line("events.ndjson", TripFormat.eventLine(t, type, text, data)) }
    }

    /** JEDER Fix, auch ungenaue/verworfene — vor jedem Filter aufrufen. */
    fun location(l: Location) {
        val s = active ?: return
        safe("location") {
            val text = TripFormat.fixLine(
                l.elapsedRealtimeNanos, l.time, l.latitude, l.longitude,
                if (l.hasAccuracy()) l.accuracy else null,
                if (l.hasSpeed()) l.speed else null,
                if (l.hasBearing()) l.bearing else null,
                if (l.hasAltitude()) l.altitude else null,
                if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
                if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null
            )
            post(s) { line("gnss.ndjson", text) }
        }
    }

    /** Nach jedem RouteProgress.update/deadReckon. [t]: Zeit des Fixes (sonst jetzt). Erkennt auch Step-/Schätz-/Off-Route-Wechsel. */
    fun progress(p: RouteProgress, t: Long = nowNs()) {
        val s = active ?: return
        safe("progress") {
            val step = p.stepIndex
            val lateral = if (p.estimating || p.lateralM > 1e6) null else p.lateralM
            val text = TripFormat.progressLine(t, p.s, lateral, p.estimating, step, p.distanceToStep(step + 1), p.remainingM, p.matched)
            post(s) { line("progress.ndjson", text) }
            if (s.lastRoute !== p.route) { s.lastRoute = p.route; s.lastStep = step }
            if (step != s.lastStep) {
                event("stepAdvance", null, mapOf("from" to s.lastStep, "to" to step, "stepType" to p.route.steps.getOrNull(step)?.maneuver?.type, "s" to p.s), t)
                s.lastStep = step
            }
            if (p.estimating != s.lastEst) {
                event(if (p.estimating) "estimateStart" else "estimateEnd", null, mapOf("s" to p.s), t)
                s.lastEst = p.estimating
            }
            val off = p.offRoute
            if (off != s.lastOff) {
                if (off) event("offRoute", null, mapOf("s" to p.s, "lateral" to p.lateralM), t)
                s.lastOff = off
            }
        }
    }

    /** Beim App-Start (Hauptprozess): liegengebliebene Fahrten finalisieren und einreihen. */
    fun recoverIncomplete(ctx: Context) {
        val app = ctx.applicationContext
        h.post {
            safe("recover") {
                for (d in File(app.filesDir, "trips").listFiles { f -> f.isDirectory }.orEmpty()) {
                    if (d == active?.dir) continue
                    safe("recoverDir") { recoverDir(app, d) }
                }
            }
        }
    }

    private fun recoverDir(app: Context, d: File) {
        val metaFile = File(d, "meta.json")
        val meta = try { JSONObject(metaFile.readText()) } catch (e: Exception) { d.deleteRecursively(); return }
        val end = d.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis()
        meta.put("endWall", maxOf(end, meta.optLong("startWall")))
        meta.put("incomplete", true)
        metaFile.writeText(meta.toString())
        DebugUpload.enqueueTrip(app, DebugUpload.fileName(Date(meta.optLong("startWall")), meta.optString("tester", "tester"), "trip_"), d)
    }

    // ---------- eine Fahrt ----------

    private class Session(
        val app: Context, val startWall: Long, val startNs: Long, val tester: String,
        val dest: LatLng?, val destName: String?
    ) {
        val dir = File(app.filesDir, "trips/" + DebugUpload.stamp(Date(startWall)))

        // nur Main-Thread (progress())
        var lastRoute: Route? = null
        var lastStep = 0
        var lastEst = false
        var lastOff = false

        // nur Recorder-Thread
        private val writers = HashMap<String, BufferedWriter>()
        private var imu: GZIPOutputStream? = null
        private val imuBuf = StringBuilder(IMU_DRAIN_CHARS + 256)
        private var closed = false
        private var minOffsetNs = Long.MAX_VALUE
        private var imuOffsetNs: Long? = null
        private var sm: SensorManager? = null
        private var lm: LocationManager? = null

        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { safe("imu") { imuEvent(e) } }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        private val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                safe("gnssStatus") {
                    var used = 0
                    var cn0 = 0.0
                    for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) { used++; cn0 += status.getCn0DbHz(i) }
                    line("gnss.ndjson", TripFormat.statusLine(nowNs(), used, status.satelliteCount, if (used > 0) cn0 / used else null))
                }
            }
        }

        private fun writer(name: String) = writers.getOrPut(name) {
                BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, name), true), Charsets.UTF_8), 16 * 1024)
        }

        fun line(name: String, text: String) {
            if (closed) return
            val w = writer(name)
            w.write(text); w.write("\n")
        }

        @Suppress("DEPRECATION", "MissingPermission")
        fun open() {
            dir.mkdirs()
            writeMeta(incomplete = true, endWall = startWall)
            for (n in listOf("routes.ndjson", "gnss.ndjson", "progress.ndjson", "events.ndjson")) writer(n)
            imu = GZIPOutputStream(BufferedOutputStream(FileOutputStream(File(dir, "imu.csv.gz")), 32 * 1024), 16 * 1024, true)
                .also { it.write(TripFormat.IMU_HEADER.toByteArray(Charsets.US_ASCII)) }
            // Sensoren nur während der Fahrt registriert
            safe("sensors") {
                val m = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                sm = m
                reg(m, Sensor.TYPE_ACCELEROMETER, IMU_PERIOD_US)
                reg(m, Sensor.TYPE_GYROSCOPE, IMU_PERIOD_US)
                reg(m, Sensor.TYPE_PRESSURE, PRE_PERIOD_US)
                reg(m, Sensor.TYPE_GAME_ROTATION_VECTOR, IMU_PERIOD_US)
            }
            safe("gnss") {
                val l = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm = l
                if (Build.VERSION.SDK_INT >= 30) l.registerGnssStatusCallback(Executor { h.post(it) }, gnssCallback)
                else l.registerGnssStatusCallback(gnssCallback, h)
            }
            h.postDelayed(object : Runnable {
                override fun run() { if (!closed) { safe("flush") { flush() }; h.postDelayed(this, FLUSH_MS) } }
            }, FLUSH_MS)
        }

        private fun reg(m: SensorManager, type: Int, periodUs: Int) {
            val s = m.getDefaultSensor(type) ?: return
            m.registerListener(sensorListener, s, periodUs, h)
        }

        private fun imuEvent(e: SensorEvent) {
            if (closed) return
            val v = e.values
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> TripFormat.imuRow(imuBuf, e.timestamp, "acc", 3, v[0], v[1], v[2], 3)
                Sensor.TYPE_GYROSCOPE -> TripFormat.imuRow(imuBuf, e.timestamp, "gyr", 4, v[0], v[1], v[2], 3)
                Sensor.TYPE_PRESSURE -> TripFormat.imuRow(imuBuf, e.timestamp, "pre", 3, v[0], 0f, 0f, 1)
                Sensor.TYPE_GAME_ROTATION_VECTOR -> TripFormat.imuRow(imuBuf, e.timestamp, "grv", 4, v[0], v[1], v[2], 3)
                else -> return
            }
            val off = nowNs() - e.timestamp
            if (off < minOffsetNs) minOffsetNs = off
            if (imuOffsetNs == null) { imuOffsetNs = off; writeMeta(true, System.currentTimeMillis()) }
            if (imuBuf.length >= IMU_DRAIN_CHARS) drainImu()
        }

        private fun drainImu() {
            imu?.write(imuBuf.toString().toByteArray(Charsets.US_ASCII))
            imuBuf.setLength(0)
        }

        private fun flush() {
            drainImu()
            imu?.flush()
            writers.values.forEach { it.flush() }
        }

        private fun writeMeta(incomplete: Boolean, endWall: Long) {
            val ver = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
            File(dir, "meta.json").writeText(
                TripFormat.metaJson(
                    ver, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, tester, startWall, endWall, startNs,
                    dest?.lat, dest?.lng, destName, incomplete, if (minOffsetNs == Long.MAX_VALUE) imuOffsetNs else minOffsetNs
                )
            )
        }

        fun close(incomplete: Boolean) {
            safe("unregister") { sm?.unregisterListener(sensorListener) }
            safe("unregisterGnss") { lm?.unregisterGnssStatusCallback(gnssCallback) }
            safe("closeFiles") { flush(); imu?.close(); writers.values.forEach { it.close() } }
            closed = true
            safe("meta") { writeMeta(incomplete, System.currentTimeMillis()) }
            DebugUpload.enqueueTrip(app, DebugUpload.fileName(Date(startWall), tester, "trip_"), dir)
        }
    }
}
