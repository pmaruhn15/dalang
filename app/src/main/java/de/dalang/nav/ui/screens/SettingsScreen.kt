package de.dalang.nav.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.dalang.nav.offline.GermanState
import de.dalang.nav.offline.OfflineMapsManager
import de.dalang.nav.offline.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    offlineMapsManager: OfflineMapsManager,
    voiceEnabled: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var downloadedStates by remember { mutableStateOf(offlineMapsManager.getDownloadedStates()) }
    var downloadingState by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sprachansagen
            item {
                SettingsSection(title = "Navigation") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Sprachansagen",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Navigationsanweisungen vorlesen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = voiceEnabled,
                            onCheckedChange = onVoiceEnabledChange
                        )
                    }
                }
            }

            // Offline-Karten
            item {
                SettingsSection(title = "Offline-Karten") {
                    Text(
                        text = "Speicher: ${offlineMapsManager.getStorageUsed().formatFileSize()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            items(offlineMapsManager.germanStates) { state ->
                OfflineMapItem(
                    state = state,
                    isDownloaded = state.id in downloadedStates,
                    isDownloading = downloadingState == state.id,
                    progress = if (downloadingState == state.id) downloadProgress else 0f,
                    onDownload = {
                        // Download-Logik würde hier implementiert
                    },
                    onDelete = {
                        offlineMapsManager.deleteState(state.id)
                        downloadedStates = offlineMapsManager.getDownloadedStates()
                    }
                )
            }

            // Info
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Offline-Karten ermöglichen die Navigation ohne Internetverbindung. " +
                            "Die Karten werden von OpenStreetMap bereitgestellt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun OfflineMapItem(
    state: GermanState,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "~${state.sizeBytes.formatFileSize()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }

        when {
            isDownloading -> {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            isDownloaded -> {
                Row {
                    Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Löschen",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(onClick = onDelete)
                    )
                }
            }
            else -> {
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Laden")
                }
            }
        }
    }
}
