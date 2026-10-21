package me.tarry.segmentation

import me.tarry.segmentation.trace.TraceBuilder
import me.tarry.segmentation.trace.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    private val t0 = 1_760_000_000_000L
    private fun builder(seed: Long = 5) = TraceBuilder(seed = seed, startTimeMs = t0)

    @Test
    fun twoSitesWithADrive() {
        val pings = builder().stay(HOUR).moveBy(90.0, 5_000.0, 12.5).stay(HOUR).build()
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings))
        assertEquals(2, r.stays.size)
        assertEquals(1, r.trips.size)
        val trip = r.trips.single()
        assertEquals(0, trip.fromStay)
        assertEquals(1, trip.toStay)
        assertEquals(TripMode.VEHICLE, trip.mode)
        assertEquals(5_000.0, trip.distanceM, 150.0)
        assertEquals(listOf(PlaceRef.New(0), PlaceRef.New(1)), r.stays.map { it.place })
        assertEquals(2, r.newPlaces.size)
        assertFalse(r.stays[0].mayBeOngoing)
        assertTrue(r.stays[1].mayBeOngoing)
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun frozenRangeIsAFixedBoundary() {
        val pings = builder().stay(HOUR * 3).build()
        val frozen = TimeRange(t0 + HOUR, t0 + 2 * HOUR)
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, frozen = listOf(frozen)))
        assertEquals(2, r.stays.size)
        r.stays.forEach { assertFalse(it.range.overlaps(frozen)) }
        r.trips.forEach { assertFalse(it.range.overlaps(frozen)) }
        // The stay before the frozen record ends there; it is not ongoing.
        assertFalse(r.stays[0].mayBeOngoing)
        assertTrue(r.stays[1].mayBeOngoing)
    }

    @Test
    fun excludedPingsAreIgnored() {
        val pings = builder().stay(HOUR).moveBy(0.0, 3_000.0, 12.5).stay(HOUR).build()
        val excluded = TimeRange(t0, t0 + HOUR)
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, excluded = listOf(excluded)))
        assertEquals(1, r.stays.size)
        assertTrue(r.stays.single().range.startMs >= excluded.endMs)
    }

    @Test
    fun aLongSilenceAtOneSpotIsAGapBetweenTwoStaysAndNoTrip() {
        val pings = builder().stay(HOUR).gap(60 * MIN).stay(HOUR).build()
        val session = TimeRange(t0, pings.last().timeMs + 1)
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, sessions = listOf(session)))
        assertEquals(2, r.stays.size)
        assertEquals(GapCause.NO_FIX, r.gaps.single().cause)
        assertTrue(r.trips.isEmpty())
        assertEquals(PlaceRef.New(0), r.stays[1].place) // same new place both times
    }

    @Test
    fun aTripIsCutAroundASignalGap() {
        // Drive, then 60 min of silence, then more driving, then a stay.
        val pings = builder()
            .stay(HOUR)
            .moveBy(0.0, 2_000.0, 12.5)
            .gap(60 * MIN)
            .moveBy(0.0, 2_000.0, 12.5)
            .stay(HOUR)
            .build()
        val session = TimeRange(t0, pings.last().timeMs + 1)
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, sessions = listOf(session)))
        val gap = r.gaps.single()
        assertEquals(2, r.trips.size)
        r.trips.forEach { assertFalse(it.range.overlaps(gap.range)) }
        assertEquals(0, r.trips[0].fromStay)
        assertEquals(null, r.trips[0].toStay)
        assertEquals(null, r.trips[1].fromStay)
        assertEquals(1, r.trips[1].toStay)
    }

    @Test
    fun arrivingButNotYetStayingIsATrailingTrip() {
        val pings = builder().stay(HOUR).moveBy(0.0, 3_000.0, 12.5).stay(2 * MIN).build()
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings))
        assertEquals(1, r.stays.size)
        assertFalse(r.stays.single().mayBeOngoing)
        assertEquals(null, r.trips.single().toStay)
    }

    @Test
    fun gapsNeverOverlapStays() {
        val pings = builder().stay(3 * HOUR).build()
        // A window with no session at all: NO_SESSION, but the pings prove the user was at the stay.
        val window = TimeRange(t0 - HOUR, t0 + 4 * HOUR)
        val r = Segmenter.segment(SegmentationInput(window, pings, windows = listOf(window)))
        val stay = r.stays.single()
        r.gaps.forEach { assertFalse(it.range.overlaps(stay.range)) }
        assertEquals(2, r.gaps.size)
    }

    @Test
    fun outputIsDeterministic() {
        val pings = builder().stay(HOUR).moveBy(45.0, 4_000.0, 10.0).stay(HOUR).spikes(4).stay(HOUR).build()
        val input = SegmentationInput(pings.range(), pings)
        assertEquals(Segmenter.segment(input), Segmenter.segment(input))
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
