package de.dalang.nav.offline

import android.content.Context
import de.dalang.nav.util.CrashLogger
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.geometry.LatLngBounds

data class MapRegion(
    val id: String,
    val name: String,
    val bounds: LatLngBounds,
    val minZoom: Double = 6.0,
    val maxZoom: Double = 16.0,
    val estimatedSizeMB: Int = 50
)

data class DownloadProgress(
    val regionId: String,
    val percentage: Int,
    val isComplete: Boolean,
    val error: String? = null
)

object OfflineMapManager {
    private var offlineManager: OfflineManager? = null
    private val downloadedRegions = mutableMapOf<String, OfflineRegion>()

    val availableRegions = listOf(
        MapRegion(
            id = "bayern",
            name = "Bayern",
            bounds = LatLngBounds.from(50.6, 13.9, 47.3, 8.9),
            estimatedSizeMB = 450
        ),
        MapRegion(
            id = "baden_wuerttemberg",
            name = "Baden-Württemberg",
            bounds = LatLngBounds.from(49.8, 10.5, 47.5, 7.5),
            estimatedSizeMB = 280
        ),
        MapRegion(
            id = "nrw",
            name = "Nordrhein-Westfalen",
            bounds = LatLngBounds.from(52.6, 9.5, 50.3, 5.9),
            estimatedSizeMB = 320
        ),
        MapRegion(
            id = "berlin_brandenburg",
            name = "Berlin & Brandenburg",
            bounds = LatLngBounds.from(53.6, 14.8, 51.4, 11.3),
            estimatedSizeMB = 180
        ),
        MapRegion(
            id = "muenchen",
            name = "München & Umgebung",
            bounds = LatLngBounds.from(48.35, 11.85, 47.95, 11.25),
            minZoom = 10.0,
            maxZoom = 17.0,
            estimatedSizeMB = 85
        )
    )

    fun initialize(context: Context) {
        CrashLogger.log("OfflineMapManager: Initializing")
        offlineManager = OfflineManager.getInstance(context)
        loadExistingRegions()
    }

    private fun loadExistingRegions() {
        CrashLogger.log("OfflineMapManager: Loading existing regions")
        offlineManager?.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions?.forEach { region ->
                    val metadata = String(region.metadata)
                    CrashLogger.log("OfflineMapManager: Found region: $metadata")
                    downloadedRegions[metadata] = region
                }
                CrashLogger.log("OfflineMapManager: Loaded ${offlineRegions?.size ?: 0} existing regions")
            }

            override fun onError(error: String) {
                CrashLogger.log("OfflineMapManager: Error loading regions: $error")
            }
        })
    }

    fun downloadRegion(
        region: MapRegion,
        styleUrl: String,
        pixelRatio: Float,
        onProgress: (DownloadProgress) -> Unit
    ) {
        CrashLogger.log("OfflineMapManager: Starting download for ${region.name}")

        val manager = offlineManager
        if (manager == null) {
            CrashLogger.log("OfflineMapManager: Manager not initialized")
            onProgress(DownloadProgress(region.id, 0, false, "Manager nicht initialisiert"))
            return
        }

        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            region.bounds,
            region.minZoom,
            region.maxZoom,
            pixelRatio
        )

        val metadata = region.id.toByteArray()

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    CrashLogger.log("OfflineMapManager: Region created, starting download")
                    downloadedRegions[region.id] = offlineRegion

                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val percentage = if (status.requiredResourceCount > 0) {
                                (status.completedResourceCount * 100 / status.requiredResourceCount).toInt()
                            } else {
                                0
                            }

                            val isComplete = status.isComplete
                            CrashLogger.log("OfflineMapManager: Download progress ${region.id}: $percentage%")

                            onProgress(DownloadProgress(region.id, percentage, isComplete))

                            if (isComplete) {
                                CrashLogger.log("OfflineMapManager: Download complete for ${region.name}")
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            CrashLogger.log("OfflineMapManager: Download error: ${error.reason} - ${error.message}")
                            onProgress(DownloadProgress(region.id, 0, false, error.message))
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            CrashLogger.log("OfflineMapManager: Tile limit exceeded: $limit")
                            onProgress(DownloadProgress(region.id, 0, false, "Tile-Limit überschritten"))
                        }
                    })

                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    CrashLogger.log("OfflineMapManager: Create region error: $error")
                    onProgress(DownloadProgress(region.id, 0, false, error))
                }
            }
        )
    }

    fun deleteRegion(regionId: String, onComplete: (Boolean) -> Unit) {
        CrashLogger.log("OfflineMapManager: Deleting region $regionId")
        val region = downloadedRegions[regionId]
        if (region == null) {
            CrashLogger.log("OfflineMapManager: Region not found")
            onComplete(false)
            return
        }

        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                CrashLogger.log("OfflineMapManager: Region deleted")
                downloadedRegions.remove(regionId)
                onComplete(true)
            }

            override fun onError(error: String) {
                CrashLogger.log("OfflineMapManager: Delete error: $error")
                onComplete(false)
            }
        })
    }

    fun cancelDownload(regionId: String) {
        CrashLogger.log("OfflineMapManager: Canceling download for $regionId")
        val region = downloadedRegions[regionId]
        region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        // Delete the incomplete region
        region?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                CrashLogger.log("OfflineMapManager: Canceled region deleted")
                downloadedRegions.remove(regionId)
            }
            override fun onError(error: String) {
                CrashLogger.log("OfflineMapManager: Cancel delete error: $error")
            }
        })
    }

    fun getDownloadedRegionIds(): Set<String> = downloadedRegions.keys.toSet()
}
