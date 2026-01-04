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

            // Model aus Assets kopieren (einmalig)
            if (!modelFile.exists() || modelFile.length() < 50_000_000) {
                CrashLogger.log("PiperTts: Copying model from assets...")
                copyAssetFile("piper/de_DE-thorsten-medium.onnx", modelFile)
            }
            CrashLogger.log("PiperTts: Using model: ${modelFile.absolutePath} (${modelFile.length() / 1_000_000}MB)")

            // Tokens kopieren
            if (!tokensFile.exists()) {
                CrashLogger.log("PiperTts: Copying tokens file...")
                copyAssetFile("piper/tokens.txt", tokensFile)
            }

            // espeak-ng-data kopieren
            if (!dataDir.exists()) {
                CrashLogger.log("PiperTts: Copying espeak-ng-data...")
                copyAssetDirectory("piper/espeak-ng-data", dataDir)
            }

            // TTS konfigurieren
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
                numThreads = 2,
                debug = false
            )

            val config = OfflineTtsConfig(
                model = modelConfig
            )

            tts = OfflineTts(config = config)
            isInitialized = true

            CrashLogger.log("PiperTts: Initialized successfully, sample rate: ${tts?.sampleRate()}")
            true
        } catch (e: Exception) {
            CrashLogger.logError("PiperTts", "Initialization failed", e)
            isInitialized = false
            false
        }
    }

    /**
     * Spricht den Text aus
     */
    fun speak(text: String) {
        if (!enabled || !isInitialized || text.isBlank()) return

        scope.launch {
            try {
                val ttsInstance = tts ?: return@launch

                CrashLogger.log("PiperTts: Speaking: $text")

                // Audio generieren
                val audio = ttsInstance.generate(
                    text = text,
                    sid = 0,
                    speed = speed
                )

                // Audio abspielen
                playAudio(audio.samples, ttsInstance.sampleRate())

            } catch (e: Exception) {
                CrashLogger.logError("PiperTts", "speak failed", e)
            }
        }
    }

    /**
     * Spricht den Text sofort aus (unterbricht laufende Ausgabe)
     */
    fun speakNow(text: String) {
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

    private fun copyAssetDirectory(assetPath: String, destDir: File) {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        val files = context.assets.list(assetPath) ?: return

        for (file in files) {
            val srcPath = "$assetPath/$file"
            val destFile = File(destDir, file)

            try {
                // Versuche als Datei zu öffnen
                context.assets.open(srcPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                // Ist wahrscheinlich ein Verzeichnis
                copyAssetDirectory(srcPath, destFile)
            }
        }
    }
}
