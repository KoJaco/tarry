package me.tarry.segmentation.trace

import me.tarry.segmentation.Activity
import me.tarry.segmentation.geo.haversineMeters
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceBuilderTest {
    private val start = atLocal(LocalDate.of(2026, 3, 3), LocalTime.of(9, 0))

    private fun sample(seed: Long) = TraceBuilder(seed, startTimeMs = start)
        .stay(10 * 60_000).moveBy(90.0, 2_000.0, 10.0).stay(5 * 60_000).spikes(2).build()

    @Test fun sameSeedIsIdentical() = assertEquals(sample(7), sample(7))

    @Test fun differentSeedDiffers() = assertNotEquals(sample(7), sample(8))

    @Test fun stayPingsWithinJitter() {
        val pings = TraceBuilder(1, SYDNEY_LAT, SYDNEY_LNG, start).stay(60 * 60_000, jitterM = 15.0).build()
        assertEquals(60, pings.size)
        pings.forEach {
            assertTrue(haversineMeters(SYDNEY_LAT, SYDNEY_LNG, it.lat, it.lng) <= 15.0 + 0.01)
            assertTrue(it.accuracyM in 15f..25f)
            assertEquals(Activity.STILL, it.activity)
            assertEquals(1L, it.sessionId)
        }
    }

    @Test fun moveEndsAtTargetWithSteadySpeed() {
        val (toLat, toLng) = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 3_000.0, 4_000.0)
        val pings = TraceBuilder(2, startTimeMs = start).move(toLat, toLng, 12.5, intervalMs = 20_000).build()
        val last = pings.last()
        assertTrue(haversineMeters(last.lat, last.lng, toLat, toLng) < 1.0)
        assertEquals(Activity.IN_VEHICLE, last.activity)
        var prevLat = SYDNEY_LAT
        var prevLng = SYDNEY_LNG
        var prevT = start
        pings.forEach {
            assertEquals(20_000L, it.timeMs - prevT)
            val speed = haversineMeters(prevLat, prevLng, it.lat, it.lng) / 20.0
            assertTrue("speed $speed", abs(speed - 12.5) / 12.5 < 0.05)
            prevLat = it.lat
            prevLng = it.lng
            prevT = it.timeMs
        }
    }

    @Test fun gapLeavesNoPings() {
        val b = TraceBuilder(3, startTimeMs = start).stay(5 * 60_000)
        val gapStart = b.currentTimeMs
        b.gap(30 * 60_000)
        val gapEnd = b.currentTimeMs
        val pings = b.stay(5 * 60_000).build()
        assertEquals(10, pings.size)
        assertTrue(pings.none { it.timeMs in gapStart until gapEnd })
        assertEquals(gapEnd, pings[5].timeMs)
    }

    @Test fun midnightCrossingIsOrdered() {
        val t0 = atLocal(LocalDate.of(2026, 3, 3), LocalTime.of(23, 30))
        val pings = TraceBuilder(4, startTimeMs = t0).stay(30 * 60_000).moveBy(0.0, 500.0, 5.0)
            .stay(30 * 60_000).build()
        assertEquals(pings.sortedBy { it.timeMs }, pings)
        val midnight = atLocal(LocalDate.of(2026, 3, 4), LocalTime.MIDNIGHT)
        assertTrue(pings.any { it.timeMs < midnight } && pings.any { it.timeMs >= midnight })
        assertEquals(pings.first().timeMs, pings.range().startMs)
        assertEquals(pings.last().timeMs + 1, pings.range().endMs)
    }

    @Test fun sessionStampsLaterPings() {
        val pings = TraceBuilder(5, startTimeMs = start).stay(60_000).session(9).stay(60_000).build()
        assertEquals(listOf(1L, 9L), pings.map { it.sessionId })
    }

    @Test fun offsetMetersRoundTrips() {
        for ((n, e) in listOf(1_000.0 to 0.0, 0.0 to 2_500.0, 20_000.0 to 15_000.0, -8_000.0 to -3_000.0)) {
            val (lat, lng) = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, n, e)
            val expected = Math.hypot(n, e)
            val actual = haversineMeters(SYDNEY_LAT, SYDNEY_LNG, lat, lng)
            assertTrue("$n,$e: $actual vs $expected", abs(actual - expected) / expected < 0.005)
        }
    }
}
