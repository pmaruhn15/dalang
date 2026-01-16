package de.dalang.nav.util

/**
 * Zentrale Klasse für die Formatierung von Distanzen.
 * Konsolidiert alle formatDistance-Implementierungen an einem Ort.
 */
object DistanceFormatter {

    /**
     * Formatiert eine Distanz in Metern für die Anzeige mit intelligenter Rundung.
     *
     * Rundungslogik:
     * - Unter 100m: auf 10m runden (10, 20, 30, ... 90m)
     * - 100-500m: auf 50m runden (100, 150, 200, ... 500m)
     * - 500m-1km: auf 100m runden (500, 600, ... 1000m)
     * - Über 1km: auf 0.1km Genauigkeit
     *
     * @param meters Distanz in Metern
     * @return Formatierte Distanz-Zeichenkette (z.B. "150 m", "1.2 km")
     */
    fun formatMeters(meters: Double): String {
        // Intelligente Rundung basierend auf Distanz
        val rounded = when {
            meters < 100 -> (meters / 10).toInt() * 10.0
            meters < 500 -> (meters / 50).toInt() * 50.0
            meters < 1000 -> (meters / 100).toInt() * 100.0
            else -> meters  // Über 1km: keine Rundung der Meter, km-Formatierung macht das
        }

        return when {
            rounded >= 1000 -> {
                val km = rounded / 1000
                if (km >= 10) {
                    "${km.toInt()} km"
                } else {
                    String.format("%.1f km", km)
                }
            }
            rounded < 10 -> "10 m"  // Minimum 10m anzeigen
            else -> "${rounded.toInt()} m"
        }
    }

    /**
     * Formatiert eine Distanz in Kilometern für die Anzeige.
     * Konvertiert intern zu Metern und nutzt die intelligente Rundung.
     *
     * @param kilometers Distanz in Kilometern
     * @return Formatierte Distanz-Zeichenkette
     */
    fun formatKilometers(kilometers: Double): String {
        return formatMeters(kilometers * 1000)
    }

    /**
     * Einfache Formatierung für Kilometer ohne intelligente Rundung.
     * Wird für Suchergebnisse und POI-Distanzen verwendet.
     *
     * @param kilometers Distanz in Kilometern
     * @return Formatierte Distanz-Zeichenkette (z.B. "850 m", "2.3 km")
     */
    fun formatKilometersSimple(kilometers: Double): String {
        return when {
            kilometers < 1 -> "${(kilometers * 1000).toInt()} m"
            kilometers < 10 -> String.format("%.1f km", kilometers)
            else -> "${kilometers.toInt()} km"
        }
    }

    /**
     * Formatiert eine Dauer in Sekunden für die Anzeige.
     *
     * @param seconds Dauer in Sekunden
     * @return Formatierte Dauer-Zeichenkette (z.B. "5 Min.", "1 Std. 30 Min.")
     */
    fun formatDuration(seconds: Double): String {
        val minutes = (seconds / 60).toInt()
        val hours = minutes / 60
        val remainingMinutes = minutes % 60

        return if (hours > 0) {
            "$hours Std. $remainingMinutes Min."
        } else {
            "$minutes Min."
        }
    }
}
