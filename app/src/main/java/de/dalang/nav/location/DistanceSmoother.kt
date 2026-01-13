package de.dalang.nav.location

import de.dalang.nav.util.CrashLogger

/**
 * Glättet Distanz-Werte zum nächsten Manöver.
 * Verhindert dass die Distanz hochspringt durch GPS-Rauschen.
 *
 * Features:
 * - Erlaubt nur Verringerung der Distanz (+ kleine Toleranz für GPS-Jitter)
 * - Exponential Moving Average für sanfte Übergänge
 * - Intelligente Rundung basierend auf Distanz
 */
class DistanceSmoother {

    private var lastSmoothedDistance: Double = -1.0
    private var lastRawDistance: Double = -1.0
    private var consecutiveIncreases: Int = 0

    // Konfiguration
    private val jitterToleranceMeters = 15.0  // Erlaubte Schwankung nach oben
    private val smoothingFactor = 0.3  // EMA Faktor (0-1, höher = schneller)
    private val maxConsecutiveIncreases = 3  // Nach X Erhöhungen akzeptieren wir neuen Wert

    /**
     * Verarbeitet eine neue Distanz-Messung und gibt die geglättete Distanz zurück.
     *
     * @param rawDistance Gemessene Distanz in Metern
     * @return Geglättete Distanz in Metern
     */
    fun process(rawDistance: Double): Double {
        // Erste Messung
        if (lastSmoothedDistance < 0) {
            lastSmoothedDistance = rawDistance
            lastRawDistance = rawDistance
            consecutiveIncreases = 0
            return rawDistance
        }

        val diff = rawDistance - lastSmoothedDistance

        val newSmoothed: Double

        when {
            // Distanz verringert sich - normal, akzeptieren
            diff <= 0 -> {
                consecutiveIncreases = 0
                // EMA für sanften Übergang
                newSmoothed = lastSmoothedDistance + smoothingFactor * diff
            }

            // Kleine Erhöhung innerhalb Toleranz - ignorieren (GPS-Jitter)
            diff <= jitterToleranceMeters -> {
                consecutiveIncreases++
                // Behalte alten Wert, aber erlaube langsame Anpassung nach unten
                newSmoothed = lastSmoothedDistance
            }

            // Größere Erhöhung
            else -> {
                consecutiveIncreases++

                // Nach mehreren Erhöhungen: Wir sind wohl wirklich weiter weg
                // (z.B. falsch abgebogen, Route neu berechnet)
                if (consecutiveIncreases >= maxConsecutiveIncreases) {
                    CrashLogger.log("DistanceSmoother: Accepting distance increase after $consecutiveIncreases consecutive increases")
                    consecutiveIncreases = 0
                    // Langsam anpassen statt Sprung
                    newSmoothed = lastSmoothedDistance + smoothingFactor * diff
                } else {
                    // Noch nicht genug Erhöhungen - behalten alten Wert
                    newSmoothed = lastSmoothedDistance
                }
            }
        }

        lastSmoothedDistance = newSmoothed.coerceAtLeast(0.0)
        lastRawDistance = rawDistance

        return lastSmoothedDistance
    }

    /**
     * Setzt den Smoother zurück (z.B. bei neuem Manöver)
     */
    fun reset() {
        lastSmoothedDistance = -1.0
        lastRawDistance = -1.0
        consecutiveIncreases = 0
    }

    /**
     * Setzt auf einen neuen Ausgangswert (z.B. bei Schritt-Wechsel)
     */
    fun setInitialDistance(distance: Double) {
        lastSmoothedDistance = distance
        lastRawDistance = distance
        consecutiveIncreases = 0
    }

    /**
     * Gibt die letzte geglättete Distanz zurück
     */
    fun getLastSmoothedDistance(): Double = lastSmoothedDistance

    companion object {
        /**
         * Rundet eine Distanz intelligent für die Anzeige.
         * Unter 100m: auf 10m runden
         * 100-500m: auf 50m runden
         * 500m-1km: auf 100m runden
         * Über 1km: auf 100m runden
         *
         * @param meters Distanz in Metern
         * @return Gerundete Distanz in Metern
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
         *
         * @param meters Distanz in Metern
         * @return Formatierte Distanz-Zeichenkette
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
