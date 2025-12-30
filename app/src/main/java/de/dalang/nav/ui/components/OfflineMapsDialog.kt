package de.dalang.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.dalang.nav.offline.DownloadProgress
import de.dalang.nav.offline.MapRegion
import de.dalang.nav.offline.OfflineMapManager
import de.dalang.nav.util.CrashLogger

@Composable
fun OfflineMapsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var downloadProgress by remember { mutableStateOf<Map<String, DownloadProgress>>(emptyMap()) }
    var downloadedRegions by remember { mutableStateOf(OfflineMapManager.getDownloadedRegionIds()) }

    LaunchedEffect(Unit) {
        CrashLogger.log("OfflineMapsDialog: Initializing")
        OfflineMapManager.initialize(context)
        downloadedRegions = OfflineMapManager.getDownloadedRegionIds()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Text(
                text = "Offline Karten",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Lade Kartenregionen herunter für Offline-Nutzung.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(OfflineMapManager.availableRegions) { region ->
                    val isDownloaded = downloadedRegions.contains(region.id)
                    val progress = downloadProgress[region.id]
                    val isDownloading = progress != null && !progress.isComplete && progress.error == null

                    RegionItem(
                        region = region,
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        progress = progress,
                        onDownload = {
                            CrashLogger.log("OfflineMapsDialog: Download ${region.name}")
                            val styleUrl = "https://tiles.openfreemap.org/styles/positron"
                            val pixelRatio = context.resources.displayMetrics.density

                            OfflineMapManager.downloadRegion(region, styleUrl, pixelRatio) { newProgress ->
                                downloadProgress = downloadProgress + (region.id to newProgress)
                                if (newProgress.isComplete) {
                                    downloadedRegions = OfflineMapManager.getDownloadedRegionIds()
                                }
                            }
                        },
                        onCancel = {
                            CrashLogger.log("OfflineMapsDialog: Cancel ${region.name}")
                            OfflineMapManager.cancelDownload(region.id)
                            downloadProgress = downloadProgress - region.id
                        },
                        onDelete = {
                            CrashLogger.log("OfflineMapsDialog: Delete ${region.name}")
                            OfflineMapManager.deleteRegion(region.id) { success ->
                                if (success) {
                                    downloadedRegions = OfflineMapManager.getDownloadedRegionIds()
                                    downloadProgress = downloadProgress - region.id
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Schließen")
            }
        }
    }
}

@Composable
private fun RegionItem(
    region: MapRegion,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: DownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = region.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "~${region.estimatedSizeMB} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when {
                    isDownloading -> Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Abbrechen")
                    }
                    isDownloaded -> Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Löschen")
                    }
                    else -> Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Laden")
                    }
                }
            }

            if (isDownloading && progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress.percentage / 100f }, modifier = Modifier.fillMaxWidth())
                Text(text = "${progress.percentage}%", style = MaterialTheme.typography.bodySmall)
            }

            progress?.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
