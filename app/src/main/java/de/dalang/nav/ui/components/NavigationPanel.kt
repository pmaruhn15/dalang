package de.dalang.nav.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dalang.nav.R
import de.dalang.nav.navigation.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun NavigationPanel(
    state: NavigationState,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onCancelRoute: () -> Unit = {},
    onMcDonaldsClick: () -> Unit = {},
    onGasStationClick: () -> Unit = {},
    isMcDonaldsLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Get navigation bar height for bottom padding
    val navigationBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    AnimatedVisibility(
        visible = state.route != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 20.dp + navigationBarHeight)
        ) {
            if (state.isNavigating) {
                // Aktive Navigation
                ActiveNavigationContent(
                    state = state,
                    onStop = onStopNavigation,
                    onMcDonaldsClick = onMcDonaldsClick,
                    onGasStationClick = onGasStationClick,
                    isMcDonaldsLoading = isMcDonaldsLoading
                )
            } else {
                // Routenvorschau (swipe zum Abbrechen)
                RoutePreviewContent(
                    state = state,
                    onStart = onStartNavigation,
                    onCancel = onCancelRoute
                )
            }
        }
    }
}

@Composable
private fun ActiveNavigationContent(
    state: NavigationState,
    onStop: () -> Unit,
    onMcDonaldsClick: () -> Unit,
    onGasStationClick: () -> Unit,
    isMcDonaldsLoading: Boolean = false
) {
    // Zeige das nächste RELEVANTE Manöver (überspringt "Geradeaus" auf Landstraßen etc.)
    val displayStep = state.nextRelevantStep ?: state.currentStep
    val distanceToDisplay = state.distanceToNextRelevantStep()

    // Panel ausgeklappt State - standardmäßig eingeklappt
    var isExpanded by remember { mutableStateOf(false) }

    // Auto-Hide nach 5 Sekunden
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            delay(5000L)
            isExpanded = false
        }
    }

    // Klickbarer Bereich für Expand/Collapse
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                isExpanded = !isExpanded
            }
    ) {
        val hasLanes = displayStep?.laneInfo != null && displayStep.laneInfo.lanes.isNotEmpty()

        // Bei Lane-Info: eigene Zeile mit voller Breite, damit auch 4–6 Spuren reinpassen.
        if (hasLanes) {
            LaneGuidancePanel(
                laneInfo = displayStep!!.laneInfo!!,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pfeil/Roundabout nur wenn KEINE Lane-Info — sonst zeigt die Lane-Reihe oben das Manöver.
            if (!hasLanes) {
                if (displayStep.isRoundabout() && displayStep?.maneuver?.exit != null) {
                    RoundaboutVisualization(
                        exitNumber = displayStep.maneuver.exit,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    Image(
                        painter = painterResource(id = getTurnIconRes(displayStep)),
                        contentDescription = "Richtung",
                        modifier = Modifier.size(64.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Distanz zum nächsten relevanten Manöver
            Text(
                text = distanceToDisplay.formatDistance(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.weight(1f))

            // Ankunftszeit prominent + Restinfos darunter
            Column(horizontalAlignment = Alignment.End) {
                val etaFormat = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }
                val etaString = remember(state.totalTimeRemaining) {
                    val eta = Calendar.getInstance().apply {
                        add(Calendar.SECOND, state.totalTimeRemaining.toInt())
                    }
                    etaFormat.format(eta.time)
                }

                Text(
                    text = etaString,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "${state.totalDistanceRemaining.formatDistance()} • ${state.totalTimeRemaining.formatDuration()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Ausklappbarer Bereich mit Buttons
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))

                // POI-Buttons Zeile
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val buttonBg = MaterialTheme.colorScheme.onSurface
                    val buttonFg = MaterialTheme.colorScheme.surface

                    // McDonald's Button
                    Button(
                        onClick = {
                            onMcDonaldsClick()
                            isExpanded = false
                        },
                        enabled = !isMcDonaldsLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonBg,
                            contentColor = buttonFg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isMcDonaldsLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = buttonFg,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_mcdonalds),
                                contentDescription = "McDonald's",
                                modifier = Modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(buttonFg)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("McDonald's")
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Tankstelle Button
                    Button(
                        onClick = {
                            onGasStationClick()
                            isExpanded = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonBg,
                            contentColor = buttonFg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_gas_station),
                            contentDescription = "Tankstelle",
                            modifier = Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(buttonFg)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tankstelle")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stop-Button
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Navigation beenden",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutePreviewContent(
    state: NavigationState,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val route = state.route ?: return

    // Swipe-Offset State
    var offsetX by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 150f  // Pixel zum Auslösen des Abbrechens

    // ETA berechnen
    val eta = Calendar.getInstance().apply {
        add(Calendar.SECOND, route.duration.toInt())
    }
    val etaFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)
    val etaString = etaFormat.format(eta.time)

    // Traffic delay
    val trafficDelay = if (route.hasTrafficData && route.typicalDuration != null) {
        (route.duration - route.typicalDuration).toInt()
    } else 0

    Column(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(offsetX) > swipeThreshold) {
                            onCancel()
                        }
                        offsetX = 0f
                    },
                    onDragCancel = {
                        offsetX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX += dragAmount
                    }
                )
            }
    ) {
        // Ziel
        Text(
            text = state.destinationName ?: "Ziel",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Route-Info mit ETA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Distanz
            Column {
                Text(
                    text = route.distance.formatDistance(),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = route.duration.formatDuration(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ETA mit Verkehr-Delay neben der Zeit
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = etaString,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Immer Delay anzeigen
                val delayMinutes = trafficDelay / 60
                Text(
                    text = if (delayMinutes > 0) "+$delayMinutes" else "+0",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Start-Button - Farben passen sich an Theme an
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Navigation starten",
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

// Lane-Guidance Panel - Google-Style: Pfeile pro Spur, empfohlene dicker
@Composable
private fun LaneGuidancePanel(
    laneInfo: LaneInfo,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        laneInfo.lanes.forEachIndexed { index, lane ->
            LaneIndicator(
                lane = lane,
                modifier = Modifier.padding(horizontal = 1.dp)
            )
            // Dünne Trennlinie zwischen Spuren
            if (index < laneInfo.lanes.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )
            }
        }
    }
}

// Google-Style Lane Indicator: Pfeile pro Spur, mehrere Richtungen möglich
@Composable
private fun LaneIndicator(
    lane: Lane,
    modifier: Modifier = Modifier
) {
    // Empfohlene Spur: größer, voll sichtbar
    // Andere Spuren: kleiner, transparent
    val baseIconSize = if (lane.isRecommended) 40.dp else 28.dp
    val iconAlpha = if (lane.isRecommended) 1f else 0.4f
    val iconColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .width(48.dp)
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        // Bei mehreren Richtungen: Pfeile nebeneinander/übereinander zeigen
        if (lane.directions.size == 1) {
            // Einzelne Richtung - einfach zentriert
            Image(
                painter = painterResource(id = getLaneIconRes(lane.directions.first())),
                contentDescription = lane.directions.first(),
                modifier = Modifier.size(baseIconSize),
                colorFilter = ColorFilter.tint(iconColor.copy(alpha = iconAlpha))
            )
        } else {
            // Mehrere Richtungen - kompakt nebeneinander
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val smallIconSize = if (lane.isRecommended) 24.dp else 18.dp
                lane.directions.take(3).forEach { direction ->  // Max 3 Richtungen anzeigen
                    Image(
                        painter = painterResource(id = getLaneIconRes(direction)),
                        contentDescription = direction,
                        modifier = Modifier.size(smallIconSize),
                        colorFilter = ColorFilter.tint(iconColor.copy(alpha = iconAlpha))
                    )
                }
            }
        }
    }
}

// Lane-Richtung zu Icon mappen
// Richtungen kommen aus mapOsrmLaneDirection: straight, left, right, slightLeft, slightRight, sharpLeft, sharpRight, uTurn, mergeLeft, mergeRight
private fun getLaneIconRes(direction: String): Int {
    return when (direction.lowercase()) {
        "straight", "through", "none" -> R.drawable.ic_turn_straight
        "left" -> R.drawable.ic_turn_left
        "right" -> R.drawable.ic_turn_right
        "slightleft", "slight_left" -> R.drawable.ic_turn_slight_left
        "slightright", "slight_right" -> R.drawable.ic_turn_slight_right
        "sharpleft", "sharp_left" -> R.drawable.ic_turn_sharp_left
        "sharpright", "sharp_right" -> R.drawable.ic_turn_sharp_right
        "uturn", "uturnleft", "uturnright", "reverse" -> R.drawable.ic_turn_uturn
        "mergeleft", "merge_to_left" -> R.drawable.ic_lane_merge_left
        "mergeright", "merge_to_right" -> R.drawable.ic_lane_merge_right
        else -> R.drawable.ic_turn_straight
    }
}

// Hilfsfunktion für Richtungspfeile (Fallback)
private fun getTurnIconRes(step: RouteStep?): Int {
    if (step == null) return R.drawable.ic_turn_straight

    return when (step.maneuver.type) {
        "depart", "continue" -> R.drawable.ic_turn_straight
        "arrive" -> R.drawable.ic_destination_flag
        "turn" -> when (step.maneuver.modifier) {
            "left", "sharp left" -> R.drawable.ic_turn_left
            "right", "sharp right" -> R.drawable.ic_turn_right
            "slight left" -> R.drawable.ic_turn_slight_left
            "slight right" -> R.drawable.ic_turn_slight_right
            "uturn" -> R.drawable.ic_turn_uturn
            else -> R.drawable.ic_turn_straight
        }
        "fork" -> when (step.maneuver.modifier) {
            "left" -> R.drawable.ic_turn_slight_left
            "right" -> R.drawable.ic_turn_slight_right
            else -> R.drawable.ic_turn_straight
        }
        // Kreisverkehr: Fallback auf Icon wenn keine Exit-Nummer
        "roundabout", "rotary", "exit roundabout", "exit rotary" -> R.drawable.ic_roundabout
        else -> R.drawable.ic_turn_straight
    }
}

// Prüft ob ein Schritt ein Kreisverkehr-Manöver ist
private fun RouteStep?.isRoundabout(): Boolean {
    return this?.maneuver?.type in listOf("roundabout", "rotary", "exit roundabout", "exit rotary")
}

/**
 * Dynamische Kreisverkehr-Visualisierung
 * Zeigt den Kreisverkehr mit der korrekten Ausfahrt an
 *
 * @param exitNumber Die Ausfahrt (1 = erste Ausfahrt rechts, 2 = zweite, etc.)
 * @param color Die Farbe für die Darstellung
 */
@Composable
private fun RoundaboutVisualization(
    exitNumber: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(64.dp)) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val outerRadius = size.minDimension / 2 - 4.dp.toPx()
        val innerRadius = outerRadius * 0.5f
        val roadWidth = 8.dp.toPx()
        val arrowSize = 10.dp.toPx()

        // Kreisverkehr-Ring (Donut)
        drawCircle(
            color = color,
            radius = outerRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = roadWidth)
        )

        // Innerer Kreis (Insel)
        drawCircle(
            color = color.copy(alpha = 0.3f),
            radius = innerRadius,
            center = Offset(centerX, centerY)
        )

        // Einfahrt von unten (immer)
        drawLine(
            color = color,
            start = Offset(centerX, size.height),
            end = Offset(centerX, centerY + outerRadius - roadWidth / 2),
            strokeWidth = roadWidth,
            cap = StrokeCap.Round
        )

        // Ausfahrt basierend auf Exit-Nummer
        // In Deutschland: Kreisverkehr im Uhrzeigersinn
        // Exit 1 = erste Ausfahrt (ca. 90° = rechts)
        // Exit 2 = zweite Ausfahrt (ca. 0° = oben)
        // Exit 3 = dritte Ausfahrt (ca. 270° = links)
        // Exit 4 = vierte Ausfahrt (ca. 180° = zurück/unten)

        // Winkel für Ausfahrt berechnen (0° = oben, im Uhrzeigersinn)
        // Einfahrt ist bei 180° (unten)
        // Exit 1 = 90° (rechts), Exit 2 = 0° (oben), Exit 3 = 270° (links)
        val exitAngle = when (exitNumber) {
            1 -> 90f   // Rechts
            2 -> 0f    // Oben (geradeaus durch)
            3 -> 270f  // Links
            4 -> 180f  // Zurück (U-Turn)
            else -> ((exitNumber - 1) * 90f) % 360f
        }

        val exitAngleRad = Math.toRadians(exitAngle.toDouble())
        val exitX = centerX + (outerRadius * sin(exitAngleRad)).toFloat()
        val exitY = centerY - (outerRadius * cos(exitAngleRad)).toFloat()

        // Ausfahrtlinie
        val exitEndX = centerX + ((outerRadius + 20.dp.toPx()) * sin(exitAngleRad)).toFloat()
        val exitEndY = centerY - ((outerRadius + 20.dp.toPx()) * cos(exitAngleRad)).toFloat()

        drawLine(
            color = color,
            start = Offset(exitX, exitY),
            end = Offset(exitEndX, exitEndY),
            strokeWidth = roadWidth,
            cap = StrokeCap.Round
        )

        // Pfeilspitze an der Ausfahrt
        val arrowAngleRad = exitAngleRad
        val arrowTipX = exitEndX
        val arrowTipY = exitEndY

        // Pfeilflügel
        val wingAngle1 = arrowAngleRad + Math.toRadians(150.0)
        val wingAngle2 = arrowAngleRad - Math.toRadians(150.0)

        val wing1X = arrowTipX + (arrowSize * sin(wingAngle1)).toFloat()
        val wing1Y = arrowTipY - (arrowSize * cos(wingAngle1)).toFloat()
        val wing2X = arrowTipX + (arrowSize * sin(wingAngle2)).toFloat()
        val wing2Y = arrowTipY - (arrowSize * cos(wingAngle2)).toFloat()

        val arrowPath = Path().apply {
            moveTo(arrowTipX, arrowTipY)
            lineTo(wing1X, wing1Y)
            lineTo(wing2X, wing2Y)
            close()
        }
        drawPath(arrowPath, color = color)

        // Richtungspfeil auf dem Ring (im Uhrzeigersinn)
        // Kleiner Pfeil bei 45° um Fahrtrichtung anzuzeigen
        val indicatorAngle = Math.toRadians(135.0) // Zwischen Einfahrt und erster Ausfahrt
        val indicatorX = centerX + ((outerRadius) * sin(indicatorAngle)).toFloat()
        val indicatorY = centerY - ((outerRadius) * cos(indicatorAngle)).toFloat()

        // Kleiner Richtungspfeil im Uhrzeigersinn
        val smallArrowAngle = indicatorAngle + Math.toRadians(90.0) // Tangential
        val smallArrowSize = 6.dp.toPx()
        val smallWing1 = smallArrowAngle + Math.toRadians(140.0)
        val smallWing2 = smallArrowAngle - Math.toRadians(140.0)

        val smallPath = Path().apply {
            moveTo(
                indicatorX + (smallArrowSize * sin(smallArrowAngle)).toFloat(),
                indicatorY - (smallArrowSize * cos(smallArrowAngle)).toFloat()
            )
            lineTo(
                indicatorX + (smallArrowSize * sin(smallWing1)).toFloat(),
                indicatorY - (smallArrowSize * cos(smallWing1)).toFloat()
            )
            lineTo(
                indicatorX + (smallArrowSize * sin(smallWing2)).toFloat(),
                indicatorY - (smallArrowSize * cos(smallWing2)).toFloat()
            )
            close()
        }
        drawPath(smallPath, color = color)
    }
}
