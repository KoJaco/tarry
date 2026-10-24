package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters
import me.tarry.segmentation.trace.SYDNEY_LAT
import me.tarry.segmentation.trace.SYDNEY_LNG
import me.tarry.segmentation.trace.TraceBuilder
import me.tarry.segmentation.trace.offsetMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CleaningTest {
    private val params = SegmentationParams()
    private val t0 = 1_760_000_000_000L
    private val min = 60_000L

    @Test
    fun goodAccuracyJitterAlternatingBetweenTwoSitesIsNotAnAnchor() {
        val b = TraceBuilder(seed = 3, startTimeMs = t0)
        repeat(8) { i ->
            b.stay(6 * min, intervalMs = 20_000, jitterM = 8.0, accuracyM = 8f)
            b.moveBy(if (i % 2 == 0) 90.0 else 270.0, 100.0, 1.4, mode = Activity.WALKING, accuracyM = 8f)
        }
        val pings = b.build()
        val accuracy = anchorAccuracies(pings, params)
        for (i in pings.indices) assertEquals(pings[i].accuracyM, accuracy[i], 0f)
    }

    @Test
    fun poorFixesAlternatingBetweenExactAnchorsAreRaisedToTheirSpread() {
        val a = SYDNEY_LAT to SYDNEY_LNG
        val c = offsetMeters(a.first, a.second, 300.0, 0.0)
        val pings = TraceBuilder(seed = 4, startTimeMs = t0)
            .anchors(30 * min, listOf(a, c), listOf(80f, 80f), intervalMs = 30_000, intervalJitter = 0.0)
            .build()
        val accuracy = anchorAccuracies(pings, params)
        assertTrue(accuracy.all { it >= 250f })
    }

    @Test
    fun restoreGivesStaysTheirOriginalPositionsAndCentroids() {
        val b = TraceBuilder(seed = 5, startTimeMs = t0)
        b.hops(40 * min, intervalMs = 20_000, jitterM = 30.0, hopMinM = 100.0, hopMaxM = 250.0, accuracyM = 60f..95f)
        val cleaned = cleanPings(b.build(), params)
        // Poor fixes were smoothed for detection only.
        assertTrue(cleaned.pings.indices.any { cleaned.pings[it].lat != cleaned.detection[it].lat })
        val detected = detectStays(cleaned.detection, params)
        val restored = cleaned.restore(detected, params)
        assertEquals(detected.size, restored.size)
        for (stay in restored) {
            assertTrue(stay.pings.all { p -> cleaned.pings.any { it === p } })
            val (lat, lng) = weightedCentroid(stay.pings, params)
            assertEquals(0.0, haversineMeters(stay.lat, stay.lng, lat, lng), 0.01)
        }
        assertNotEquals(detected.first().lat, restored.first().lat)
    }

    @Test
    fun nothingToSmoothReturnsTheSameStreamForDetection() {
        val pings = TraceBuilder(seed = 6, startTimeMs = t0).stay(20 * min, accuracyM = 8f).build()
        val cleaned = cleanPings(pings, params)
        assertSame(cleaned.pings, cleaned.pings)
        assertEquals(cleaned.pings, cleaned.detection)
    }
}
