package de.dalang.nav.navigation

enum class WaypointType {
    MCDONALDS,
    GAS_STATION
}

data class NavigationState(
    val isNavigating: Boolean = false,
    val route: Route? = null,
    val currentStepIndex: Int = 0,
    val distanceToNextStep: Double = 0.0,
    val totalDistanceRemaining: Double = 0.0,
    val totalTimeRemaining: Double = 0.0,
    val currentLocation: LatLng? = null,
    val destination: LatLng? = null,
    val destinationName: String? = null,
    val isRecalculating: Boolean = false,
    val hasArrived: Boolean = false,
    // Waypoint (Zwischenziel) - z.B. McDonald's oder Tankstelle
    val waypoint: LatLng? = null,
    val waypointName: String? = null,
    val waypointType: WaypointType? = null,
    val distanceToWaypoint: Double = 0.0,
    val timeToWaypoint: Double = 0.0
) {
    val currentStep: RouteStep?
        get() = route?.steps?.getOrNull(currentStepIndex)

    val nextStep: RouteStep?
        get() = route?.steps?.getOrNull(currentStepIndex + 1)

    /**
     * Findet das nächste relevante Manöver (überspringt "Geradeaus" etc.)
     * Gibt den Index des Steps zurück oder null wenn keins gefunden.
     */
    fun findNextRelevantStepIndex(): Int? {
        val steps = route?.steps ?: return null
        for (i in currentStepIndex until steps.size) {
            if (steps[i].isRelevantManeuver()) {
                return i
            }
        }
        return null
    }

    /**
     * Gibt das nächste relevante Manöver zurück (überspringt "Geradeaus" etc.)
     */
    val nextRelevantStep: RouteStep?
        get() {
            val index = findNextRelevantStepIndex() ?: return null
            return route?.steps?.getOrNull(index)
        }
}

sealed class NavigationEvent {
    data class Instruction(val text: String) : NavigationEvent()
    data class StepChanged(val step: RouteStep) : NavigationEvent()
    object Recalculating : NavigationEvent()
    object Arrived : NavigationEvent()
}

/**
 * Prüft ob dieser Schritt ein relevantes Manöver ist, das angesagt werden soll.
 * "Geradeaus fahren" und ähnliche werden übersprungen.
 */
fun RouteStep.isRelevantManeuver(): Boolean {
    return when (maneuver.type) {
        // Relevante Manöver (werden angesagt)
        "turn", "roundabout", "rotary", "exit roundabout", "exit rotary",
        "fork", "merge", "on ramp", "off ramp", "arrive", "depart" -> true

        // Nicht relevante Manöver (werden übersprungen)
        "continue", "new name", "notification" -> false

        // Unbekannte Typen: nur ansagen wenn sie eine Richtung haben
        else -> maneuver.modifier != null && maneuver.modifier != "straight"
    }
}

fun RouteStep.toGermanInstruction(): String {
    val directionText = when (maneuver.type) {
        "depart" -> "Los geht's"
        "arrive" -> "Ziel erreicht"
        "turn" -> when (maneuver.modifier) {
            "left" -> "Links abbiegen"
            "right" -> "Rechts abbiegen"
            "slight left" -> "Leicht links halten"
            "slight right" -> "Leicht rechts halten"
            "sharp left" -> "Scharf links abbiegen"
            "sharp right" -> "Scharf rechts abbiegen"
            "uturn" -> "Bitte wenden"
            else -> "Abbiegen"
        }
        "continue" -> "Weiter geradeaus"
        "merge" -> "Einfädeln"
        "on ramp", "off ramp" -> "Ausfahrt nehmen"
        "fork" -> when (maneuver.modifier) {
            "left" -> "Links halten"
            "right" -> "Rechts halten"
            else -> "Gabelung"
        }
        "roundabout", "rotary" -> "Im Kreisverkehr"
        "exit roundabout", "exit rotary" -> "Kreisverkehr verlassen"
        "new name" -> "Weiter auf der Straße"
        else -> "Weiter"
    }

    // Nur deutsche Straßennamen anhängen (keine englischen OSRM/HERE Instruktionen)
    // Ein Name gilt als deutsch wenn er keine typischen englischen Phrasen enthält
    val streetName = instruction.takeIf { name ->
        name.isNotBlank() &&
        !name.contains("go ", ignoreCase = true) &&
        !name.contains("turn ", ignoreCase = true) &&
        !name.contains("continue ", ignoreCase = true) &&
        !name.contains("head ", ignoreCase = true) &&
        !name.contains(" for ", ignoreCase = true) &&
        !name.contains(" on ", ignoreCase = true) &&
        !name.contains(" onto ", ignoreCase = true) &&
        !name.matches(Regex(".*\\d+\\s*(m|km|meters|kilometers).*", RegexOption.IGNORE_CASE))
    }

    return if (streetName != null) {
        "$directionText auf $streetName"
    } else {
        directionText
    }
}

/**
 * Formatiert eine Distanz für die Anzeige mit intelligenter Rundung.
 * - Unter 100m: auf 10m runden (10, 20, 30, ... 90m)
 * - 100-500m: auf 50m runden (100, 150, 200, ... 500m)
 * - 500m-1km: auf 100m runden (500, 600, ... 1000m)
 * - Über 1km: auf 0.1km Genauigkeit
 */
fun Double.formatDistance(): String {
    // Intelligente Rundung basierend auf Distanz
    val rounded = when {
        this < 100 -> (this / 10).toInt() * 10.0
        this < 500 -> (this / 50).toInt() * 50.0
        this < 1000 -> (this / 100).toInt() * 100.0
        else -> this  // Über 1km: keine Rundung der Meter, km-Formatierung macht das
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

fun Double.formatDuration(): String {
    val minutes = (this / 60).toInt()
    val hours = minutes / 60
    val remainingMinutes = minutes % 60

    return if (hours > 0) {
        "$hours Std. $remainingMinutes Min."
    } else {
        "$minutes Min."
    }
}
