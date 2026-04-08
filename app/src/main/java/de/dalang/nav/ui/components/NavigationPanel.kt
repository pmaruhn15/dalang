package de.dalang.nav.ui.components

import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import kotlin.math.roundToInt

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
    val currentStep = state.currentStep

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
        // Lane-Visualisierung + Distanz (immer sichtbar)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lane-Anzeige wenn verfügbar, sonst Richtungspfeil
            if (currentStep?.laneInfo != null && currentStep.laneInfo.lanes.isNotEmpty()) {
                LaneGuidancePanel(
                    laneInfo = currentStep.laneInfo,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Fallback: Richtungspfeil
                Image(
                    painter = painterResource(id = getTurnIconRes(currentStep)),
                    contentDescription = "Richtung",
                    modifier = Modifier.size(64.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Distanz
            Text(
                text = state.distanceToNextStep.formatDistance(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.weight(1f))

            // Ankunftszeit prominent + Restinfos darunter
            Column(horizontalAlignment = Alignment.End) {
                // ETA berechnen - immer aktuelle Zeit + verbleibende Zeit
                // derivedStateOf statt remember damit sich die ETA bei jedem Update aktualisiert
                val etaFormat = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }
                val etaString = remember(state.totalTimeRemaining) {
                    val eta = Calendar.getInstance().apply {
                        add(Calendar.SECOND, state.totalTimeRemaining.toInt())
                    }
                    etaFormat.format(eta.time)
                }

                // Ankunftszeit GROSS
                Text(
                    text = etaString,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Restdistanz und -zeit klein darunter
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
        "roundabout", "rotary", "exit roundabout", "exit rotary" -> R.drawable.ic_turn_right
        else -> R.drawable.ic_turn_straight
    }
}
