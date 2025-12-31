package de.dalang.nav.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import de.dalang.nav.BuildConfig
import de.dalang.nav.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Update-Info von GitHub Release
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSize: Long
)

/**
 * Update-Status
 */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateStatus()
    object UpToDate : UpdateStatus()
    data class Downloading(val progress: Int) : UpdateStatus()
    data class ReadyToInstall(val apkFile: File) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

/**
 * Repository für GitHub-Updates
 */
class UpdateRepository(private val context: Context) {

    // GitHub Repository Info - hier anpassen!
    private val githubOwner = "pmaruhn15"
    private val githubRepo = "dalang"

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status

    /**
     * Aktuelle App-Version
     */
    fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Aktueller Version Code
     */
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Prüft auf GitHub nach Updates
     */
    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        _status.value = UpdateStatus.Checking
        CrashLogger.log("UpdateRepository: Checking for updates...")

        try {
            val url = "https://api.github.com/repos/$githubOwner/$githubRepo/releases/latest"
            CrashLogger.log("UpdateRepository: Fetching $url")

            val connection = URL(url).openConnection()
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val response = connection.getInputStream().bufferedReader().readText()
            val json = JSONObject(response)

            val tagName = json.optString("tag_name", "").removePrefix("v")
            val releaseNotes = json.optString("body", "Keine Release-Notes verfügbar")

            // Version aus Tag extrahieren (z.B. "v1.0.1" -> "1.0.1")
            val remoteVersion = tagName.ifEmpty { "0.0.0" }
            val remoteVersionCode = parseVersionCode(remoteVersion)
            val currentVersionCode = getCurrentVersionCode()

            CrashLogger.log("UpdateRepository: Remote version: $remoteVersion ($remoteVersionCode), Current: ${getCurrentVersion()} ($currentVersionCode)")

            // APK-Asset finden
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize: Long = 0

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size", 0)
                        break
                    }
                }
            }

            if (remoteVersionCode > currentVersionCode && apkUrl != null) {
                val updateInfo = UpdateInfo(
                    versionName = remoteVersion,
                    versionCode = remoteVersionCode,
                    releaseNotes = releaseNotes,
                    downloadUrl = apkUrl,
                    fileSize = apkSize
                )
                _status.value = UpdateStatus.UpdateAvailable(updateInfo)
                CrashLogger.log("UpdateRepository: Update available: $remoteVersion")
            } else {
                _status.value = UpdateStatus.UpToDate
                CrashLogger.log("UpdateRepository: App is up to date")
            }

        } catch (e: Exception) {
            CrashLogger.logError("UpdateRepository", "Check for updates failed", e)
            _status.value = UpdateStatus.Error("Update-Prüfung fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Lädt das APK herunter
     */
    suspend fun downloadUpdate(info: UpdateInfo) = withContext(Dispatchers.IO) {
        _status.value = UpdateStatus.Downloading(0)
        CrashLogger.log("UpdateRepository: Downloading update from ${info.downloadUrl}")

        try {
            val connection = URL(info.downloadUrl).openConnection()
            connection.setRequestProperty("User-Agent", "DaLang Navigation App")
            connection.connectTimeout = 30000
            connection.readTimeout = 60000

            val totalSize = connection.contentLengthLong
            val inputStream = connection.getInputStream()

            // APK in App-Cache speichern
            val apkFile = File(context.cacheDir, "dalang-update.apk")
            apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (totalSize > 0) {
                        val progress = ((totalBytesRead * 100) / totalSize).toInt()
                        _status.value = UpdateStatus.Downloading(progress)
                    }
                }
            }

            inputStream.close()
            _status.value = UpdateStatus.ReadyToInstall(apkFile)
            CrashLogger.log("UpdateRepository: Download complete: ${apkFile.absolutePath}")

        } catch (e: Exception) {
            CrashLogger.logError("UpdateRepository", "Download failed", e)
            _status.value = UpdateStatus.Error("Download fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Startet die APK-Installation
     */
    fun installUpdate(apkFile: File) {
        try {
            CrashLogger.log("UpdateRepository: Installing update from ${apkFile.absolutePath}")

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)

        } catch (e: Exception) {
            CrashLogger.logError("UpdateRepository", "Install failed", e)
            _status.value = UpdateStatus.Error("Installation fehlgeschlagen: ${e.message}")
        }
    }

    /**
     * Setzt den Status zurück
     */
    fun resetStatus() {
        _status.value = UpdateStatus.Idle
    }

    /**
     * Parst Version String zu Version Code (z.B. "1.2.3" -> 10203)
     */
    private fun parseVersionCode(version: String): Int {
        return try {
            val parts = version.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            0
        }
    }
}
