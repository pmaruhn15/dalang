package de.dalang.nav.ui.components

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import de.dalang.nav.navigation.LatLng
import de.dalang.nav.util.CrashLogger

/**
 * Animiert die Bewegung des Ego-Markers flüssig zwischen GPS-Updates.
 * Verwendet ValueAnimator mit linearer Interpolation für smoothe Übergänge.
 */
class MarkerAnimator {

    private var currentAnimator: ValueAnimator? = null
    private var currentPosition: LatLng? = null
    private var currentRotation: Float = 0f
    private var targetRotation: Float = 0f

    // Callback für Position-Updates während der Animation
    var onPositionUpdate: ((LatLng, Float) -> Unit)? = null

    // Animation dauert 1 Sekunde (GPS kommt ca. alle 1s)
    private val animationDurationMs = 1000L

    /**
     * Animiert den Marker von der aktuellen Position zur neuen Position.
     * Die Animation wird flüssig interpoliert.
     *
     * @param newPosition Die neue Zielposition
     * @param newRotation Die neue Rotation (Bearing)
     */
    fun animateTo(newPosition: LatLng, newRotation: Float) {
        val startPosition = currentPosition
        val startRotation = currentRotation

        // Ziel-Rotation speichern
        targetRotation = newRotation

        // Erste Position - keine Animation, direkt setzen
        if (startPosition == null) {
            currentPosition = newPosition
            currentRotation = newRotation
            onPositionUpdate?.invoke(newPosition, newRotation)
            return
        }

        // Laufende Animation abbrechen
        currentAnimator?.cancel()

        // Neue Animation starten
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animationDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float

                // Position linear interpolieren
                val interpolatedLat = startPosition.lat + (newPosition.lat - startPosition.lat) * fraction
                val interpolatedLng = startPosition.lng + (newPosition.lng - startPosition.lng) * fraction
                val interpolatedPosition = LatLng(interpolatedLat, interpolatedLng)

                // Rotation interpolieren (kürzesten Weg nehmen)
                val interpolatedRotation = interpolateRotation(startRotation, newRotation, fraction)

                // Position und Rotation aktualisieren
                currentPosition = interpolatedPosition
                currentRotation = interpolatedRotation

                // Callback aufrufen für UI-Update
                onPositionUpdate?.invoke(interpolatedPosition, interpolatedRotation)
            }

            start()
        }
    }

    /**
     * Setzt die Position sofort ohne Animation (z.B. bei großen Sprüngen).
     */
    fun setPositionImmediate(position: LatLng, rotation: Float) {
        currentAnimator?.cancel()
        currentPosition = position
        currentRotation = rotation
        onPositionUpdate?.invoke(position, rotation)
    }

    /**
     * Gibt die aktuelle (möglicherweise interpolierte) Position zurück.
     */
    fun getCurrentPosition(): LatLng? = currentPosition

    /**
     * Gibt die aktuelle Rotation zurück.
     */
    fun getCurrentRotation(): Float = currentRotation

    /**
     * Stoppt alle laufenden Animationen.
     */
    fun stop() {
        currentAnimator?.cancel()
        currentAnimator = null
    }

    /**
     * Setzt den Animator zurück.
     */
    fun reset() {
        stop()
        currentPosition = null
        currentRotation = 0f
    }

    /**
     * Interpoliert zwischen zwei Rotationswinkeln und nimmt den kürzesten Weg.
     * Behandelt den Übergang von 359° zu 1° korrekt.
     */
    private fun interpolateRotation(start: Float, end: Float, fraction: Float): Float {
        var diff = end - start

        // Kürzesten Weg über 360° hinweg finden
        if (diff > 180) {
            diff -= 360
        } else if (diff < -180) {
            diff += 360
        }

        var result = start + diff * fraction

        // Normalisieren auf 0-360
        while (result < 0) result += 360
        while (result >= 360) result -= 360

        return result
    }
}
