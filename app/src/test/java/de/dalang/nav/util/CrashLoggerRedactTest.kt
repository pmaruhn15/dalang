package de.dalang.nav.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashLoggerRedactTest {
    @Test
    fun masksKeysInUrls() {
        val s = CrashLogger.redact(
            "Style: https://api.maptiler.com/maps/x/style.json?key=AbC123xyz\n" +
                "HERE: https://router.hereapi.com/v8/routes?origin=1,2&apiKey=SeCrEt-42&return=polyline"
        )
        assertFalse(s.contains("AbC123xyz"))
        assertFalse(s.contains("SeCrEt-42"))
        assertEquals(true, s.contains("key=***") && s.contains("apiKey=***&return=polyline"))
    }

    @Test
    fun leavesNormalLogAlone() {
        val line = "MainViewModel: ETA tick — fromStep=0 remaining=9263m time=712s"
        assertEquals(line, CrashLogger.redact(line))
    }
}
