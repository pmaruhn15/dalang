package de.dalang.nav.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Crash-Logger für Debugging-Zwecke
 * Speichert Crashes in einer Datei und zeigt sie beim nächsten App-Start an
 */
object CrashLogger {

    private const val TAG = "DaLang"
    private const val CRASH_LOG_FILE = "crash_log.txt"
    private const val MAX_LOG_SIZE = 100 * 1024 // 100KB

    private var crashLogFile: File? = null

    fun init(context: Context) {
        try {
            crashLogFile = File(context.filesDir, CRASH_LOG_FILE)

            // Globalen Exception-Handler setzen
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logCrash("UNCAUGHT", throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }

            log("App gestartet")
        } catch (e: Exception) {
            Log.e(TAG, "CrashLogger init failed", e)
        }
    }

    fun log(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMANY).format(Date())
            val logLine = "[$timestamp] INFO: $message\n"
            Log.d(TAG, message)
            appendToFile(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "Log failed", e)
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMANY).format(Date())
            val stackTrace = throwable?.let { getStackTraceString(it) } ?: ""
            val logLine = "[$timestamp] ERROR [$tag]: $message\n$stackTrace\n"
            Log.e(TAG, "[$tag] $message", throwable)
            appendToFile(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "LogError failed", e)
        }
    }

    fun logCrash(tag: String, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMANY).format(Date())
            val stackTrace = getStackTraceString(throwable)
            val logLine = """
                |
                |========== CRASH [$timestamp] ==========
                |TAG: $tag
                |MESSAGE: ${throwable.message}
                |STACK TRACE:
                |$stackTrace
                |==========================================
                |
            """.trimMargin()
            Log.e(TAG, "CRASH [$tag]", throwable)
            appendToFile(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "LogCrash failed", e)
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        return try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            sw.toString()
        } catch (e: Exception) {
            throwable.toString()
        }
    }

    private fun appendToFile(text: String) {
        try {
            val file = crashLogFile ?: return

            // Dateigröße begrenzen
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val content = file.readText()
                val halfContent = content.takeLast(MAX_LOG_SIZE / 2)
                file.writeText("... [truncated]\n$halfContent")
            }

            file.appendText(text)
        } catch (e: Exception) {
            Log.e(TAG, "File write failed", e)
        }
    }

    fun getLastCrashLog(): String? {
        return try {
            val file = crashLogFile
            if (file != null && file.exists()) {
                file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun hasRecentCrash(): Boolean {
        return try {
            val file = crashLogFile
            if (file != null && file.exists()) {
                val content = file.readText()
                content.contains("CRASH")
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun clearLog() {
        try {
            crashLogFile?.writeText("")
        } catch (e: Exception) {
            Log.e(TAG, "Clear log failed", e)
        }
    }
}
