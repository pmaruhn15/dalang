package de.dalang.nav.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Sichert Parser + Metriken der Fahrt-Auswertung ab (läuft immer). Fixture trip/trip_synthetic:
 * 20 m/s geradeaus, Rechtskurve bei 1200 m, Ziel 1500 m; Tunnel 21–34 s (sats=0, ein wegdriftender
 * Fix acc 120 m), Ansage "In 500 Metern" bei s=720 (wahr 480 m).
 */
class TripEvaluationTest {

    private val dir = File(javaClass.classLoader!!.getResource("trip/trip_synthetic")!!.toURI())

    @Test
    fun parsesDirectoryAndZip() {
        val trip = TripEval.loadAll(dir.parentFile).single()
        assertEquals(63, trip.fixes.size)
        assertEquals(2, trip.statuses.size)
        assertEquals(3, trip.routes.single().route.steps.size)
        assertEquals("right", trip.routes.single().route.steps[1].maneuver.modifier)
        assertEquals(null, trip.routes.single().route.steps[0].maneuver.modifier)
        assertEquals(2, trip.events.size)
        assertEquals(2, trip.live.size)
        assertEquals(1_000_000L, trip.fixes.first().t)  // ns -> ms

        val tmp = Files.createTempDirectory("trip").toFile()
        ZipOutputStream(File(tmp, "trip_synthetic.zip").outputStream()).use { z ->
            dir.listFiles()!!.forEach { f -> z.putNextEntry(ZipEntry("trip_synthetic/${f.name}")); f.inputStream().use { it.copyTo(z) } }
        }
        assertEquals(63, TripEval.loadAll(tmp).single().fixes.size)
        tmp.deleteRecursively()
    }

    @Test
    fun metrics() {
        val trip = TripEval.loadAll(dir.parentFile).single()
        val r = TripEval.analyze(trip, TripEval.Cfg(lens = listOf(10), stepS = 10, warmS = 10))

        val o = r.outages.single()
        assertEquals(15_000L, o.t1 - o.t0)
        assertEquals(300.0, o.distM, 1.0)
        assertTrue(o.sats0 && o.estimated)
        assertTrue("Fehler Tunnelende ${o.errEnd}", abs(o.errEnd) <= 25)
        assertEquals(0, o.falsePass)

        val c = r.speakChecks.single()
        assertEquals(500.0, c.announcedM, 0.0)
        assertEquals(480.0, c.trueM, 1.0)
        assertEquals(1.0, c.devS, 0.1)

        val drop = r.injections.filter { it.mode == "drop" }
        assertTrue(drop.size >= 3)
        for (i in drop) {
            assertEquals("konstant", i.cls)
            assertTrue("A ${i.errA}", abs(i.errA) <= 25)
            assertTrue("B ${i.errB}", i.errB < -150)     // eingefroren: ~10 s x 20 m/s hinterher
            assertTrue("C ${i.errC}", abs(i.errC) <= 25)
        }
        assertTrue(TripEval.injectionTable(r.injections, false).contains("| drop | 10 s | alle |"))
        assertEquals(50.0, TripEval.pct(listOf(10.0, 50.0, 90.0), 50.0), 0.0)
    }
}
