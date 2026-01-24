package de.dalang.nav.util

import android.content.Context
import de.dalang.nav.navigation.Lane
import de.dalang.nav.navigation.LaneInfo
import de.dalang.nav.navigation.LatLng
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GPS Track Logger für Debugging.
 * Loggt GPS-Positionen und Lane-Informationen mit Zeitstempeln.
 */
object GpsTrackLogger {

    private val trackPoints = ConcurrentLinkedQueue<TrackPoint>()
    private val laneEvents = ConcurrentLinkedQueue<LaneEvent>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)

    // Max Punkte im Speicher (ca. 1 Stunde bei 1 Hz)
    private const val MAX_TRACK_POINTS = 3600
    private const val MAX_LANE_EVENTS = 500

    private var isEnabled = true
    private var sessionStartTime: Long = 0L

    data class TrackPoint(
        val timestamp: Long,
        val lat: Double,
        val lng: Double,
        val speed: Float,
        val bearing: Float,
        val accuracy: Float
    )

    data class LaneEvent(
        val timestamp: Long,
        val lat: Double,
        val lng: Double,
        val lanes: List<String>,  // z.B. ["left", "straight+right*", "right"]
        val stepName: String
    )

    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        trackPoints.clear()
        laneEvents.clear()
        CrashLogger.log("GpsTrackLogger: Session started")
    }

    fun stopSession() {
        CrashLogger.log("GpsTrackLogger: Session stopped with ${trackPoints.size} points, ${laneEvents.size} lane events")
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun logPosition(
        location: LatLng,
        speed: Float = 0f,
        bearing: Float = 0f,
        accuracy: Float = 0f
    ) {
        if (!isEnabled) return

        val point = TrackPoint(
            timestamp = System.currentTimeMillis(),
            lat = location.lat,
            lng = location.lng,
            speed = speed,
            bearing = bearing,
            accuracy = accuracy
        )

        trackPoints.add(point)

        // Alte Punkte entfernen wenn zu viele
        while (trackPoints.size > MAX_TRACK_POINTS) {
            trackPoints.poll()
        }
    }

    fun logLaneInfo(location: LatLng, laneInfo: LaneInfo, stepName: String) {
        if (!isEnabled) return

        val lanesStr = laneInfo.lanes.map { lane ->
            val dirs = lane.directions.joinToString("+")
            if (lane.isRecommended) "$dirs*" else dirs
        }

        val event = LaneEvent(
            timestamp = System.currentTimeMillis(),
            lat = location.lat,
            lng = location.lng,
            lanes = lanesStr,
            stepName = stepName
        )

        laneEvents.add(event)

        // Alte Events entfernen
        while (laneEvents.size > MAX_LANE_EVENTS) {
            laneEvents.poll()
        }

        CrashLogger.log("GpsTrackLogger: Lane at $stepName: ${lanesStr.joinToString(" | ")}")
    }

    /**
     * Exportiert den Track als GPX-Datei (kann in Google Earth/Maps geöffnet werden)
     */
    fun exportToGpx(context: Context): File? {
        try {
            val points = trackPoints.toList()
            if (points.isEmpty()) return null

            val gpxContent = buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("""<gpx version="1.1" creator="DaLang Nav" xmlns="http://www.topografix.com/GPX/1/1">""")
                appendLine("  <trk>")
                appendLine("    <name>DaLang Track ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMANY).format(Date(sessionStartTime))}</name>")
                appendLine("    <trkseg>")

                for (point in points) {
                    val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.format(Date(point.timestamp))

                    appendLine("""      <trkpt lat="${point.lat}" lon="${point.lng}">""")
                    appendLine("""        <time>$time</time>""")
                    if (point.speed > 0) {
                        appendLine("""        <speed>${point.speed}</speed>""")
                    }
                    if (point.bearing > 0) {
                        appendLine("""        <course>${point.bearing}</course>""")
                    }
                    appendLine("      </trkpt>")
                }

                appendLine("    </trkseg>")
                appendLine("  </trk>")

                // Lane Events als Waypoints
                val lanes = laneEvents.toList()
                if (lanes.isNotEmpty()) {
                    appendLine("  <!-- Lane Events -->")
                    for (event in lanes) {
                        val time = dateFormat.format(Date(event.timestamp))
                        appendLine("""  <wpt lat="${event.lat}" lon="${event.lng}">""")
                        appendLine("""    <name>Lanes: ${event.lanes.joinToString(" | ")}</name>""")
                        appendLine("""    <desc>${event.stepName} @ $time</desc>""")
                        appendLine("  </wpt>")
                    }
                }

                appendLine("</gpx>")
            }

            val fileName = "dalang_track_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date(sessionStartTime))}.gpx"
            val file = File(context.getExternalFilesDir(null), fileName)
            file.writeText(gpxContent)

            CrashLogger.log("GpsTrackLogger: Exported GPX to ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            CrashLogger.logError("GpsTrackLogger", "Export failed", e)
            return null
        }
    }

    /**
     * Gibt einen kurzen Debug-Summary als String zurück
     */
    fun getDebugSummary(): String {
        val points = trackPoints.toList()
        val lanes = laneEvents.toList()

        if (points.isEmpty()) return "Keine GPS-Daten"

        return buildString {
            appendLine("=== GPS Track Debug ===")
            appendLine("Punkte: ${points.size}")
            appendLine("Lane Events: ${lanes.size}")

            if (lanes.isNotEmpty()) {
                appendLine("\nLetzte 5 Lane Events:")
                lanes.takeLast(5).forEach { event ->
                    val time = dateFormat.format(Date(event.timestamp))
                    appendLine("  $time: ${event.lanes.joinToString(" | ")} @ ${event.stepName}")
                }
            }

            val last = points.lastOrNull()
            if (last != null) {
                appendLine("\nLetzte Position: ${last.lat}, ${last.lng}")
                appendLine("Speed: ${(last.speed * 3.6).toInt()} km/h")
            }
        }
    }

    /**
     * Gibt die letzten N Lane-Events für Debugging zurück
     */
    fun getRecentLaneEvents(count: Int = 10): List<LaneEvent> {
        return laneEvents.toList().takeLast(count)
    }
}
