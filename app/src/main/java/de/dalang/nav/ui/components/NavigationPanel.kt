package de.dalang.nav.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dalang.nav.navigation.*

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

    // Nächste Anweisung
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Richtungspfeil
        Text(
            text = currentStep?.maneuver?.toEmoji() ?: "➡️",
            fontSize = 48.sp,
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.distanceToNextStep.formatDistance(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = currentStep?.toGermanInstruction() ?: "",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

    // Stop-Button
    Button(
        onClick = onStop,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFE53935)
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

    // Ziel
    Text(
        text = state.destinationName ?: "Ziel",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Route-Info
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "📏", fontSize = 16.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = route.distance.formatDistance(),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "⏱️", fontSize = 16.sp)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = route.duration.formatDuration(),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Start-Button
    Button(
        onClick = onStart,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
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

private fun Maneuver.toEmoji(): String {
    return when (type) {
        "turn" -> when (modifier) {
            "left" -> "⬅️"
            "right" -> "➡️"
            "slight left" -> "↖️"
            "slight right" -> "↗️"
            "sharp left" -> "⤴️"
            "sharp right" -> "⤵️"
            "uturn" -> "↩️"
            else -> "➡️"
        }
        "continue", "depart" -> "⬆️"
        "arrive" -> "🏁"
        "roundabout", "rotary" -> "🔄"
        "merge" -> "↘️"
        "fork" -> when (modifier) {
            "left" -> "↖️"
            "right" -> "↗️"
            else -> "⬆️"
        }
        else -> "➡️"
    }
}
