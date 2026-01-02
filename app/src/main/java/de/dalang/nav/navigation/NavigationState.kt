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
}

sealed class NavigationEvent {
    data class Instruction(val text: String) : NavigationEvent()
    data class StepChanged(val step: RouteStep) : NavigationEvent()
    object Recalculating : NavigationEvent()
    object Arrived : NavigationEvent()
}

fun RouteStep.toGermanInstruction(): String {
    val directionText = when (maneuver.type) {
        "depart" -> "Starten Sie"
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
        else -> maneuver.type
    }

    val streetName = if (instruction.isNotBlank()) " auf $instruction" else ""
    return "$directionText$streetName"
}

fun Double.formatDistance(): String {
    return if (this >= 1000) {
        String.format("%.1f km", this / 1000)
    } else {
        String.format("%d m", this.toInt())
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
