package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters
import me.tarry.segmentation.trace.SYDNEY_LAT
import me.tarry.segmentation.trace.SYDNEY_LNG
import me.tarry.segmentation.trace.TraceBuilder
import me.tarry.segmentation.trace.offsetMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StayDetectorTest {
    private val params = SegmentationParams()
    private val t0 = 1_760_000_000_000L
    private fun builder(seed: Long = 7) = TraceBuilder(seed = seed, startTimeMs = t0)

    @Test
    fun singleSiteAllDayIsOneOpenStayAtTheSite() {
        val pings = builder().stay(HOUR * 8).build()
        val stays = detectStays(pings, params)
        assertEquals(1, stays.size)
        val s = stays.single()
        assertTrue(s.open)
        assertFalse(s.bridged)
        assertEquals(pings.first().timeMs, s.range.startMs)
        assertEquals(pings.last().timeMs, s.range.endMs)
        assertTrue(haversineMeters(s.lat, s.lng, SYDNEY_LAT, SYDNEY_LNG) < 10.0)
    }

    @Test
    fun twoSitesWithADriveGiveTwoStays() {
        val pings = builder().stay(HOUR).moveBy(90.0, 5_000.0, 12.5).stay(HOUR).build()
        val stays = detectStays(pings, params)
        assertEquals(2, stays.size)
        assertFalse(stays[0].open)
        assertTrue(stays[1].open)
        assertTrue(stays[0].range.endMs < stays[1].range.startMs)
        assertTrue(haversineMeters(stays[0].lat, stays[0].lng, stays[1].lat, stays[1].lng) > 4_900.0)
    }

    @Test
    fun accuracySpikesDoNotSplitTheStay() {
        val pings = builder().stay(HOUR).spikes(5).stay(HOUR).spikes(3).stay(HOUR).build()
        val stays = detectStays(pings, params)
        assertEquals(1, stays.size)
        assertTrue(haversineMeters(stays[0].lat, stays[0].lng, SYDNEY_LAT, SYDNEY_LNG) < 15.0)
    }

    @Test
    fun aFortyMinuteSilenceInsideAStayIsBridgedWithLowerConfidence() {
        val pings = builder().stay(HOUR).gap(40 * MIN).stay(HOUR).build()
        val stays = detectStays(pings, params)
        assertEquals(1, stays.size)
        assertTrue(stays.single().bridged)
    }

    @Test
    fun aSilenceLongerThanMaxGapToBridgeClosesTheStay() {
        val pings = builder().stay(HOUR).gap(50 * MIN).stay(HOUR).build()
        val stays = detectStays(pings, params)
        assertEquals(2, stays.size)
        assertFalse(stays[0].open)
        assertFalse(stays[0].bridged)
    }

    @Test
    fun keepaliveSpacingIsNotABridgedGap() {
        // Capture saves a stationary keepalive about every 10 min (plus up to a 3 min fix interval).
        val pings = builder().stay(HOUR * 3, intervalMs = 13 * MIN).build()
        assertFalse(detectStays(pings, params).single().bridged)
    }

    @Test
    fun aFuelStopUnderFiveMinutesIsNotAStay() {
        val pings = builder()
            .stay(HOUR)
            .moveBy(0.0, 3_000.0, 15.0)
            .stay(4 * MIN, intervalMs = 20_000)
            .moveBy(0.0, 3_000.0, 15.0)
            .stay(HOUR)
            .build()
        assertEquals(2, detectStays(pings, params).size)
    }

    @Test
    fun aFiveMinuteStopIsAStay() {
        val pings = builder()
            .stay(HOUR)
            .moveBy(0.0, 3_000.0, 15.0)
            .stay(6 * MIN, intervalMs = 20_000)
            .moveBy(0.0, 3_000.0, 15.0)
            .stay(HOUR)
            .build()
        assertEquals(3, detectStays(pings, params).size)
    }

    @Test
    fun trailingDrivingIsReturnedAsAShortOpenCandidate() {
        val pings = builder().stay(HOUR).moveBy(180.0, 2_000.0, 12.5).build()
        val stays = detectStays(pings, params)
        assertEquals(2, stays.size)
        assertTrue(stays[1].open)
        assertTrue(stays[1].range.durationMs < params.minStayDurationMs)
    }

    @Test
    fun centroidFavoursAccurateFixes() {
        val accurate = Ping(t0, SYDNEY_LAT, SYDNEY_LNG, accuracyM = 5f)
        val sloppy = Ping(t0 + 10 * MIN, SYDNEY_LAT + 0.0004, SYDNEY_LNG, accuracyM = 50f) // about 44 m north
        val s = detectStays(listOf(accurate, sloppy), params).single()
        assertTrue(haversineMeters(s.lat, s.lng, SYDNEY_LAT, SYDNEY_LNG) < 2.0)
    }

    @Test
    fun aSingleFarFixDoesNotCloseTheStay() {
        val pings = builder().stay(30 * MIN).spikes(1, accuracyM = 10f, offsetM = 500.0).stay(30 * MIN).build()
        assertEquals(1, detectStays(pings, params).size)
    }

    @Test
    fun fewFarFixesSpanningUnderTwoMinutesAreOutliers() {
        val pings = builder().stay(30 * MIN).spikes(3, accuracyM = 10f, offsetM = 500.0).stay(30 * MIN).build()
        assertEquals(1, detectStays(pings, params).size) // three fixes 30 s apart span 60 s
    }

    @Test
    fun sustainedFarFixesCloseTheStay() {
        // Moved 500 m and stayed there: the far fixes agree with each other.
        val pings = builder().stay(30 * MIN).moveBy(90.0, 500.0, 25.0, accuracyM = 10f).stay(30 * MIN).build()
        assertEquals(2, detectStays(pings, params).size)
    }

    @Test
    fun farFixesThatDisagreeWithEachOtherDoNotCloseTheStay() {
        // Six network-grade fixes 500 m away in random directions span 150 s but never agree on where the phone went.
        val pings = builder().stay(30 * MIN).spikes(6, accuracyM = 80f, offsetM = 500.0).stay(30 * MIN).build()
        assertEquals(1, detectStays(pings, params).size)
    }

    @Test
    fun ambiguousFixesDoNotResetAProgressingDeparture() {
        // A slow boat leaving at 2 m/s: a clear fix every 20 s, each followed by a fix with a 400 m accuracy circle that
        // still reaches the stay (ambiguous) until the boat is 460 m away. They must not restart the departure (9 steps = 360 m).
        val b = builder().stay(30 * MIN, accuracyM = 10f)
        repeat(9) {
            b.moveBy(90.0, 40.0, 2.0, accuracyM = 10f)
            b.wander(1_000, intervalMs = 1_000, radiusM = 1.0, accuracyM = 400f..400f, activity = null)
        }
        val stays = detectStays(b.build(), params)
        assertFalse(stays.first().open) // closed by the departure, not left open by the ambiguous fixes
    }


    @Test
    fun anOldLoneOutsideFixAndAMuchLaterOneDoNotMakeADeparture() {
        // One poor fix 200 m out at 31 min, ambiguous fixes (the accuracy circle still reaches the stay) for 40 minutes,
        // then a second poor fix at the same spot. They agree, but 40 minutes apart they are two outliers, not a
        // departure: the stay must not close at the first one.
        val base = builder().stay(30 * MIN, accuracyM = 10f).build()
        val far = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 200.0, 0.0)
        val ambiguous = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 100.0, 0.0)
        val first = t0 + 31 * MIN
        val pings = base +
            Ping(first, far.first, far.second, 60f) +
            (1..19).map { Ping(first + it * 2 * MIN, ambiguous.first, ambiguous.second, 60f) } +
            Ping(first + 40 * MIN, far.first, far.second, 60f) +
            (1..10).map { Ping(first + 40 * MIN + it * MIN, SYDNEY_LAT, SYDNEY_LNG, 10f) }
        val stays = detectStays(pings, params)
        assertEquals(1, stays.size)
        assertTrue(stays.single().range.endMs >= first + 40 * MIN)
    }

    @Test
    fun aDepartureThatStaysWithinTheMaxSpanStillCloses() {
        val base = builder().stay(30 * MIN, accuracyM = 10f).build()
        val far = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 200.0, 0.0)
        val first = t0 + 31 * MIN
        val pings = base + (0..3).map { Ping(first + it * 2 * MIN, far.first, far.second + it * 1e-5, 60f) }
        val stays = detectStays(pings, params)
        assertFalse(stays.first().open) // closed by the departure; the excursion is re-read as what comes next
    }
    @Test
    fun emptyInputGivesNoStays() {
        assertTrue(detectStays(emptyList(), params).isEmpty())
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
