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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.config.HereConfig
import de.dalang.nav.settings.SettingsRepository

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var hereApiKey by remember { mutableStateOf(HereConfig.getApiKey()) }
    var showApiKey by remember { mutableStateOf(false) }
    var dailyLimit by remember { mutableStateOf(HereConfig.getDailyLimit().toString()) }
    var warningThreshold by remember { mutableStateOf(HereConfig.getWarningThreshold().toString()) }

    val todayUsage = remember { HereConfig.getTodayUsage() }
    val usageStatus = remember { HereConfig.getUsageStatus() }

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

            // Usage Stats (nur wenn API Key vorhanden)
            if (hereApiKey.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "API Nutzung heute",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Usage Bar
                val currentLimit = dailyLimit.toIntOrNull() ?: SettingsRepository.DEFAULT_DAILY_LIMIT
                val usagePercent = (todayUsage.toFloat() / currentLimit.toFloat()).coerceIn(0f, 1f)
                val barColor = when (usageStatus) {
                    SettingsRepository.UsageStatus.BLOCKED -> Color(0xFFE53935)
                    SettingsRepository.UsageStatus.WARNING -> Color(0xFFFF9800)
                    SettingsRepository.UsageStatus.OK -> Color(0xFF43A047)
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$todayUsage / $currentLimit Anfragen",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when (usageStatus) {
                                SettingsRepository.UsageStatus.BLOCKED -> "Limit erreicht!"
                                SettingsRepository.UsageStatus.WARNING -> "Warnung"
                                SettingsRepository.UsageStatus.OK -> "OK"
                            },
                            fontSize = 14.sp,
                            color = barColor
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Progress Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(usagePercent)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(barColor)
                        )
                    }

                    if (usageStatus == SettingsRepository.UsageStatus.BLOCKED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Fallback auf OSRM (ohne Verkehr)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Limit Settings
                Text(
                    text = "Limits",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Daily Limit
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tageslimit",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = dailyLimit,
                                onValueChange = { dailyLimit = it.filter { c -> c.isDigit() } },
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
                    }

                    // Warning Threshold
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Warnung ab",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = warningThreshold,
                                onValueChange = { warningThreshold = it.filter { c -> c.isDigit() } },
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
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bei Limit: Fallback auf OSRM ohne Verkehrsdaten",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Status wenn kein Key
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ohne Key: Keine Echtzeit-Verkehrsdaten",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abbrechen")
                }

                Button(
                    onClick = {
                        HereConfig.setApiKey(hereApiKey.trim())
                        dailyLimit.toIntOrNull()?.let { HereConfig.setDailyLimit(it) }
                        warningThreshold.toIntOrNull()?.let { HereConfig.setWarningThreshold(it) }
                        onSave()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Speichern")
                }
            }
        }
    }
}
