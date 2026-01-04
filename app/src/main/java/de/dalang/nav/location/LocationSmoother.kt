package de.dalang.nav.location

import android.location.Location
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.util.CrashLogger
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Glättet GPS-Positionen mit einem Kalman-Filter und filtert Ausreißer.
 * Verhindert wilde Sprünge des Ego-Markers und unnötige Routenneuberechnung.
 */
class LocationSmoother {

    // Kalman Filter State
    private var latitude = 0.0
    private var longitude = 0.0
    private var variance = -1.0  // -1 = nicht initialisiert

    // Für Velocity-basierte Ausreißererkennung
    private var lastLocation: LatLng? = null
    private var lastTimestamp: Long = 0
    private var lastSpeed: Float = 0f

    // Konfiguration
    private val minAccuracyMeters = 50f  // Positionen mit schlechterer Accuracy werden ignoriert
    private val maxSpeedMs = 70f  // ~250 km/h - Ausreißer über dieser Geschwindigkeit werden gefiltert
    private val kalmanQ = 3.0  // Prozessrauschen (höher = schnellere Anpassung)

    /**
     * Verarbeitet eine neue GPS-Position und gibt die geglättete Position zurück.
     * Gibt null zurück wenn die Position als Ausreißer erkannt wurde.
     */
    fun process(location: Location): LatLng? {
        val timestamp = location.time
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = if (location.hasAccuracy()) location.accuracy else 100f
        val gpsSpeed = if (location.hasSpeed()) location.speed else 0f

        // 1. Accuracy-Filter: Schlechte GPS-Signale ignorieren
        if (accuracy > minAccuracyMeters) {
            CrashLogger.log("LocationSmoother: Rejected low accuracy (${accuracy}m)")
            return null
        }

        // 2. Geschwindigkeits-Ausreißerfilter
        val lastLoc = lastLocation
        if (lastLoc != null && timestamp > lastTimestamp) {
            val timeDelta = (timestamp - lastTimestamp) / 1000.0  // Sekunden
            if (timeDelta > 0) {
                val distance = calculateDistance(lastLoc.lat, lastLoc.lng, lat, lng)
                val calculatedSpeed = distance / timeDelta

                // Wenn berechnete Geschwindigkeit unrealistisch hoch (Teleport)
                if (calculatedSpeed > maxSpeedMs) {
                    CrashLogger.log("LocationSmoother: Rejected speed anomaly (${(calculatedSpeed * 3.6).toInt()} km/h)")
                    return null
                }

                // Plausibilitätsprüfung: Bei niedrigem GPS-Speed aber hohem berechneten Speed
                // Dies deutet auf einen GPS-Sprung hin
                if (gpsSpeed < 5f && calculatedSpeed > 15f) {
                    CrashLogger.log("LocationSmoother: Rejected jump (GPS: ${(gpsSpeed * 3.6).toInt()}, calc: ${(calculatedSpeed * 3.6).toInt()} km/h)")
                    return null
                }
            }
        }

        // 3. Kalman-Filter anwenden
        val smoothedLat: Double
        val smoothedLng: Double

        if (variance < 0) {
            // Erste Position - initialisieren
            latitude = lat
            longitude = lng
            variance = accuracy.toDouble().pow(2)
            smoothedLat = lat
            smoothedLng = lng
            CrashLogger.log("LocationSmoother: Initialized at $lat, $lng (accuracy: ${accuracy}m)")
        } else {
            // Kalman Update
            // Prediction: Varianz erhöht sich über Zeit (Prozessrauschen)
            val timeSinceLastUpdate = if (lastTimestamp > 0) {
                (timestamp - lastTimestamp) / 1000.0
            } else 1.0
            variance += timeSinceLastUpdate * kalmanQ

            // Kalman Gain
            val measurementVariance = accuracy.toDouble().pow(2)
            val kalmanGain = variance / (variance + measurementVariance)

            // Update
            latitude += kalmanGain * (lat - latitude)
            longitude += kalmanGain * (lng - longitude)
            variance = (1 - kalmanGain) * variance

            smoothedLat = latitude
            smoothedLng = longitude
        }

        // State für nächste Iteration speichern
        lastLocation = LatLng(smoothedLat, smoothedLng)
        lastTimestamp = timestamp
        lastSpeed = gpsSpeed

        return LatLng(smoothedLat, smoothedLng)
    }

    /**
     * Setzt den Filter zurück (z.B. bei Navigationsstopp)
     */
    fun reset() {
        variance = -1.0
        lastLocation = null
        lastTimestamp = 0
        lastSpeed = 0f
        CrashLogger.log("LocationSmoother: Reset")
    }

    /**
     * Gibt die letzte geglättete Position zurück
     */
    fun getLastSmoothedLocation(): LatLng? = lastLocation

    /**
     * Berechnet Distanz zwischen zwei Koordinaten in Metern (Haversine)
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0  // Meter

        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val a = kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLng / 2).pow(2)

        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
