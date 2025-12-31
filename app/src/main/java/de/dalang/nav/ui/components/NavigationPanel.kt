package de.dalang.nav.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dalang.nav.R
import de.dalang.nav.navigation.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NavigationPanel(
    state: NavigationState,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                .padding(20.dp)
        ) {
            if (state.isNavigating) {
                // Aktive Navigation
                ActiveNavigationContent(
                    state = state,
                    onStop = onStopNavigation
                )
            } else {
                // Routenvorschau
                RoutePreviewContent(
                    state = state,
                    onStart = onStartNavigation
                )
            }
        }
    }
}

@Composable
private fun ActiveNavigationContent(
    state: NavigationState,
    onStop: () -> Unit
) {
    val currentStep = state.currentStep

    // Lane-Visualisierung oder Fallback auf Richtungspfeil
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
                colorFilter = ColorFilter.tint(Color.White)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Distanz
        Text(
            text = state.distanceToNextStep.formatDistance(),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Verbleibende Route
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = state.totalDistanceRemaining.formatDistance(),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Verbleibend",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state.totalTimeRemaining.formatDuration(),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Ankunft",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Stop-Button - weiß mit schwarzer Schrift
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black
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

@Composable
private fun RoutePreviewContent(
    state: NavigationState,
    onStart: () -> Unit
) {
    val route = state.route ?: return

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
                color = Color.White
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Start-Button - weiß mit schwarzer Schrift
    Button(
        onClick = onStart,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black
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

// Lane-Guidance Panel
@Composable
private fun LaneGuidancePanel(
    laneInfo: LaneInfo,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        laneInfo.lanes.forEachIndexed { index, lane ->
            LaneIndicator(
                lane = lane,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            // Trennlinie zwischen Spuren (außer nach der letzten)
            if (index < laneInfo.lanes.size - 1) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                )
            }
        }
    }
}

@Composable
private fun LaneIndicator(
    lane: Lane,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (lane.isRecommended) {
        Color.White
    } else {
        Color.Transparent
    }
    val iconTint = if (lane.isRecommended) {
        Color.Black
    } else {
        Color.White.copy(alpha = 0.5f)
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .then(
                if (!lane.isRecommended) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = getLaneIconRes(lane.direction)),
            contentDescription = lane.direction,
            modifier = Modifier.size(32.dp),
            colorFilter = ColorFilter.tint(iconTint)
        )
    }
}

// Lane-Richtung zu Icon mappen
private fun getLaneIconRes(direction: String): Int {
    return when (direction.lowercase()) {
        "straight", "through" -> R.drawable.ic_turn_straight
        "left", "sharpleft", "sharp left" -> R.drawable.ic_turn_left
        "right", "sharpright", "sharp right" -> R.drawable.ic_turn_right
        "slightleft", "slight left", "slightlyLeft" -> R.drawable.ic_turn_slight_left
        "slightright", "slight right", "slightlyRight" -> R.drawable.ic_turn_slight_right
        "uturn", "uturnleft", "uturnright" -> R.drawable.ic_turn_uturn
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
