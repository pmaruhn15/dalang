package de.dalang.nav.tts

import android.content.Context
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Download-Status für TTS Model
 */
sealed class DownloadState {
    object Idle : DownloadState()
    object Checking : DownloadState()
    data class Downloading(val progress: Int, val totalMb: Float) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Lädt das Thorsten-high TTS Model von Hugging Face herunter
 */
class TtsModelDownloader(private val context: Context) {

    companion object {
        // Thorsten-high Model (~114 MB)
        private const val MODEL_URL = "https://huggingface.co/Thorsten-Voice/Piper/resolve/main/de_DE-thorsten-high.onnx"
        private const val MODEL_FILENAME = "de_DE-thorsten-high.onnx"
        private const val EXPECTED_SIZE_MB = 114f  // Ungefähre Größe für Fortschrittsanzeige
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelDir: File
        get() = File(context.filesDir, "piper")

    val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    /**
     * Prüft ob das Model bereits heruntergeladen wurde
     */
    fun isModelDownloaded(): Boolean {
        val file = modelFile
        // Prüfe ob Datei existiert und mindestens 100MB groß ist (Thorsten-high ist ~114MB)
        return file.exists() && file.length() > 100_000_000
    }

    /**
     * Lädt das Model herunter und gibt den Fortschritt als Flow zurück
     */
    fun downloadModel(): Flow<DownloadState> = flow {
        emit(DownloadState.Checking)

        if (isModelDownloaded()) {
            CrashLogger.log("TtsModelDownloader: Model already exists")
            emit(DownloadState.Completed)
            return@flow
        }

        CrashLogger.log("TtsModelDownloader: Starting download from $MODEL_URL")

        try {
            // Verzeichnis erstellen
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            val request = Request.Builder()
                .url(MODEL_URL)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val error = "Download failed: HTTP ${response.code}"
                CrashLogger.log("TtsModelDownloader: $error")
                emit(DownloadState.Error(error))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(DownloadState.Error("Empty response"))
                return@flow
            }

            val contentLength = body.contentLength()
            val totalMb = if (contentLength > 0) contentLength / 1_000_000f else EXPECTED_SIZE_MB

            CrashLogger.log("TtsModelDownloader: Content length: $contentLength bytes (${totalMb}MB)")

            // Temporäre Datei für Download
            val tempFile = File(modelDir, "$MODEL_FILENAME.tmp")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var lastProgress = 0

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        bytesRead += read

                        // Progress nur alle 1% emittieren um UI nicht zu überlasten
                        val progress = if (contentLength > 0) {
                            ((bytesRead * 100) / contentLength).toInt()
                        } else {
                            ((bytesRead / 1_000_000f / EXPECTED_SIZE_MB) * 100).toInt().coerceAtMost(99)
                        }

                        if (progress > lastProgress) {
                            lastProgress = progress
                            emit(DownloadState.Downloading(progress, totalMb))
                        }
                    }
                }
            }

            // Temporäre Datei umbenennen
            if (tempFile.exists()) {
                val success = tempFile.renameTo(modelFile)
                if (success) {
                    CrashLogger.log("TtsModelDownloader: Download completed, size: ${modelFile.length()} bytes")
                    emit(DownloadState.Completed)
                } else {
                    emit(DownloadState.Error("Failed to save model file"))
                }
            } else {
                emit(DownloadState.Error("Download incomplete"))
            }

        } catch (e: Exception) {
            CrashLogger.logError("TtsModelDownloader", "Download failed", e)
            emit(DownloadState.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Löscht das heruntergeladene Model (z.B. für erneuten Download)
     */
    fun deleteModel() {
        try {
            if (modelFile.exists()) {
                modelFile.delete()
                CrashLogger.log("TtsModelDownloader: Model deleted")
            }
        } catch (e: Exception) {
            CrashLogger.logError("TtsModelDownloader", "Delete failed", e)
        }
    }
}
