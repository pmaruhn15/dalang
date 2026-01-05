package de.dalang.nav.navigation

import de.dalang.nav.util.CrashLogger
import kotlin.math.max

/**
 * Manages voice guidance announcements based on the OsmAnd algorithm.
 *
 * Key principles:
 * - Speed-based lead distances: distance = time × speed
 * - Three-phase announcements: PREPARE, TURN_IN, TURN_NOW
 * - Track which announcements were made to prevent repetition
 * - Minimum time between announcements
 * - Handle consecutive turns with "dann" (then) announcements
 *
 * Based on: https://osmand.net/docs/technical/algorithms/voice-prompt-triggering/
 */
class VoiceGuidanceManager {

    companion object {
        // Default speed for lead distance calculation (m/s)
        // 45 km/h = 12.5 m/s (typical city driving)
        private const val DEFAULT_SPEED_MS = 12.5f

        // Lead times in seconds for different announcement phases
        private const val LEAD_TIME_PREPARE_SECONDS = 90f    // "Demnächst" - early warning
        private const val LEAD_TIME_TURN_IN_SECONDS = 18f    // "In X Metern" - intermediate
        private const val LEAD_TIME_TURN_NOW_SECONDS = 5f    // "Jetzt" - immediate

        // Minimum distances to prevent announcements at very low speeds
        private const val MIN_DISTANCE_PREPARE = 500.0       // Don't prepare if less than 500m
        private const val MIN_DISTANCE_TURN_IN = 100.0       // "In X Metern" minimum
        private const val MIN_DISTANCE_TURN_NOW = 25.0       // "Jetzt" minimum

        // Distance below which "Turn now" is triggered
        private const val TURN_NOW_THRESHOLD = 35.0

        // Minimum time between any announcements (ms)
        private const val MIN_ANNOUNCEMENT_INTERVAL_MS = 4000L

        // Minimum time before same phase can repeat (ms)
        private const val SAME_PHASE_COOLDOWN_MS = 15000L

        // Distance threshold for consecutive turn announcement
        private const val CONSECUTIVE_TURN_DISTANCE = 150.0

        // Announced distance values (rounded)
        private val ANNOUNCE_DISTANCES = listOf(2000, 1500, 1000, 500, 300, 200, 150, 100, 80, 50)
    }

    enum class AnnouncementPhase {
        NONE,
        PREPARE,      // "Demnächst links abbiegen" (far away)
        TURN_IN,      // "In 200 Metern links abbiegen"
        TURN_NOW      // "Links abbiegen" (immediate)
    }

    data class AnnouncementState(
        val stepIndex: Int = -1,
        val lastPhase: AnnouncementPhase = AnnouncementPhase.NONE,
        val lastAnnouncedDistance: Int = -1,
        val prepareAnnounced: Boolean = false,
        val lastAnnouncementTime: Long = 0L,
        val lastPhaseTime: Long = 0L
    )

    private var state = AnnouncementState()

    /**
     * Reset state when navigation starts or route changes
     */
    fun reset() {
        state = AnnouncementState()
        CrashLogger.log("VoiceGuidanceManager: Reset")
    }

    /**
     * Called when step changes (user completed a maneuver)
     */
    fun onStepChanged(newStepIndex: Int) {
        state = AnnouncementState(
            stepIndex = newStepIndex,
            lastAnnouncementTime = System.currentTimeMillis()
        )
        CrashLogger.log("VoiceGuidanceManager: Step changed to $newStepIndex")
    }

    /**
     * Determines if and what announcement should be made.
     *
     * @param step Current navigation step
     * @param nextStep Next navigation step (for consecutive turn handling)
     * @param distanceToManeuver Distance to the maneuver point in meters
     * @param currentSpeedMs Current speed in m/s
     * @param stepIndex Current step index
     * @return The announcement text, or null if no announcement needed
     */
    fun getAnnouncement(
        step: RouteStep,
        nextStep: RouteStep?,
        distanceToManeuver: Double,
        currentSpeedMs: Float,
        stepIndex: Int
    ): String? {
        val now = System.currentTimeMillis()

        // Check minimum interval between any announcements
        if (now - state.lastAnnouncementTime < MIN_ANNOUNCEMENT_INTERVAL_MS) {
            return null
        }

        // Reset state if step changed (shouldn't happen here, but safety check)
        if (stepIndex != state.stepIndex) {
            state = state.copy(
                stepIndex = stepIndex,
                lastPhase = AnnouncementPhase.NONE,
                lastAnnouncedDistance = -1,
                prepareAnnounced = false
            )
        }

        // Calculate speed-based lead distances
        val effectiveSpeed = if (currentSpeedMs > 1f) currentSpeedMs else DEFAULT_SPEED_MS

        val prepareDist = max(MIN_DISTANCE_PREPARE, (LEAD_TIME_PREPARE_SECONDS * effectiveSpeed).toDouble())
        val turnInDist = max(MIN_DISTANCE_TURN_IN, (LEAD_TIME_TURN_IN_SECONDS * effectiveSpeed).toDouble())
        val turnNowDist = max(MIN_DISTANCE_TURN_NOW, (LEAD_TIME_TURN_NOW_SECONDS * effectiveSpeed).toDouble())

        // Determine announcement phase based on distance
        val phase = when {
            distanceToManeuver <= TURN_NOW_THRESHOLD -> AnnouncementPhase.TURN_NOW
            distanceToManeuver <= turnNowDist -> AnnouncementPhase.TURN_NOW
            distanceToManeuver <= turnInDist -> AnnouncementPhase.TURN_IN
            distanceToManeuver <= prepareDist && !state.prepareAnnounced -> AnnouncementPhase.PREPARE
            else -> AnnouncementPhase.NONE
        }

        if (phase == AnnouncementPhase.NONE) {
            return null
        }

        // Check if we already announced this phase recently
        if (phase == state.lastPhase && now - state.lastPhaseTime < SAME_PHASE_COOLDOWN_MS) {
            // For TURN_IN phase, allow announcement at different distances
            if (phase == AnnouncementPhase.TURN_IN) {
                val roundedDist = roundToAnnouncementDistance(distanceToManeuver)
                if (roundedDist == state.lastAnnouncedDistance) {
                    return null
                }
            } else {
                return null
            }
        }

        // Skip PREPARE phase if TURN_IN already announced (too close for prepare)
        if (phase == AnnouncementPhase.PREPARE &&
            (state.lastPhase == AnnouncementPhase.TURN_IN || state.lastPhase == AnnouncementPhase.TURN_NOW)) {
            return null
        }

        // Generate the announcement text
        val announcement = buildAnnouncementText(step, nextStep, distanceToManeuver, phase)

        if (announcement != null) {
            val roundedDist = roundToAnnouncementDistance(distanceToManeuver)
            state = state.copy(
                lastPhase = phase,
                lastAnnouncedDistance = roundedDist,
                prepareAnnounced = state.prepareAnnounced || phase == AnnouncementPhase.PREPARE,
                lastAnnouncementTime = now,
                lastPhaseTime = if (phase != state.lastPhase) now else state.lastPhaseTime
            )

            CrashLogger.log("VoiceGuidanceManager: Announce phase=$phase dist=${distanceToManeuver.toInt()}m -> $announcement")
        }

        return announcement
    }

    /**
     * Get announcement for step transition (when user just completed a maneuver).
     * Only announces if the next maneuver is reasonably close.
     */
    fun getStepTransitionAnnouncement(
        currentStep: RouteStep,
        nextStep: RouteStep?,
        distanceToNext: Double,
        currentSpeedMs: Float
    ): String? {
        // Don't announce the next step immediately if it's far away
        val effectiveSpeed = if (currentSpeedMs > 1f) currentSpeedMs else DEFAULT_SPEED_MS
        val maxSilentDistance = max(200.0, (15f * effectiveSpeed).toDouble())

        if (distanceToNext > maxSilentDistance) {
            CrashLogger.log("VoiceGuidanceManager: Next step too far (${distanceToNext.toInt()}m > ${maxSilentDistance.toInt()}m), not announcing")
            return null
        }

        // Announce current step (the one just passed) or immediate next
        return buildAnnouncementText(currentStep, nextStep, distanceToNext, AnnouncementPhase.TURN_NOW)
    }

    private fun buildAnnouncementText(
        step: RouteStep,
        nextStep: RouteStep?,
        distance: Double,
        phase: AnnouncementPhase
    ): String? {
        val direction = getDirectionText(step)
        if (direction.isNullOrBlank()) {
            return null
        }

        val consecutiveTurnSuffix = if (nextStep != null &&
            step.distance < CONSECUTIVE_TURN_DISTANCE &&
            getDirectionText(nextStep) != null) {
            val nextDir = getDirectionText(nextStep)
            ", dann $nextDir"
        } else {
            ""
        }

        return when (phase) {
            AnnouncementPhase.PREPARE -> {
                "Demnächst $direction$consecutiveTurnSuffix"
            }
            AnnouncementPhase.TURN_IN -> {
                val distText = roundToAnnouncementDistance(distance)
                "In $distText Metern $direction$consecutiveTurnSuffix"
            }
            AnnouncementPhase.TURN_NOW -> {
                val streetName = if (step.instruction.isNotBlank()) " auf ${step.instruction}" else ""
                "$direction$streetName$consecutiveTurnSuffix"
            }
            AnnouncementPhase.NONE -> null
        }
    }

    private fun getDirectionText(step: RouteStep): String? {
        return when (step.maneuver.type) {
            "depart" -> "Los geht's"
            "arrive" -> "Ziel erreicht"
            "turn" -> when (step.maneuver.modifier) {
                "left" -> "links abbiegen"
                "right" -> "rechts abbiegen"
                "slight left" -> "leicht links halten"
                "slight right" -> "leicht rechts halten"
                "sharp left" -> "scharf links abbiegen"
                "sharp right" -> "scharf rechts abbiegen"
                "uturn" -> "wenden"
                else -> "abbiegen"
            }
            "continue", "straight" -> "geradeaus weiter"
            "merge" -> "einfädeln"
            "on ramp", "off ramp" -> "Ausfahrt nehmen"
            "fork" -> when (step.maneuver.modifier) {
                "left" -> "links halten"
                "right" -> "rechts halten"
                else -> null
            }
            "roundabout", "rotary" -> {
                val exit = step.maneuver.exit ?: 1
                "im Kreisverkehr die ${exitOrdinal(exit)} Ausfahrt nehmen"
            }
            "exit roundabout", "exit rotary" -> "Kreisverkehr verlassen"
            else -> null  // Unknown types don't get announced
        }
    }

    private fun exitOrdinal(exit: Int): String {
        return when (exit) {
            1 -> "erste"
            2 -> "zweite"
            3 -> "dritte"
            4 -> "vierte"
            5 -> "fünfte"
            else -> "$exit."
        }
    }

    private fun roundToAnnouncementDistance(distance: Double): Int {
        // Find the closest announcement distance that's <= actual distance
        for (announceDist in ANNOUNCE_DISTANCES) {
            if (distance >= announceDist - 20) { // Small tolerance
                return announceDist
            }
        }
        return 50 // Minimum
    }
}
