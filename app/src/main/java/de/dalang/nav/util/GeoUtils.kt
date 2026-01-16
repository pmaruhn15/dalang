package de.dalang.nav.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zentrale Geo-Utility-Klasse für alle Distanz- und Koordinaten-Berechnungen.
 * Konsolidiert alle Haversine-Implementierungen an einem Ort.
 */
object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0
    private const val METERS_PER_DEGREE_EQUATOR = 111000.0

    /**
     * Berechnet die Distanz zwischen zwei Koordinaten in Metern (Haversine-Formel).
     * Dies ist die zentrale Distanz-Berechnungsfunktion für die gesamte App.
     *
     * @param lat1 Breitengrad des ersten Punktes
     * @param lng1 Längengrad des ersten Punktes
     * @param lat2 Breitengrad des zweiten Punktes
     * @param lng2 Längengrad des zweiten Punktes
     * @return Distanz in Metern
     */
    fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Berechnet die Distanz zwischen zwei Koordinaten in Kilometern.
     *
     * @param lat1 Breitengrad des ersten Punktes
     * @param lng1 Längengrad des ersten Punktes
     * @param lat2 Breitengrad des zweiten Punktes
     * @param lng2 Längengrad des zweiten Punktes
     * @return Distanz in Kilometern
     */
    fun calculateDistanceKilometers(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        return calculateDistanceMeters(lat1, lng1, lat2, lng2) / 1000.0
    }

    /**
     * Konvertiert Meter in Grad (approximativ).
     * Nützlich für Kalman-Filter und andere Berechnungen.
     *
     * @param meters Distanz in Metern
     * @param atLatitude Breitengrad für die Umrechnung (beeinflusst Längengrad-Skalierung)
     * @return Approximative Distanz in Grad
     */
    fun metersToDegreesApprox(meters: Double, atLatitude: Double): Double {
        val metersPerDegree = METERS_PER_DEGREE_EQUATOR * cos(Math.toRadians(atLatitude))
        return if (metersPerDegree > 0) meters / metersPerDegree else meters / METERS_PER_DEGREE_EQUATOR
    }

    /**
     * Konvertiert Grad in Meter (approximativ).
     *
     * @param degrees Distanz in Grad
     * @param atLatitude Breitengrad für die Umrechnung
     * @return Approximative Distanz in Metern
     */
    fun degreesToMetersApprox(degrees: Double, atLatitude: Double): Double {
        val metersPerDegree = METERS_PER_DEGREE_EQUATOR * cos(Math.toRadians(atLatitude))
        return degrees * metersPerDegree
    }

    /**
     * Berechnet den Bearing (Kurswinkel) zwischen zwei Punkten in Grad (0-360).
     *
     * @param lat1 Breitengrad des Startpunktes
     * @param lng1 Längengrad des Startpunktes
     * @param lat2 Breitengrad des Zielpunktes
     * @param lng2 Längengrad des Zielpunktes
     * @return Bearing in Grad (0 = Nord, 90 = Ost, 180 = Süd, 270 = West)
     */
    fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)

        val y = sin(dLng) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLng)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360  // Normalisieren auf 0-360

        return bearing
    }
}
