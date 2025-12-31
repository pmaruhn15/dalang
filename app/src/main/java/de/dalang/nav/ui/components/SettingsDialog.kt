package de.dalang.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.config.HereConfig
import de.dalang.nav.settings.ApiUsageInfo
import de.dalang.nav.settings.MapStyle
import de.dalang.nav.settings.PeriodType
import de.dalang.nav.settings.SettingsRepository
import de.dalang.nav.settings.UsageStatus
import java.text.NumberFormat
import java.util.Locale

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }

    var hereApiKey by remember { mutableStateOf(HereConfig.getApiKey()) }
    var showApiKey by remember { mutableStateOf(false) }
    var monthlyLimit by remember { mutableStateOf(settingsRepository.hereMonthlyLimit.toString()) }
    var selectedMapStyle by remember { mutableStateOf(settingsRepository.mapStyle) }
    var mapStyleExpanded by remember { mutableStateOf(false) }

    val usageInfos = remember { settingsRepository.getAllApiUsageInfos() }
    val numberFormat = remember { NumberFormat.getNumberInstance(Locale.GERMANY) }

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

            Spacer(modifier = Modifier.height(20.dp))

            // Map Style Section
            Text(
                text = "Kartenstil",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // Dropdown Button
                TextButton(
                    onClick = { mapStyleExpanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    Text(
                        text = selectedMapStyle.displayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "▼",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                DropdownMenu(
                    expanded = mapStyleExpanded,
                    onDismissRequest = { mapStyleExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    MapStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = style.displayName,
                                    color = if (style == selectedMapStyle) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            },
                            onClick = {
                                selectedMapStyle = style
                                mapStyleExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (selectedMapStyle == MapStyle.AUTO) {
                    "Wechselt automatisch zwischen Hell/Dunkel"
                } else {
                    "Fester Kartenstil"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // HERE API Key Section
            Text(
                text = "HERE API Key",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Fuer Echtzeit-Verkehrsdaten:\nplatform.here.com > Projects > Create > API Keys",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // API Key Input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (hereApiKey.isEmpty()) {
                            Text(
                                text = "API Key eingeben...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }

                        BasicTextField(
                            value = hereApiKey,
                            onValueChange = { hereApiKey = it },
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            singleLine = true,
                            visualTransformation = if (showApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Show/Hide Toggle
                    TextButton(
                        onClick = { showApiKey = !showApiKey },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = if (showApiKey) "Verbergen" else "Zeigen",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // API Usage Stats Section (wenn APIs konfiguriert sind)
            if (usageInfos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "API Nutzung",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                usageInfos.forEach { info ->
                    ApiUsageCard(
                        info = info,
                        numberFormat = numberFormat
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Limit Settings
                Text(
                    text = "Monatslimit",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "HERE Free Tier: 250.000/Monat",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = monthlyLimit,
                        onValueChange = { monthlyLimit = it.filter { c -> c.isDigit() } },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bei Limit: Fallback auf OSRM ohne Verkehrsdaten",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (hereApiKey.isBlank()) {
                // Status wenn kein Key
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ohne Key: Keine Echtzeit-Verkehrsdaten",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Buttons
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
                        HereConfig.setApiKey(hereApiKey.trim())
                        monthlyLimit.toIntOrNull()?.let {
                            settingsRepository.hereMonthlyLimit = it
                        }
                        settingsRepository.mapStyle = selectedMapStyle
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
private fun ApiUsageCard(
    info: ApiUsageInfo,
    numberFormat: NumberFormat
) {
    val barColor = when (info.status) {
        UsageStatus.BLOCKED -> Color(0xFFE53935)  // Rot
        UsageStatus.WARNING -> Color(0xFFFF9800)  // Orange
        UsageStatus.OK -> Color(0xFF4CAF50)       // Grün
    }

    val statusText = when (info.status) {
        UsageStatus.BLOCKED -> "Limit erreicht!"
        UsageStatus.WARNING -> "Warnung"
        UsageStatus.OK -> "OK"
    }

    val periodText = when (info.periodType) {
        PeriodType.MONTHLY -> info.periodStart
        PeriodType.DAILY -> "Heute"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = info.apiName,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = barColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (info.percentage / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(barColor)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${numberFormat.format(info.used)} / ${numberFormat.format(info.limit)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = periodText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Remaining
        Text(
            text = "${numberFormat.format(info.remaining)} verbleibend (${String.format("%.1f", info.percentage)}%)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Warning bei Limit erreicht
        if (info.status == UsageStatus.BLOCKED) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Fallback auf OSRM aktiv (ohne Verkehr)",
                fontSize = 11.sp,
                color = barColor
            )
        }
    }
}
