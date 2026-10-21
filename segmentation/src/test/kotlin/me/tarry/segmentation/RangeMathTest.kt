package me.tarry.segmentation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class RangeMathTest {
    private fun r(s: Long, e: Long) = TimeRange(s, e)

    @Test fun normalizedMergesOverlappingAndTouching() {
        val out = listOf(r(20, 30), r(0, 10), r(5, 12), r(12, 15), r(40, 40)).normalized()
        assertEquals(listOf(r(0, 15), r(20, 30)), out)
    }

    @Test fun minusNoOverlapReturnsSelf() {
        assertEquals(listOf(r(0, 10)), r(0, 10).minus(listOf(r(10, 20), r(-5, 0))))
    }

    @Test fun minusFullCoverReturnsNothing() {
        assertEquals(emptyList<TimeRange>(), r(0, 10).minus(listOf(r(-1, 11))))
    }

    @Test fun minusOnePiece() {
        assertEquals(listOf(r(5, 10)), r(0, 10).minus(listOf(r(-3, 5))))
        assertEquals(listOf(r(0, 5)), r(0, 10).minus(listOf(r(5, 20))))
    }

    @Test fun minusMiddleSplitsIntoTwo() {
        assertEquals(listOf(r(0, 3), r(7, 10)), r(0, 10).minus(listOf(r(3, 7))))
    }

    @Test fun minusUnsortedMultipleOthers() {
        assertEquals(listOf(r(0, 2), r(4, 6), r(8, 10)), r(0, 10).minus(listOf(r(6, 8), r(2, 4))))
    }

    @Test fun clipIntersectsOrReturnsNull() {
        assertEquals(r(5, 10), r(0, 10).clip(r(5, 20)))
        assertEquals(r(2, 4), r(0, 10).clip(r(2, 4)))
        assertNull(r(0, 10).clip(r(10, 20)))
        assertNull(r(0, 10).clip(r(20, 30)))
    }
}
