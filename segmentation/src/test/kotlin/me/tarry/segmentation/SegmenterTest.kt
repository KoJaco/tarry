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

    @Test
    fun anOvernightBreakBetweenSessionsIsNotATrip() {
        // Day 1 at site A until the window ends, day 2 at home 5 km away: nothing was captured overnight.
        val day1 = builder().stay(10 * HOUR).build()
        val bLat = SITE_B.first
        val bLng = SITE_B.second
        val day2Start = day1.last().timeMs + 14 * HOUR
        val day2 = TraceBuilder(seed = 6, startLat = bLat, startLng = bLng, startTimeMs = day2Start).stay(3 * HOUR).build()
        val pings = day1 + day2
        val sessions = listOf(TimeRange(t0, day1.last().timeMs + 1), TimeRange(day2Start, day2.last().timeMs + 1))
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, sessions = sessions))
        assertEquals(2, r.stays.size)
        assertTrue(r.trips.isEmpty())
    }

    @Test
    fun anOvernightBreakAtTheSamePlaceIsNotATripEither() {
        val day1 = builder().stay(10 * HOUR).build()
        val day2Start = day1.last().timeMs + 14 * HOUR
        val day2 = TraceBuilder(seed = 8, startTimeMs = day2Start).stay(3 * HOUR).build()
        val pings = day1 + day2
        val sessions = listOf(TimeRange(t0, day1.last().timeMs + 1), TimeRange(day2Start, day2.last().timeMs + 1))
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, sessions = sessions))
        assertEquals(2, r.stays.size)
        assertTrue(r.trips.isEmpty())
        assertEquals(r.stays[0].place, r.stays[1].place)
    }

    @Test
    fun excludedTimeIsNeitherAGapNorPartOfATrip() {
        // A deleted visit in the middle of a drive day: its time is the user's, not a missing signal.
        val pings = builder().stay(HOUR).moveBy(0.0, 3_000.0, 12.5).stay(2 * HOUR).moveBy(0.0, 3_000.0, 12.5).stay(HOUR).build()
        val session = TimeRange(t0, pings.last().timeMs + 1)
        val excluded = TimeRange(t0 + HOUR + 10 * MIN, t0 + 3 * HOUR)
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, sessions = listOf(session), excluded = listOf(excluded)))
        assertTrue(r.gaps.isEmpty())
        r.trips.forEach { assertFalse(it.range.overlaps(excluded)) }
        r.stays.forEach { assertFalse(it.range.overlaps(excluded)) }
    }

    @Test
    fun aStayMergedAcrossAShortWalkKeepsItsShapeOnceItsPlaceExists() {
        // Two hours at X, a 120 m walk, ten minutes at Y: one stay (the walk is under minTripDistance). Feeding the
        // new place back in must not split Y off (it lies outside a default 75 m radius around X).
        val pings = builder().stay(2 * HOUR).moveBy(90.0, 120.0, 1.2, mode = Activity.WALKING).stay(10 * MIN).build()
        val first = Segmenter.segment(SegmentationInput(pings.range(), pings))
        assertEquals(1, first.stays.size)
        val place = first.newPlaces.single()
        val known = KnownPlace("p", place.lat, place.lng, place.radiusM.toFloat())
        val second = Segmenter.segment(SegmentationInput(pings.range(), pings, places = listOf(known)))
        assertEquals(first.stays.map { it.range }, second.stays.map { it.range })
        assertEquals(PlaceRef.Existing("p"), second.stays.single().place)
        assertTrue(second.newPlaces.isEmpty())
    }

    @Test
    fun aDifferentSiteNearAnExistingPlaceOnALaterDayGetsItsOwnPlace() {
        // A place from an earlier day, and today a job 100 m from its centroid: outside its default radius.
        val known = KnownPlace("old", me.tarry.segmentation.trace.SYDNEY_LAT, me.tarry.segmentation.trace.SYDNEY_LNG, 75f)
        val (zLat, zLng) = me.tarry.segmentation.trace.offsetMeters(known.lat, known.lng, 100.0, 0.0)
        val pings = TraceBuilder(seed = 9, startLat = zLat, startLng = zLng, startTimeMs = t0).stay(2 * HOUR).build()
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, places = listOf(known)))
        assertEquals(PlaceRef.New(0), r.stays.single().place)
        assertEquals(75.0, r.newPlaces.single().radiusM, 0.0)
    }

    @Test
    fun anOngoingStayThatDriftsBetweenRunsKeepsItsPlace() {
        // Run 1 sees an hour; run 2 sees two more hours, centred 25 m away (indoor drift), and knows run 1's place.
        val first = builder().stay(HOUR).build()
        val (dLat, dLng) = me.tarry.segmentation.trace.offsetMeters(
            me.tarry.segmentation.trace.SYDNEY_LAT, me.tarry.segmentation.trace.SYDNEY_LNG, 25.0, 0.0,
        )
        val later = TraceBuilder(seed = 10, startLat = dLat, startLng = dLng, startTimeMs = first.last().timeMs + MIN)
            .stay(2 * HOUR, jitterM = 20.0).build()
        val r1 = Segmenter.segment(SegmentationInput(first.range(), first))
        val p = r1.newPlaces.single()
        val pings = first + later
        val r2 = Segmenter.segment(SegmentationInput(pings.range(), pings, places = listOf(KnownPlace("p", p.lat, p.lng, p.radiusM.toFloat()))))
        assertEquals(1, r2.stays.size)
        assertEquals(PlaceRef.Existing("p"), r2.stays.single().place)
        assertTrue(r2.newPlaces.isEmpty())
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        val SITE_B = me.tarry.segmentation.trace.offsetMeters(
            me.tarry.segmentation.trace.SYDNEY_LAT,
            me.tarry.segmentation.trace.SYDNEY_LNG,
            0.0,
            5_000.0,
        )
    }
}
