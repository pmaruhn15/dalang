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

    // Nächste Anweisung
    Column(modifier = Modifier.fillMaxWidth()) {
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

        // ETA mit Verkehr
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = etaString,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (trafficDelay > 60) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "+${(trafficDelay / 60)} Min",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE53935)
                )
            }
        }
    }

    // Traffic Indikator
    if (route.hasTrafficData) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (trafficDelay > 60) "Verkehr auf der Strecke" else "Verkehr: gut",
            fontSize = 11.sp,
            color = if (trafficDelay > 60) Color(0xFFE53935) else Color(0xFF43A047)
        )
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
