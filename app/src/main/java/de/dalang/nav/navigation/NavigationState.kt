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
     *
     * Why: step.maneuver.location liegt am ANFANG des Steps. currentStepIndex
     * zeigt auf den Step durch dessen Geometrie wir gerade fahren — sein
     * Manöver liegt also hinter uns. Das nächste anstehende Manöver beginnt
     * bei currentStepIndex + 1.
     */
    fun findNextRelevantStepIndex(): Int? {
        val steps = route?.steps ?: return null
        for (i in (currentStepIndex + 1) until steps.size) {
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

    /**
     * Distanz zum nächsten relevanten Manöver.
     *
     * distanceToNextStep enthält die Distanz zum Anfang von Step (currentStepIndex + 1)
     * — also zum direkt nächsten Manöver. Liegt das nächste relevante Manöver
     * weiter hinten, addiere die Längen der dazwischenliegenden Steps.
     */
    fun distanceToNextRelevantStep(): Double {
        val steps = route?.steps ?: return distanceToNextStep
        val relevantIndex = findNextRelevantStepIndex() ?: return distanceToNextStep

        // Direkt nächstes Manöver ist relevant — distanceToNextStep ist schon dorthin gemessen.
        if (relevantIndex == currentStepIndex + 1) {
            return distanceToNextStep
        }

        // Sonst: Distanz zum nächsten Manöver + Längen der Steps dazwischen.
        var totalDistance = distanceToNextStep
        for (i in (currentStepIndex + 1) until relevantIndex) {
            totalDistance += steps.getOrNull(i)?.distance ?: 0.0
        }
        return totalDistance
    }

    /**
     * Liefert den realen Drehwinkel im Kreisverkehr in Grad relativ zur Anfahrtsrichtung.
     *
     * 0° = geradeaus durch, +90° = rechts ab, -90° = links ab, ±180° = wenden.
     *
     * Why: Der bisherige Code nahm starre 90°-Quadranten an (Exit 1=rechts, 2=oben, ...),
     * was bei 3-/5-/6-Exit-Kreisverkehren oder schiefer Geometrie immer falsch war.
     * Stattdessen leiten wir die Drehung aus den Bearings der angrenzenden Step-Geometrien ab.
     *
     * Returns null wenn nicht genug Geometrie da ist oder kein Roundabout-Step ansteht.
     */
    fun computeRoundaboutTurnAngleDeg(): Float? {
        val steps = route?.steps ?: return null
        val nextIdx = findNextRelevantStepIndex() ?: return null
        val displayStep = steps.getOrNull(nextIdx) ?: return null
        if (!displayStep.isRoundaboutType()) return null

        val prevStep = steps.getOrNull(nextIdx - 1)
        val afterStep = steps.getOrNull(nextIdx + 1)

        // Anfahrt: bevorzugt das Ende des vorigen Steps, sonst der Anfang dieses Steps.
        val entryGeom = prevStep?.geometry?.takeLast(2)?.takeIf { it.size >= 2 }
            ?: displayStep.geometry.take(2).takeIf { it.size >= 2 }
            ?: return null
        // Wegfahrt: bevorzugt der Anfang des nächsten Steps, sonst das Ende dieses Steps.
        val exitGeom = afterStep?.geometry?.take(2)?.takeIf { it.size >= 2 }
            ?: displayStep.geometry.takeLast(2).takeIf { it.size >= 2 }
            ?: return null

        val bearingIn = bearingDeg(entryGeom[0], entryGeom[1])
        val bearingOut = bearingDeg(exitGeom[0], exitGeom[1])
        var diff = bearingOut - bearingIn
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        return diff.toFloat()
    }
}

private fun RouteStep.isRoundaboutType(): Boolean {
    return maneuver.type in setOf("roundabout", "rotary", "exit roundabout", "exit rotary")
}

private fun bearingDeg(from: LatLng, to: LatLng): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLng = Math.toRadians(to.lng - from.lng)
    val y = Math.sin(dLng) * Math.cos(lat2)
    val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
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
