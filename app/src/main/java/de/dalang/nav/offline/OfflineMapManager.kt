package de.dalang.nav.offline

import android.content.Context
import de.dalang.nav.util.CrashLogger
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.geometry.LatLngBounds

data class MapRegion(
    val id: String,
    val name: String,
    val bounds: LatLngBounds,
    val minZoom: Double = 6.0,
    val maxZoom: Double = 16.0
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

    // Vordefinierte Regionen für Deutschland
    val availableRegions = listOf(
        MapRegion(
            id = "bayern",
            name = "Bayern",
            bounds = LatLngBounds.from(50.6, 13.9, 47.3, 8.9)
        ),
        MapRegion(
            id = "baden_wuerttemberg",
            name = "Baden-Württemberg",
            bounds = LatLngBounds.from(49.8, 10.5, 47.5, 7.5)
        ),
        MapRegion(
            id = "nrw",
            name = "Nordrhein-Westfalen",
            bounds = LatLngBounds.from(52.6, 9.5, 50.3, 5.9)
        ),
        MapRegion(
            id = "berlin_brandenburg",
            name = "Berlin & Brandenburg",
            bounds = LatLngBounds.from(53.6, 14.8, 51.4, 11.3)
        ),
        MapRegion(
            id = "niedersachsen",
            name = "Niedersachsen",
            bounds = LatLngBounds.from(54.0, 11.6, 51.3, 6.6)
        ),
        MapRegion(
            id = "hessen",
            name = "Hessen",
            bounds = LatLngBounds.from(51.7, 10.3, 49.4, 7.8)
        ),
        MapRegion(
            id = "sachsen",
            name = "Sachsen",
            bounds = LatLngBounds.from(51.7, 15.1, 50.2, 11.9)
        ),
        MapRegion(
            id = "muenchen",
            name = "München & Umgebung",
            bounds = LatLngBounds.from(48.35, 11.85, 47.95, 11.25),
            minZoom = 10.0,
            maxZoom = 17.0
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
            override fun onList(regions: Array<out OfflineRegion>?) {
                regions?.forEach { region ->
                    val metadata = String(region.metadata)
                    CrashLogger.log("OfflineMapManager: Found region: $metadata")
                    downloadedRegions[metadata] = region
                }
                CrashLogger.log("OfflineMapManager: Loaded ${regions?.size ?: 0} existing regions")
            }

            override fun onError(error: String) {
                CrashLogger.log("OfflineMapManager: Error loading regions: $error")
            }
        })
    }

    fun isRegionDownloaded(regionId: String): Boolean {
        return downloadedRegions.containsKey(regionId)
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
                        override fun onStatusChanged(status: OfflineRegion.OfflineRegionStatus) {
                            val percentage = if (status.requiredResourceCount > 0) {
                                (status.completedResourceCount * 100 / status.requiredResourceCount).toInt()
                            } else {
                                0
                            }

                            val isComplete = status.isComplete
                            CrashLogger.log("OfflineMapManager: Download progress ${region.id}: $percentage% (${status.completedResourceCount}/${status.requiredResourceCount})")

                            onProgress(DownloadProgress(region.id, percentage, isComplete))

                            if (isComplete) {
                                CrashLogger.log("OfflineMapManager: Download complete for ${region.name}")
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            }
                        }

                        override fun onError(error: OfflineRegion.OfflineRegionError) {
                            CrashLogger.log("OfflineMapManager: Download error: ${error.reason} - ${error.message}")
                            onProgress(DownloadProgress(region.id, 0, false, error.message ?: "Unbekannter Fehler"))
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
            CrashLogger.log("OfflineMapManager: Region not found for deletion")
            onComplete(false)
            return
        }

        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                CrashLogger.log("OfflineMapManager: Region deleted successfully")
                downloadedRegions.remove(regionId)
                onComplete(true)
            }

            override fun onError(error: String) {
                CrashLogger.log("OfflineMapManager: Delete error: $error")
                onComplete(false)
            }
        })
    }

    fun getDownloadedRegionIds(): Set<String> {
        return downloadedRegions.keys.toSet()
    }
}
