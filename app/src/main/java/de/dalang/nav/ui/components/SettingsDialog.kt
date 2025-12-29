package de.dalang.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.config.HereConfig

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var hereApiKey by remember { mutableStateOf(HereConfig.getApiKey()) }
    var showApiKey by remember { mutableStateOf(false) }

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

            // Status Anzeige
            Spacer(modifier = Modifier.height(8.dp))

            val statusText = if (hereApiKey.isNotBlank()) {
                "Verkehrsdaten werden verwendet"
            } else {
                "Ohne Key: Keine Echtzeit-Verkehrsdaten"
            }
            val statusColor = if (hereApiKey.isNotBlank()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor
            )

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
