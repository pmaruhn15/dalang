package de.dalang.nav.location

import android.location.Location
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.util.CrashLogger
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Glättet GPS-Positionen mit einem erweiterten Kalman-Filter inkl. Geschwindigkeitsschätzung.
 * Verhindert wilde Sprünge des Ego-Markers und sorgt für flüssige Bewegung.
 *
 * Features:
 * - 4-State Kalman Filter (lat, lng, vLat, vLng) für Position + Geschwindigkeit
 * - Velocity-basierte Vorhersage zwischen GPS-Updates
 * - Ausreißer-Filterung basierend auf berechneter vs. GPS-Geschwindigkeit
 * - Interpolation für flüssige Animation
 */
class LocationSmoother {

    // 4-State Kalman Filter: [lat, lng, vLat, vLng]
    // vLat/vLng = Geschwindigkeit in Grad/Sekunde
    private var lat = 0.0
    private var lng = 0.0
    private var vLat = 0.0  // Geschwindigkeit in lat-Richtung (Grad/s)
    private var vLng = 0.0  // Geschwindigkeit in lng-Richtung (Grad/s)

    // Kovarianz-Matrix (vereinfacht: nur Diagonale)
    private var pLat = -1.0  // -1 = nicht initialisiert
    private var pLng = -1.0
    private var pVLat = 1.0
    private var pVLng = 1.0

    // Für Ausreißererkennung
    private var lastRawLocation: LatLng? = null
    private var lastTimestamp: Long = 0
    private var lastGpsSpeed: Float = 0f

    // Konfiguration
    private val minAccuracyMeters = 50f  // Positionen mit schlechterer Accuracy werden ignoriert
    private val maxSpeedMs = 70f  // ~250 km/h - Ausreißer über dieser Geschwindigkeit werden gefiltert
    private val processNoisePos = 0.00001  // Prozessrauschen Position (Grad²/s)
    private val processNoiseVel = 0.0001   // Prozessrauschen Geschwindigkeit (Grad²/s³)

    /**
     * Verarbeitet eine neue GPS-Position und gibt die geglättete Position zurück.
     * Gibt null zurück wenn die Position als Ausreißer erkannt wurde.
     */
    fun process(location: Location): LatLng? {
        val timestamp = location.time
        val measLat = location.latitude
        val measLng = location.longitude
        val accuracy = if (location.hasAccuracy()) location.accuracy else 100f
        val gpsSpeed = if (location.hasSpeed()) location.speed else 0f

        // 1. Accuracy-Filter: Schlechte GPS-Signale ignorieren
        if (accuracy > minAccuracyMeters) {
            CrashLogger.log("LocationSmoother: Rejected low accuracy (${accuracy}m)")
            return null
        }

        // 2. Geschwindigkeits-Ausreißerfilter
        val lastLoc = lastRawLocation
        if (lastLoc != null && timestamp > lastTimestamp) {
            val timeDelta = (timestamp - lastTimestamp) / 1000.0
            if (timeDelta > 0 && timeDelta < 30) {  // Max 30s zwischen Updates
                val distance = calculateDistance(lastLoc.lat, lastLoc.lng, measLat, measLng)
                val calculatedSpeed = distance / timeDelta

                // Wenn berechnete Geschwindigkeit unrealistisch hoch (Teleport)
                if (calculatedSpeed > maxSpeedMs) {
                    CrashLogger.log("LocationSmoother: Rejected speed anomaly (${(calculatedSpeed * 3.6).toInt()} km/h)")
                    return null
                }

                // Plausibilitätsprüfung: Bei niedrigem GPS-Speed aber hohem berechneten Speed
                if (gpsSpeed < 5f && calculatedSpeed > 15f) {
                    CrashLogger.log("LocationSmoother: Rejected jump (GPS: ${(gpsSpeed * 3.6).toInt()}, calc: ${(calculatedSpeed * 3.6).toInt()} km/h)")
                    return null
                }
            }
        }

        // 3. Kalman-Filter anwenden
        val smoothedLat: Double
        val smoothedLng: Double

        if (pLat < 0) {
            // Erste Position - initialisieren
            lat = measLat
            lng = measLng
            vLat = 0.0
            vLng = 0.0

            // Initiale Unsicherheit basierend auf GPS-Accuracy
            val accDegrees = metersToDegreesApprox(accuracy.toDouble(), measLat)
            pLat = accDegrees.pow(2)
            pLng = accDegrees.pow(2)
            pVLat = 0.001  // Initiale Geschwindigkeits-Unsicherheit
            pVLng = 0.001

            smoothedLat = measLat
            smoothedLng = measLng
            CrashLogger.log("LocationSmoother: Initialized at $measLat, $measLng (accuracy: ${accuracy}m)")
        } else {
            // Zeit seit letztem Update
            val dt = if (lastTimestamp > 0) {
                ((timestamp - lastTimestamp) / 1000.0).coerceIn(0.01, 10.0)
            } else 1.0

            // === PREDICTION STEP ===
            // Position vorhersagen basierend auf Geschwindigkeit
            val predLat = lat + vLat * dt
            val predLng = lng + vLng * dt
            // Geschwindigkeit bleibt gleich (konstantes Modell)
            val predVLat = vLat
            val predVLng = vLng

            // Kovarianz erhöhen (Prozessrauschen)
            pLat += pVLat * dt * dt + processNoisePos * dt
            pLng += pVLng * dt * dt + processNoisePos * dt
            pVLat += processNoiseVel * dt
            pVLng += processNoiseVel * dt

            // === UPDATE STEP ===
            // Messrauschen in Grad umrechnen
            val measNoise = metersToDegreesApprox(accuracy.toDouble(), measLat).pow(2)

            // Kalman Gain für Position
            val kLat = pLat / (pLat + measNoise)
            val kLng = pLng / (pLng + measNoise)

            // Innovation (Differenz zwischen Messung und Vorhersage)
            val innovLat = measLat - predLat
            val innovLng = measLng - predLng

            // Update Position
            lat = predLat + kLat * innovLat
            lng = predLng + kLng * innovLng

            // Update Geschwindigkeit basierend auf Innovation
            // Je größer die Innovation, desto mehr passen wir die Geschwindigkeit an
            val kVel = 0.3  // Geschwindigkeits-Lernrate
            vLat = predVLat + kVel * innovLat / dt
            vLng = predVLng + kVel * innovLng / dt

            // Geschwindigkeit begrenzen (max ~150 km/h in jede Richtung)
            val maxVelDegrees = metersToDegreesApprox(42.0, lat)  // 42 m/s ≈ 150 km/h
            vLat = vLat.coerceIn(-maxVelDegrees, maxVelDegrees)
            vLng = vLng.coerceIn(-maxVelDegrees, maxVelDegrees)

            // Update Kovarianz
            pLat = (1 - kLat) * pLat
            pLng = (1 - kLng) * pLng

            smoothedLat = lat
            smoothedLng = lng
        }

        // State für nächste Iteration speichern
        lastRawLocation = LatLng(measLat, measLng)
        lastTimestamp = timestamp
        lastGpsSpeed = gpsSpeed

        return LatLng(smoothedLat, smoothedLng)
    }

    /**
     * Gibt eine interpolierte Position für flüssige Animation zurück.
     * Basiert auf der aktuellen Geschwindigkeit und Zeit seit letztem Update.
     *
     * @param currentTimeMs Aktuelle Zeit in Millisekunden
     * @return Interpolierte Position oder letzte bekannte Position
     */
    fun getInterpolatedPosition(currentTimeMs: Long): LatLng? {
        if (pLat < 0) return null  // Nicht initialisiert

        val timeSinceUpdate = (currentTimeMs - lastTimestamp) / 1000.0
        if (timeSinceUpdate < 0 || timeSinceUpdate > 5.0) {
            // Zu alt oder in der Zukunft - keine Interpolation
            return LatLng(lat, lng)
        }

        // Interpoliere basierend auf Geschwindigkeit
        val interpLat = lat + vLat * timeSinceUpdate
        val interpLng = lng + vLng * timeSinceUpdate

        return LatLng(interpLat, interpLng)
    }

    /**
     * Gibt die aktuelle geschätzte Geschwindigkeit in m/s zurück.
     */
    fun getEstimatedSpeedMs(): Double {
        if (pLat < 0) return 0.0

        // Geschwindigkeit von Grad/s in m/s umrechnen
        val vLatMs = degreesToMetersApprox(vLat, lat)
        val vLngMs = degreesToMetersApprox(vLng, lat) * kotlin.math.cos(Math.toRadians(lat))

        return sqrt(vLatMs.pow(2) + vLngMs.pow(2))
    }

    /**
     * Setzt den Filter zurück (z.B. bei Navigationsstopp)
     */
    fun reset() {
        pLat = -1.0
        pLng = -1.0
        pVLat = 1.0
        pVLng = 1.0
        vLat = 0.0
        vLng = 0.0
        lastRawLocation = null
        lastTimestamp = 0
        lastGpsSpeed = 0f
        CrashLogger.log("LocationSmoother: Reset")
    }

    /**
     * Gibt die letzte geglättete Position zurück
     */
    fun getLastSmoothedLocation(): LatLng? {
        return if (pLat < 0) null else LatLng(lat, lng)
    }

    /**
     * Konvertiert Meter in Grad (approximativ)
     */
    private fun metersToDegreesApprox(meters: Double, atLatitude: Double): Double {
        // 1 Grad ≈ 111km am Äquator, weniger an den Polen
        val metersPerDegree = 111000.0 * kotlin.math.cos(Math.toRadians(atLatitude))
        return if (metersPerDegree > 0) meters / metersPerDegree else meters / 111000.0
    }

    /**
     * Konvertiert Grad in Meter (approximativ)
     */
    private fun degreesToMetersApprox(degrees: Double, atLatitude: Double): Double {
        val metersPerDegree = 111000.0 * kotlin.math.cos(Math.toRadians(atLatitude))
        return degrees * metersPerDegree
    }

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
