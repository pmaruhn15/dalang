package de.dalang.nav.util

import de.dalang.nav.navigation.LatLng
import de.dalang.nav.navigation.Maneuver
import de.dalang.nav.navigation.Route
import de.dalang.nav.navigation.RouteStep
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.util.Date
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

class TripFormatTest {
    private fun route() = Route(
        distance = 1234.5, duration = 99.0,
        geometry = listOf(LatLng(48.1, 11.5), LatLng(48.2, 11.6)),
        steps = listOf(RouteStep("Links \"abbiegen\"\nZeile2", 10.0, 5.0, Maneuver("turn", "left", LatLng(48.1, 11.5)), emptyList()))
    )

    @Test fun fixLine() {
        val j = JSONObject(TripFormat.fixLine(5L, 1700000000000L, 48.5, 11.25, 3.9f, null, 90f, Double.NaN, null, 0.5f))
        assertEquals(5L, j.getLong("t")); assertEquals(1700000000000L, j.getLong("wall"))
        assertEquals(3.9, j.getDouble("acc"), 0.0)  // nicht 3.9000000953...
        assertTrue(j.isNull("speed")); assertTrue(j.isNull("alt")); assertTrue(j.isNull("vacc"))
        assertEquals(0.5, j.getDouble("sacc"), 0.0)
        assertEquals(10, j.length())  // null-Felder bleiben als Key erhalten
    }

    @Test fun statusProgressEvent() {
        val s = JSONObject(TripFormat.statusLine(7, 9, 20, 31.26))
        assertEquals("status", s.getString("type")); assertEquals(9, s.getInt("sats")); assertEquals(31.3, s.getDouble("cn0mean"), 1e-9)
        assertTrue(JSONObject(TripFormat.statusLine(7, 0, 12, null)).isNull("cn0mean"))

        val p = JSONObject(TripFormat.progressLine(8, 123.456, null, true, 3, 50.04, 900.0, false))
        assertEquals(123.5, p.getDouble("s"), 1e-9); assertTrue(p.isNull("lateral"))
        assertTrue(p.getBoolean("estimating")); assertEquals(3, p.getInt("step")); assertFalse(p.getBoolean("matched"))

        val e = TripFormat.eventLine(9, "speak", "In 200 Metern:\nLinks", mapOf("mode" to "speak", "n" to null))
        assertFalse("NDJSON: keine Zeilenumbrüche", e.contains('\n'))
        val ej = JSONObject(e)
        assertEquals("In 200 Metern:\nLinks", ej.getString("text")); assertEquals("speak", ej.getJSONObject("data").getString("mode"))
        assertTrue(JSONObject(TripFormat.eventLine(1, "navStop", null, null)).isNull("text"))
    }

    @Test fun routeLine() {
        val line = TripFormat.routeLine(1, "start", route())
        assertFalse(line.contains('\n'))
        val j = JSONObject(line)
        assertEquals("start", j.getString("reason")); assertEquals(1234.5, j.getDouble("distance"), 0.0)
        assertEquals(48.2, j.getJSONArray("geometry").getJSONArray(1).getDouble(0), 0.0)
        val st = j.getJSONArray("steps").getJSONObject(0)
        assertEquals("turn", st.getString("type")); assertEquals("left", st.getString("modifier"))
        assertEquals(11.5, st.getDouble("lng"), 0.0); assertFalse(st.getBoolean("lanes"))
        assertEquals("Links \"abbiegen\"\nZeile2", st.getString("instruction"))
    }

    @Test fun meta() {
        val j = JSONObject(TripFormat.metaJson("1.5.0", "Fairphone FP5", 34, "T", 10, 20, 30, 48.0, 11.0, "Ziel", false, 4_000_000L))
        assertEquals(1, j.getInt("format")); assertEquals(34, j.getInt("android")); assertFalse(j.getBoolean("incomplete"))
        assertEquals("Ziel", j.getJSONObject("dest").getString("name")); assertEquals(4_000_000L, j.getLong("imuClockOffsetNs"))
        assertTrue(JSONObject(TripFormat.metaJson("a", "b", 1, "t", 1, 1, 1, null, null, null, true, null)).isNull("imuClockOffsetNs"))
    }

    @Test fun imuRows() {
        val sb = StringBuilder()
        TripFormat.imuRow(sb, 123456789012345L, "acc", 3, 0.1234f, -9.8126f, -0.0001f, 3)
        TripFormat.imuRow(sb, 5L, "pre", 3, 1013.25f, 0f, 0f, 1)
        assertEquals("123456789012345,acc,0.123,-9.813,0.000\n5,pre,1013.250,,\n", sb.toString())
        assertEquals("t_ns,sensor,x,y,z\n", TripFormat.IMU_HEADER)
    }

    private fun readGz(f: File) = GZIPInputStream(f.inputStream()).readBytes().toString(Charsets.US_ASCII)

    private fun zipOf(dir: File, out: File) =
        DebugUpload.writeZip(out, dir.listFiles()!!.filter { it != out }.associate { it.name to it }, emptyMap())

    @Test fun zipContent() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tripzip_" + System.nanoTime()).apply { mkdirs() }
        File(dir, "meta.json").writeText(TripFormat.metaJson("1", "d", 34, "T", 1, 2, 3, null, null, null, false, null))
        File(dir, "gnss.ndjson").writeText(TripFormat.fixLine(1, 2, 3.0, 4.0, 5f, 6f, 7f, 8.0, 9f, 1f) + "\n")
        GZIPOutputStream(File(dir, "imu.csv.gz").outputStream()).use { it.write(TripFormat.IMU_HEADER.toByteArray()) }
        val z = File(dir, "x.zip.out")
        zipOf(dir, z)
        ZipFile(z).use { zf ->
            assertEquals(setOf("meta.json", "gnss.ndjson", "imu.csv.gz"), zf.entries().toList().map { it.name }.toSet())
            assertEquals(1, zf.getInputStream(zf.getEntry("gnss.ndjson")).bufferedReader().readLines().size)
            val inner = File(dir, "inner.gz"); inner.writeBytes(zf.getInputStream(zf.getEntry("imu.csv.gz")).readBytes())
            assertEquals("t_ns,sensor,x,y,z\n", readGz(inner))
        }
        dir.deleteRecursively()
    }

    @Test fun tripFileName() {
        val n = DebugUpload.fileName(Date(0), "Fairphone 5", DebugUpload.TRIP_PREFIX)
        assertTrue(n, n.matches(Regex("trip_\\d{8}_\\d{6}_Fairphone_5\\.zip")))
    }

    /**
     * Größe pro Stunde mit synthetischen, aber realistisch verrauschten Daten: acc/gyr/grv 50 Hz, pre 5 Hz,
     * Zeitstempel mit +-0,5 ms Zufallsjitter (Worst-Case für die Kompression), GNSS+Progress 1 Hz,
     * 20 Routen à 3000 Punkte. Echte Sensoren sind eher glatter (gyr/grv) -> real eher kleiner.
     */
    @Test fun sizePerHour() {
        val rnd = Random(42)
        fun g() = rnd.nextGaussian().toFloat()
        val dir = File(System.getProperty("java.io.tmpdir"), "tripsize_" + System.nanoTime()).apply { mkdirs() }
        val gzFile = File(dir, "imu.csv.gz")
        val sb = StringBuilder()
        var gx = 0f; var gy = 0f; var gz = 0f; var qx = 0f; var qy = 0f; var qz = 0f
        GZIPOutputStream(BufferedOutputStream(gzFile.outputStream(), 32 * 1024), 16 * 1024, true).use { out ->
            out.write(TripFormat.IMU_HEADER.toByteArray())
            for (i in 0 until 3600 * 50) {
                val base = 1_000_000_000_000L + i * 20_000_000L
                fun ts() = base + (rnd.nextInt(1_000_000) - 500_000)
                TripFormat.imuRow(sb, ts(), "acc", 3, 0.3f + g() * 0.15f, 0.5f + g() * 0.15f, 9.8f + g() * 0.2f, 3)
                gx += g() * 0.002f; gy += g() * 0.002f; gz += g() * 0.004f
                TripFormat.imuRow(sb, ts(), "gyr", 4, gx.coerceIn(-0.3f, 0.3f) + g() * 0.003f, gy.coerceIn(-0.3f, 0.3f) + g() * 0.003f, gz.coerceIn(-0.3f, 0.3f) + g() * 0.003f, 3)
                qx += g() * 0.0005f; qy += g() * 0.0005f; qz += g() * 0.0005f
                TripFormat.imuRow(sb, ts(), "grv", 4, qx.coerceIn(-0.7f, 0.7f), qy.coerceIn(-0.7f, 0.7f), qz.coerceIn(-0.7f, 0.7f), 3)
                if (i % 10 == 0) TripFormat.imuRow(sb, ts(), "pre", 3, 1013.25f + g() * 0.03f, 0f, 0f, 1)
                if (sb.length >= 16384) { out.write(sb.toString().toByteArray()); sb.setLength(0) }
                if (i % 250 == 0) out.flush()  // 5-s-Sync-Flush wie im Recorder
            }
            out.write(sb.toString().toByteArray())
        }
        val gnss = StringBuilder(); val prog = StringBuilder()
        for (i in 0 until 3600) {
            val t = 1_000_000_000_000L + i * 1_000_000_000L
            gnss.append(TripFormat.fixLine(t, 1_700_000_000_000L + i * 1000, 48.0 + i * 1e-4 + rnd.nextGaussian() * 1e-5, 11.0 + i * 1e-4, 4.5f + rnd.nextFloat(), 13.9f, 270f, 520.0, 3f, 0.5f)).append('\n')
            gnss.append(TripFormat.statusLine(t + 5, 14, 22, 31.4)).append('\n')
            prog.append(TripFormat.progressLine(t, i * 14.0, 2.3, false, i / 60, 300.0, 50000.0 - i * 14, true)).append('\n')
        }
        val pts = (0 until 3000).map { LatLng(48.0 + it * 1e-4, 11.0 + it * 1.3e-4) }
        val routes = StringBuilder()
        repeat(20) { routes.append(TripFormat.routeLine(it.toLong(), "recalc", Route(5e4, 3600.0, pts, emptyList()))).append('\n') }
        File(dir, "gnss.ndjson").writeText(gnss.toString()); File(dir, "progress.ndjson").writeText(prog.toString())
        File(dir, "routes.ndjson").writeText(routes.toString()); File(dir, "meta.json").writeText("{}")
        val z = File(dir, "trip.zip")
        zipOf(dir, z)
        val mb = z.length() / 1048576.0
        println("TRIP SIZE: imu.gz=%.2f MB, zip gesamt=%.2f MB/h (imu roh: %.1f MB)".format(gzFile.length() / 1048576.0, mb,
            GZIPInputStream(gzFile.inputStream()).readBytes().size / 1048576.0))
        assertTrue("zip $mb MB/h", mb < 15.0)
        dir.deleteRecursively()
    }
}
