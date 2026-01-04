package de.dalang.nav.location

import de.dalang.nav.navigation.LatLng
import de.dalang.nav.navigation.Route
import kotlin.math.sqrt

/**
 * Map Matching - Positioniert den Ego-Marker auf der Route statt auf der rohen GPS-Position.
 * Dies verhindert dass der Marker neben der Straße angezeigt wird.
 */
class MapMatcher {

    private var lastMatchedIndex = 0

    /**
     * Findet den nächsten Punkt auf der Route zur gegebenen GPS-Position.
     * Gibt die gematchte Position und die Distanz zur Route zurück.
     *
     * @param location Die aktuelle GPS-Position
     * @param route Die aktuelle Route
     * @param maxSnapDistance Maximale Distanz in Metern zum Snappen (default 30m)
     * @return Pair von (gematchte Position, Distanz zur Route) oder null wenn zu weit weg
     */
    fun matchToRoute(location: LatLng, route: Route, maxSnapDistance: Double = 30.0): MatchResult {
        val geometry = route.geometry
        if (geometry.isEmpty()) {
            return MatchResult(location, Double.MAX_VALUE, -1)
        }

        // Suche nur im Bereich um den letzten Match-Index für Performance
        // (wir fahren ja vorwärts auf der Route)
        val searchStart = (lastMatchedIndex - 5).coerceAtLeast(0)
        val searchEnd = (lastMatchedIndex + 100).coerceAtMost(geometry.size - 1)

        var minDistance = Double.MAX_VALUE
        var nearestIndex = lastMatchedIndex
        var nearestPoint = location

        // Finde den nächsten Liniensegment-Punkt (nicht nur Stützpunkte)
        for (i in searchStart until searchEnd) {
            val p1 = geometry[i]
            val p2 = if (i + 1 < geometry.size) geometry[i + 1] else geometry[i]

            // Projektion auf Liniensegment
            val projected = projectPointOnSegment(location, p1, p2)
            val distance = location.distanceTo(projected)

            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = i
                nearestPoint = projected
            }
        }

        // Falls nicht im lokalen Bereich gefunden, suche global
        if (minDistance > maxSnapDistance * 2) {
            for (i in geometry.indices) {
                if (i in searchStart..searchEnd) continue

                val p1 = geometry[i]
                val p2 = if (i + 1 < geometry.size) geometry[i + 1] else geometry[i]

                val projected = projectPointOnSegment(location, p1, p2)
                val distance = location.distanceTo(projected)

                if (distance < minDistance) {
                    minDistance = distance
                    nearestIndex = i
                    nearestPoint = projected
                }
            }
        }

        // Update last matched index (nur vorwärts, oder bei großem Sprung)
        if (nearestIndex >= lastMatchedIndex || nearestIndex < lastMatchedIndex - 20) {
            lastMatchedIndex = nearestIndex
        }

        // Wenn innerhalb Snap-Distanz, gib gematchte Position zurück
        val matchedLocation = if (minDistance <= maxSnapDistance) {
            nearestPoint
        } else {
            location  // Zu weit weg, behalte Original
        }

        return MatchResult(matchedLocation, minDistance, nearestIndex)
    }

    /**
     * Projiziert einen Punkt auf ein Liniensegment.
     */
    private fun projectPointOnSegment(point: LatLng, segmentStart: LatLng, segmentEnd: LatLng): LatLng {
        val dx = segmentEnd.lng - segmentStart.lng
        val dy = segmentEnd.lat - segmentStart.lat

        if (dx == 0.0 && dy == 0.0) {
            // Segment ist ein Punkt
            return segmentStart
        }

        // Parameter t für die Projektion (0 = Start, 1 = Ende)
        val t = ((point.lng - segmentStart.lng) * dx + (point.lat - segmentStart.lat) * dy) /
                (dx * dx + dy * dy)

        // Clampen auf [0, 1] damit wir auf dem Segment bleiben
        val clampedT = t.coerceIn(0.0, 1.0)

        return LatLng(
            lat = segmentStart.lat + clampedT * dy,
            lng = segmentStart.lng + clampedT * dx
        )
    }

    /**
     * Setzt den Matcher zurück (z.B. bei neuer Route)
     */
    fun reset() {
        lastMatchedIndex = 0
    }

    data class MatchResult(
        val location: LatLng,
        val distanceToRoute: Double,
        val segmentIndex: Int
    )
}
