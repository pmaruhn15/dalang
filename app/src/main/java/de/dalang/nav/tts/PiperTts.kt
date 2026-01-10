package de.dalang.nav.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/**
 * Piper TTS Wrapper für hochwertige deutsche Sprachausgabe
 * Verwendet Thorsten-medium Voice über sherpa-onnx (gebündelt in APK)
 */
class PiperTts(private val context: Context) {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Mutex verhindert gleichzeitige Audio-Wiedergabe
    private val audioMutex = Mutex()
    // Aktueller Speak-Job für Abbruch
    private var currentSpeakJob: Job? = null

    var speed: Float = 1.0f
    var enabled: Boolean = true

    /**
     * Initialisiert Piper TTS mit dem Thorsten-medium Model aus Assets
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            CrashLogger.log("PiperTts: Initializing...")

            // Prüfe ob Native Library geladen wurde
            if (!OfflineTts.isLibraryLoaded) {
                val error = OfflineTts.libraryLoadError ?: "Unknown error"
                CrashLogger.log("PiperTts: Native library not loaded: $error")
                return@withContext false
            }
            CrashLogger.log("PiperTts: Native library OK")

            // Model-Verzeichnis für alle Piper-Dateien
            val modelDir = File(context.filesDir, "piper")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            val modelFile = File(modelDir, "de_DE-thorsten-medium.onnx")
            val tokensFile = File(modelDir, "tokens.txt")
            val dataDir = File(modelDir, "espeak-ng-data")
            val espeakCompleteMarker = File(modelDir, ".espeak_complete")
            val initSuccessMarker = File(modelDir, ".init_success")

            // Lösche alten Crash-Counter falls vorhanden (wir wollen immer versuchen)
            File(modelDir, ".crash_count").delete()

            // Wenn vorherige Init erfolgreich war, können wir cached files nutzen
            val previousSuccess = initSuccessMarker.exists()
            if (previousSuccess) {
                CrashLogger.log("PiperTts: Previous init was successful")
            }

            // Prüfe Integrität der espeak-ng-data (mindestens 300 Dateien erwartet, normal ~355)
            val espeakFileCount = countFilesRecursive(dataDir)
            val espeakComplete = espeakCompleteMarker.exists() && espeakFileCount >= 300
            CrashLogger.log("PiperTts: espeak-ng-data has $espeakFileCount files, complete=$espeakComplete")

            // Prüfe ob alle Dateien vorhanden und vollständig sind
            val modelOk = modelFile.exists() && modelFile.length() > 50_000_000
            val tokensOk = tokensFile.exists() && tokensFile.length() > 100
            val espeakOk = espeakComplete

            if (!modelOk || !tokensOk || !espeakOk) {
                CrashLogger.log("PiperTts: Files incomplete (model=$modelOk, tokens=$tokensOk, espeak=$espeakOk)")
                CrashLogger.log("PiperTts: Deleting and re-copying all files...")

                // Alles löschen und neu kopieren
                modelDir.deleteRecursively()
                modelDir.mkdirs()
                initSuccessMarker.delete()
                espeakCompleteMarker.delete()

                // Model kopieren
                CrashLogger.log("PiperTts: Copying model (63MB)...")
                copyAssetFile("piper/de_DE-thorsten-medium.onnx", modelFile)
                CrashLogger.log("PiperTts: Model copied (${modelFile.length() / 1_000_000}MB)")
                yield()

                // Tokens kopieren
                CrashLogger.log("PiperTts: Copying tokens...")
                copyAssetFile("piper/tokens.txt", tokensFile)
                yield()

                // espeak-ng-data kopieren
                CrashLogger.log("PiperTts: Copying espeak-ng-data (this may take a moment)...")
                copyAssetDirectorySafe("piper/espeak-ng-data", dataDir)

                // Verifiziere dass genug Dateien kopiert wurden
                val newFileCount = countFilesRecursive(dataDir)
                CrashLogger.log("PiperTts: espeak-ng-data copied, $newFileCount files")

                if (newFileCount < 300) {
                    CrashLogger.log("PiperTts: ERROR - espeak-ng-data incomplete! Only $newFileCount files")
                    return@withContext false
                }

                // Markiere espeak als vollständig
                espeakCompleteMarker.createNewFile()
                CrashLogger.log("PiperTts: All files copied successfully")
            } else {
                CrashLogger.log("PiperTts: Using cached files")
            }

            // GC aufrufen um Speicher freizugeben
            System.gc()
            delay(100)

            // TTS erstellen
            CrashLogger.log("PiperTts: Creating TTS instance...")
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = dataDir.absolutePath,
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f
            )

            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,  // Fairphone 5 hat genug Power
                debug = false
            )

            val config = OfflineTtsConfig(
                model = modelConfig
            )

            tts = OfflineTts(config = config)
            isInitialized = true

            // Erfolg!
            initSuccessMarker.createNewFile()
            CrashLogger.log("PiperTts: Initialized successfully, sample rate: ${tts?.sampleRate()}")
            true
        } catch (e: Exception) {
            CrashLogger.logError("PiperTts", "Initialization failed", e)
            isInitialized = false
            false
        }
    }

    /**
     * Zählt alle Dateien in einem Verzeichnis rekursiv
     */
    private fun countFilesRecursive(dir: File): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                count += countFilesRecursive(file)
            } else {
                count++
            }
        }
        return count
    }

    /**
     * Spricht den Text aus (wartet auf vorherige Ausgabe)
     */
    fun speak(text: String) {
        if (!enabled || !isInitialized || text.isBlank()) return

        currentSpeakJob = scope.launch {
            // Warte auf Mutex - nur ein Audio gleichzeitig
            audioMutex.withLock {
                try {
                    val ttsInstance = tts ?: return@withLock

                    CrashLogger.log("PiperTts: Speaking: $text")

                    // Audio generieren
                    val audio = ttsInstance.generate(
                        text = text,
                        sid = 0,
                        speed = speed
                    )

                    // Audio abspielen (blockiert bis fertig)
                    playAudio(audio.samples, ttsInstance.sampleRate())

                } catch (e: Exception) {
                    CrashLogger.logError("PiperTts", "speak failed", e)
                }
            }
        }
    }

    /**
     * Spricht den Text sofort aus (unterbricht laufende Ausgabe)
     */
    fun speakNow(text: String) {
        // Vorherigen Job abbrechen
        currentSpeakJob?.cancel()
        stopCurrentPlayback()
        speak(text)
    }

    /**
     * Stoppt aktuelle Wiedergabe
     */
    fun stopCurrentPlayback() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Gibt Ressourcen frei
     */
    fun shutdown() {
        CrashLogger.log("PiperTts: Shutting down...")
        scope.cancel()
        stopCurrentPlayback()
        tts = null
        isInitialized = false
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        try {
            // Float samples zu 16-bit PCM konvertieren
            val pcmData = ShortArray(samples.size)
            for (i in samples.indices) {
                val sample = (samples[i] * 32767).toInt().coerceIn(-32768, 32767)
                pcmData[i] = sample.toShort()
            }

            // AudioTrack erstellen und abspielen
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(pcmData, 0, pcmData.size)
            audioTrack?.play()

            // Warten bis Wiedergabe fertig
            val durationMs = (samples.size * 1000L) / sampleRate
            Thread.sleep(durationMs + 100)

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

        } catch (e: Exception) {
            CrashLogger.logError("PiperTts", "playAudio failed", e)
        }
    }

    private fun copyAssetFile(assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Sichere Verzeichniskopie - kopiert Dateien einzeln mit explizitem Schließen
     */
    private suspend fun copyAssetDirectorySafe(assetPath: String, destDir: File) {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        val files = context.assets.list(assetPath) ?: return

        for (file in files) {
            val srcPath = "$assetPath/$file"
            val destFile = File(destDir, file)

            // Prüfe ob es ein Verzeichnis ist (hat Unterelemente)
            val subItems = context.assets.list(srcPath)
            if (subItems != null && subItems.isNotEmpty()) {
                // Es ist ein Verzeichnis - rekursiv kopieren
                copyAssetDirectorySafe(srcPath, destFile)
            } else {
                // Es ist eine Datei - kopieren
                try {
                    val input = context.assets.open(srcPath)
                    try {
                        val output = FileOutputStream(destFile)
                        try {
                            // Kopiere in kleinen Chunks für weniger Speicherverbrauch
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                            output.flush()
                        } finally {
                            output.close()
                        }
                    } finally {
                        input.close()
                    }
                    // Kurze Pause zwischen Dateien
                    yield()
                } catch (e: Exception) {
                    CrashLogger.log("PiperTts: Error copying $srcPath: ${e.message}")
                }
            }
        }
    }
}
