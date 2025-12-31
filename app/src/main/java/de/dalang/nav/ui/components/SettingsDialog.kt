package de.dalang.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.settings.MapColor
import de.dalang.nav.settings.MapStyle
import de.dalang.nav.settings.SettingsRepository

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    var selectedMapStyle by remember { mutableStateOf(settingsRepository.mapStyle) }
    var selectedRouteColor by remember { mutableStateOf(settingsRepository.routeColor) }
    var selectedMarkerColor by remember { mutableStateOf(settingsRepository.markerColor) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Einstellungen",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ========== KARTENSTIL SECTION ==========
            SectionHeader("Kartenstil")

            Spacer(modifier = Modifier.height(12.dp))

            // Style Grid mit Vorschau
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapStyle.entries.chunked(2).forEach { rowStyles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowStyles.forEach { style ->
                            StylePreviewCard(
                                style = style,
                                isSelected = style == selectedMapStyle,
                                onClick = { selectedMapStyle = style },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Fill remaining space if odd number
                        if (rowStyles.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ========== FARBEN SECTION ==========
            SectionHeader("Farben")

            Spacer(modifier = Modifier.height(12.dp))

            // Route Color
            Text(
                text = "Routenfarbe",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorSelector(
                selectedColor = selectedRouteColor,
                onColorSelected = { selectedRouteColor = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Marker Color
            Text(
                text = "Markerfarbe",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorSelector(
                selectedColor = selectedMarkerColor,
                onColorSelected = { selectedMarkerColor = it }
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tipp: Dunkle Farben für helle Karten, helle für dunkle",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ========== BUTTONS ==========
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abbrechen")
                }

                Button(
                    onClick = {
                        settingsRepository.mapStyle = selectedMapStyle
                        settingsRepository.routeColor = selectedRouteColor
                        settingsRepository.markerColor = selectedMarkerColor
                        onSave()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Speichern")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun StylePreviewCard(
    style: MapStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = Color(style.previewBgColor)
    val fgColor = Color(style.previewFgColor)
    val borderColor = if (isSelected) Color.White else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .background(bgColor)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mini-Map Preview (stilisiert)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            // Straßen-Linien als Vorschau
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(fgColor)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(fgColor)
            )
            // Diagonale
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .align(Alignment.TopEnd)
                    .background(fgColor.copy(alpha = 0.3f))
                    .clip(RoundedCornerShape(topEnd = 4.dp))
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = style.displayName,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (style.isDark) Color.White else Color.Black
        )

        Text(
            text = style.description,
            fontSize = 10.sp,
            color = if (style.isDark) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ColorSelector(
    selectedColor: MapColor,
    onColorSelected: (MapColor) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MapColor.entries.forEach { color ->
            val isSelected = color == selectedColor
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(color.colorValue))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) Color.White else Color.Gray.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(color) }
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (color == MapColor.WHITE) Color.Black else Color.White)
                            .align(Alignment.Center)
                    )
                }
            }
        }
    }
}
