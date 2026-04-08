package de.dalang.nav.location

/**
 * Glättet Distanz-Werte zum nächsten Manöver.
 * Verhindert dass die Distanz wild springt durch GPS-Rauschen.
 *
 * Vereinfachte Version:
 * - Asymmetrisches EMA: schnell bei Verringerung, langsamer bei Erhöhung
 * - Kein Blockieren von Updates mehr (das war der Bug!)
 * - Jitter-Toleranz für kleine GPS-Schwankungen
 */
class DistanceSmoother {

    private var lastSmoothedDistance: Double = -1.0

    // Konfiguration
    private val jitterToleranceMeters = 10.0  // Kleine Schwankungen ignorieren
    private val smoothingFactorDecrease = 0.5  // Schnell folgen wenn Distanz sinkt
    private val smoothingFactorIncrease = 0.2  // Langsamer folgen wenn Distanz steigt

    /**
     * Verarbeitet eine neue Distanz-Messung und gibt die geglättete Distanz zurück.
     */
    fun process(rawDistance: Double): Double {
        // Erste Messung
        if (lastSmoothedDistance < 0) {
            lastSmoothedDistance = rawDistance
            return rawDistance
        }

        val diff = rawDistance - lastSmoothedDistance

        val newSmoothed = when {
            // Distanz verringert sich - schnell folgen
            diff <= 0 -> {
                lastSmoothedDistance + smoothingFactorDecrease * diff
            }

            // Kleine Erhöhung innerhalb Toleranz - ignorieren (GPS-Jitter)
            diff <= jitterToleranceMeters -> {
                lastSmoothedDistance
            }

            // Größere Erhöhung - langsam folgen (Route neu berechnet, falsch abgebogen)
            else -> {
                lastSmoothedDistance + smoothingFactorIncrease * diff
            }
        }

        lastSmoothedDistance = newSmoothed.coerceAtLeast(0.0)
        return lastSmoothedDistance
    }

    /**
     * Setzt den Smoother zurück (z.B. bei neuem Manöver)
     */
    fun reset() {
        lastSmoothedDistance = -1.0
    }

    /**
     * Setzt auf einen neuen Ausgangswert (z.B. bei Schritt-Wechsel)
     */
    fun setInitialDistance(distance: Double) {
        lastSmoothedDistance = distance
    }

    /**
     * Gibt die letzte geglättete Distanz zurück
     */
    fun getLastSmoothedDistance(): Double = lastSmoothedDistance

    companion object {
        /**
         * Rundet eine Distanz intelligent für die Anzeige.
         */
        fun roundForDisplay(meters: Double): Double {
            return when {
                meters < 100 -> (meters / 10).toInt() * 10.0
                meters < 500 -> (meters / 50).toInt() * 50.0
                else -> (meters / 100).toInt() * 100.0
            }
        }

        /**
         * Formatiert eine Distanz für die Anzeige mit intelligenter Rundung.
         */
        fun formatSmartDistance(meters: Double): String {
            val rounded = roundForDisplay(meters)
            return when {
                rounded >= 1000 -> {
                    val km = rounded / 1000
                    if (km >= 10) {
                        "${km.toInt()} km"
                    } else {
                        String.format("%.1f km", km)
                    }
                }
                else -> "${rounded.toInt()} m"
            }
        }
    }
}
