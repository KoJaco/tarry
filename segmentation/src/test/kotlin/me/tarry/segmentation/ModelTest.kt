package me.tarry.segmentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun timeRangeIsHalfOpen() {
        val r = TimeRange(10, 20)
        assertTrue(10L in r)
        assertFalse(20L in r)
        assertEquals(10L, r.durationMs)
    }

    @Test
    fun touchingRangesDoNotOverlap() {
        assertFalse(TimeRange(0, 10).overlaps(TimeRange(10, 20)))
        assertTrue(TimeRange(0, 11).overlaps(TimeRange(10, 20)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedRangeIsRejected() {
        TimeRange(20, 10)
    }

    @Test
    fun defaultParamsMatchTheMvpSpecTable() {
        val p = SegmentationParams()
        assertEquals(60.0, p.stayRadiusM, 0.0)
        assertEquals(5 * 60_000L, p.minStayDurationMs)
        assertEquals(45 * 60_000L, p.maxGapToBridgeMs)
        assertEquals(75.0, p.placeMatchRadiusM, 0.0)
        assertEquals(150.0, p.minTripDistanceM, 0.0)
        assertEquals(3 * 60_000L, p.shortTripMergeMs)
        assertEquals(30 * 60_000L, p.overlapMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bridgedConfidenceAboveFullIsRejected() {
        SegmentationParams(bridgedConfidence = 1.5f)
    }
}
