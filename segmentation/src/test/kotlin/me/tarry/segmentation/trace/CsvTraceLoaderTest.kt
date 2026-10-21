package me.tarry.segmentation.trace

import me.tarry.segmentation.Activity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CsvTraceLoaderTest {
    @Test fun readsSample() {
        val pings = CsvTraceLoader.load("/traces/synthetic-sample.csv")
        assertEquals(5, pings.size)
        assertEquals(Instant.parse("2026-03-02T21:15:07.250Z").toEpochMilli(), pings[0].timeMs)
        assertNull(pings[0].activity)
        assertEquals(Activity.STILL, pings[1].activity)
        assertEquals(Activity.UNKNOWN, pings[4].activity)
        assertEquals(18.5f, pings[0].accuracyM)
        assertEquals(2L, pings[4].sessionId)
    }

    @Test fun rejectsBadHeader() {
        try {
            CsvTraceLoader.parse("id,time,lat\n1,2,3\n")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("header"))
        }
    }
}
