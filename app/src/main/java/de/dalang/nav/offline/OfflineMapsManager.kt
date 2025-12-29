package de.dalang.nav.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GermanState(
    val id: String,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class DownloadProgress(
    val stateId: String,
    val progress: Float,
    val isComplete: Boolean = false,
    val error: String? = null
)

class OfflineMapsManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val mapsDir: File by lazy {
        File(context.filesDir, "offline_maps").also { it.mkdirs() }
    }

    val germanStates = listOf(
        GermanState(
            "baden-wuerttemberg",
            "Baden-Württemberg",
            "https://download.geofabrik.de/europe/germany/baden-wuerttemberg-latest.osm.pbf",
            600_000_000
        ),
        GermanState(
            "bayern",
            "Bayern",
            "https://download.geofabrik.de/europe/germany/bayern-latest.osm.pbf",
            800_000_000
        ),
        GermanState(
            "berlin",
            "Berlin",
            "https://download.geofabrik.de/europe/germany/berlin-latest.osm.pbf",
            50_000_000
        ),
        GermanState(
            "brandenburg",
            "Brandenburg",
            "https://download.geofabrik.de/europe/germany/brandenburg-latest.osm.pbf",
            150_000_000
        ),
        GermanState(
            "bremen",
            "Bremen",
            "https://download.geofabrik.de/europe/germany/bremen-latest.osm.pbf",
            15_000_000
        ),
        GermanState(
            "hamburg",
            "Hamburg",
            "https://download.geofabrik.de/europe/germany/hamburg-latest.osm.pbf",
            30_000_000
        ),
        GermanState(
            "hessen",
            "Hessen",
            "https://download.geofabrik.de/europe/germany/hessen-latest.osm.pbf",
            250_000_000
        ),
        GermanState(
            "mecklenburg-vorpommern",
            "Mecklenburg-Vorpommern",
            "https://download.geofabrik.de/europe/germany/mecklenburg-vorpommern-latest.osm.pbf",
            80_000_000
        ),
        GermanState(
            "niedersachsen",
            "Niedersachsen",
            "https://download.geofabrik.de/europe/germany/niedersachsen-latest.osm.pbf",
            350_000_000
        ),
        GermanState(
            "nordrhein-westfalen",
            "Nordrhein-Westfalen",
            "https://download.geofabrik.de/europe/germany/nordrhein-westfalen-latest.osm.pbf",
            500_000_000
        ),
        GermanState(
            "rheinland-pfalz",
            "Rheinland-Pfalz",
            "https://download.geofabrik.de/europe/germany/rheinland-pfalz-latest.osm.pbf",
            180_000_000
        ),
        GermanState(
            "saarland",
            "Saarland",
            "https://download.geofabrik.de/europe/germany/saarland-latest.osm.pbf",
            25_000_000
        ),
        GermanState(
            "sachsen",
            "Sachsen",
            "https://download.geofabrik.de/europe/germany/sachsen-latest.osm.pbf",
            200_000_000
        ),
        GermanState(
            "sachsen-anhalt",
            "Sachsen-Anhalt",
            "https://download.geofabrik.de/europe/germany/sachsen-anhalt-latest.osm.pbf",
            120_000_000
        ),
        GermanState(
            "schleswig-holstein",
            "Schleswig-Holstein",
            "https://download.geofabrik.de/europe/germany/schleswig-holstein-latest.osm.pbf",
            120_000_000
        ),
        GermanState(
            "thueringen",
            "Thüringen",
            "https://download.geofabrik.de/europe/germany/thueringen-latest.osm.pbf",
            100_000_000
        )
    )

    fun isDownloaded(stateId: String): Boolean {
        return File(mapsDir, "$stateId.osm.pbf").exists()
    }

    fun getDownloadedStates(): List<String> {
        return mapsDir.listFiles()
            ?.filter { it.extension == "pbf" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun downloadState(state: GermanState): Flow<DownloadProgress> = flow {
        val file = File(mapsDir, "${state.id}.osm.pbf")

        try {
            val request = Request.Builder()
                .url(state.downloadUrl)
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                emit(DownloadProgress(state.id, 0f, error = "Download fehlgeschlagen: ${response.code}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadProgress(state.id, 0f, error = "Leere Antwort"))
                return@flow
            }

            val contentLength = body.contentLength()
            var downloadedBytes = 0L

            withContext(Dispatchers.IO) {
                FileOutputStream(file).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            val progress = if (contentLength > 0) {
                                downloadedBytes.toFloat() / contentLength
                            } else {
                                downloadedBytes.toFloat() / state.sizeBytes
                            }

                            emit(DownloadProgress(state.id, progress.coerceIn(0f, 1f)))
                        }
                    }
                }
            }

            emit(DownloadProgress(state.id, 1f, isComplete = true))

        } catch (e: Exception) {
            file.delete()
            emit(DownloadProgress(state.id, 0f, error = e.message ?: "Unbekannter Fehler"))
        }
    }

    fun deleteState(stateId: String): Boolean {
        return File(mapsDir, "$stateId.osm.pbf").delete()
    }

    fun getStorageUsed(): Long {
        return mapsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}

fun Long.formatFileSize(): String {
    return when {
        this >= 1_000_000_000 -> String.format("%.1f GB", this / 1_000_000_000.0)
        this >= 1_000_000 -> String.format("%.1f MB", this / 1_000_000.0)
        this >= 1_000 -> String.format("%.1f KB", this / 1_000.0)
        else -> "$this B"
    }
}
