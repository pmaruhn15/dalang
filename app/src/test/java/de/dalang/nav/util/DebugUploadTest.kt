package de.dalang.nav.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.zip.ZipFile

class DebugUploadTest {
    @Test fun parse() {
        assertEquals("https://c.example.org" to "AbC123", DebugUpload.parseShare("https://c.example.org/s/AbC123"))
        assertEquals("https://h/nextcloud" to "tok", DebugUpload.parseShare("https://h/nextcloud/s/tok/"))
        assertNull(DebugUpload.parseShare(""))
    }

    @Test fun name() {
        val n = DebugUpload.fileName(Date(0), "Fairphone 5/x")
        assertTrue(n, n.matches(Regex("dalang_report_\\d{8}_\\d{6}_Fairphone_5_x\\.zip")))
    }

    @Test fun request() {
        val f = File.createTempFile("rep", ".zip")
        val r = DebugUpload.putRequest("https://h", "tok", "a.zip", f, legacy = false)
        assertEquals("PUT", r.method)
        assertEquals("https://h/public.php/dav/files/tok/a.zip", r.url.toString())
        assertEquals("XMLHttpRequest", r.header("X-Requested-With"))
        assertTrue(r.header("Authorization")!!.startsWith("Basic "))
        assertEquals("https://h/public.php/webdav/a.zip", DebugUpload.putRequest("https://h", "tok", "a.zip", f, true).url.toString())
    }

    @Test fun zip() {
        val a = File.createTempFile("aud", ".m4a").apply { writeText("x") }
        val z = File.createTempFile("zzz", ".zip")
        DebugUpload.writeZip(z, mapOf("audio.m4a" to a, "track.gpx" to null), mapOf("info.txt" to "hi"))
        ZipFile(z).use { assertEquals(setOf("info.txt", "audio.m4a"), it.entries().toList().map { e -> e.name }.toSet()) }
    }
}
