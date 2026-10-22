package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters
import me.tarry.segmentation.trace.SYDNEY_LAT
import me.tarry.segmentation.trace.SYDNEY_LNG
import me.tarry.segmentation.trace.TraceBuilder
import me.tarry.segmentation.trace.atLocal
import me.tarry.segmentation.trace.offsetMeters
import me.tarry.segmentation.trace.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The MVP golden cases (docs/specs/phase-2b.md T9), all through [Segmenter.segment]. */
class GoldenCasesTest {
    private val params = SegmentationParams()
    private val t0 = 1_760_000_000_000L
    private fun builder(seed: Long = 11) = TraceBuilder(seed = seed, startTimeMs = t0)
    private fun run(pings: List<Ping>, places: List<KnownPlace> = emptyList(), sessions: List<TimeRange> = emptyList()) =
        Segmenter.segment(SegmentationInput(pings.range(), pings, places = places, sessions = sessions), params)

    @Test
    fun singleSiteAllDay() {
        val pings = builder().stay(10 * HOUR, intervalMs = 3 * MIN).build()
        val r = run(pings)
        assertEquals(1, r.stays.size)
        assertTrue(r.stays.single().mayBeOngoing)
        assertTrue(r.trips.isEmpty())
        assertTrue(r.gaps.isEmpty())
        assertEquals(1, r.newPlaces.size)
    }

    @Test
    fun twoSitesWithADrive() {
        val pings = builder().stay(HOUR).moveBy(90.0, 6_000.0, 12.5).stay(HOUR).build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val trip = r.trips.single()
        assertEquals(TripMode.VEHICLE, trip.mode)
        assertEquals(6_000.0, trip.distanceM, 200.0)
        assertEquals(0, trip.fromStay)
        assertEquals(1, trip.toStay)
        assertEquals(2, r.newPlaces.size)
        assertEquals(listOf(PlaceRef.New(0), PlaceRef.New(1)), r.stays.map { it.place })
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun marinaIsOneStayWithoutPlacesAndTwoWithOneSmallKnownPlace() {
        val boat1 = SYDNEY_LAT to SYDNEY_LNG
        val boat2 = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 0.0, 40.0)
        val pings = builder(3)
            .stay(HOUR, jitterM = 5.0, accuracyM = 8f)
            .move(boat2.first, boat2.second, speedMps = 1.0, mode = Activity.WALKING, accuracyM = 8f)
            .stay(HOUR, jitterM = 5.0, accuracyM = 8f)
            .build()
        assertEquals(1, run(pings).stays.size)

        val known = KnownPlace("boat1", boat1.first, boat1.second, radiusM = 18f)
        val r = run(pings, listOf(known))
        assertEquals(2, r.stays.size)
        assertEquals(PlaceRef.Existing("boat1"), r.stays[0].place)
        assertEquals(PlaceRef.New(0), r.stays[1].place)
        assertEquals(1, r.newPlaces.size)
        assertTrue(haversineMeters(r.newPlaces[0].lat, r.newPlaces[0].lng, boat2.first, boat2.second) < 15.0)
    }

    @Test
    fun indoorDriftSpikesInsideALongStayStillGiveOneStay() {
        val pings = builder()
            .stay(HOUR).spikes(6, accuracyM = 100f, offsetM = 150.0)
            .stay(HOUR).spikes(6, accuracyM = 100f, offsetM = 150.0)
            .stay(HOUR)
            .build()
        val r = run(pings)
        assertEquals(1, r.stays.size)
        assertTrue(r.trips.isEmpty())
    }

    @Test
    fun aFortyMinuteSignalGapInsideOneStayIsBridged() {
        val pings = builder().stay(HOUR).gap(40 * MIN).stay(HOUR).build()
        val session = TimeRange(t0, pings.last().timeMs + 1)
        val r = run(pings, sessions = listOf(session))
        val stay = r.stays.single()
        assertEquals(params.bridgedConfidence, stay.confidence)
        assertTrue(stay.bridged)
        assertTrue(r.gaps.isEmpty())
        assertTrue(r.trips.isEmpty())
    }

    @Test
    fun aFuelStopUnderFiveMinutesIsPartOfTheTrip() {
        val pings = builder()
            .stay(HOUR)
            .moveBy(0.0, 4_000.0, 12.5)
            .stay(4 * MIN, activity = Activity.STILL)
            .moveBy(0.0, 4_000.0, 12.5)
            .stay(HOUR)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val trip = r.trips.single()
        assertEquals(0, trip.fromStay)
        assertEquals(1, trip.toStay)
        assertEquals(8_000.0, trip.distanceM, 300.0)
    }

    @Test
    fun aQuickReturnToTheSameSiteIsMerged() {
        val pings = builder()
            .stay(HOUR)
            .moveBy(0.0, 400.0, 10.0)
            .moveBy(180.0, 400.0, 10.0)
            .stay(HOUR)
            .build()
        val r = run(pings)
        assertEquals(1, r.stays.size)
        assertEquals(1, r.newPlaces.size)
        assertTrue(r.trips.isEmpty())
    }

    @Test
    fun aTripCrossingMidnightInSydney() {
        val day = LocalDate.of(2026, 3, 10)
        val start = atLocal(day, LocalTime.of(21, 30))
        val pings = TraceBuilder(seed = 8, startTimeMs = start)
            .stay(2 * HOUR)
            .moveBy(0.0, 36_000.0, 10.0)
            .stay(2 * HOUR)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val trip = r.trips.single()
        assertEquals(TripMode.VEHICLE, trip.mode)
        assertEquals(36_000.0, trip.distanceM, 500.0)
        val midnight = atLocal(day.plusDays(1), LocalTime.MIDNIGHT)
        assertTrue(trip.range.startMs < midnight && trip.range.endMs > midnight)
        // The stays sit on either side of local midnight, on different local dates.
        val zone = ZoneId.of("Australia/Sydney")
        val dates = r.stays.map { java.time.Instant.ofEpochMilli(it.range.startMs).atZone(zone).toLocalDate() }
        assertEquals(listOf(day, day.plusDays(1)), dates)
        assertFalse(r.stays[0].range.overlaps(trip.range))
    }

    @Test
    fun parkThenWalkIn() {
        val home = builder(21).stay(30 * MIN, intervalMs = 3 * MIN)
        home.moveBy(90.0, 5_000.0, 12.5)
        val parked = home.currentTimeMs
        val pings = home
            .wander(15 * MIN, radiusM = 120.0, accuracyM = 30f..98f, activity = Activity.WALKING)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val trip = r.trips.single()
        assertEquals(TripMode.VEHICLE, trip.mode)
        // The drive ends at the moment the vehicle stopped and the visit starts there, not 15 min later.
        assertEquals(parked, trip.range.endMs)
        assertEquals(parked, r.stays[1].range.startMs)
        assertEquals(5_000.0, trip.distanceM, 250.0)
    }

    @Test
    fun marinaJitter() {
        val pings = builder(5)
            .hops(40 * MIN)
            .wander(90_000, radiusM = 40.0, accuracyM = 30f..98f, activity = Activity.IN_VEHICLE)
            .hops(40 * MIN)
            .wander(90_000, radiusM = 40.0, accuracyM = 30f..98f, activity = Activity.IN_VEHICLE)
            .hops(40 * MIN)
            .build()
        val r = run(pings)
        assertEquals(1, r.stays.size)
        assertTrue(r.trips.isEmpty())
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun staleRepeats() {
        val b = builder(9).stay(30 * MIN, intervalMs = 3 * MIN).spikes(1, accuracyM = 100f, offsetM = 300.0)
        val spike = b.pingCount - 1
        val pings = b.stay(30 * MIN, intervalMs = 3 * MIN)
            .repeatEarlier(spike, count = 8, intervalMs = MIN, accuracyM = 100f)
            .stay(30 * MIN, intervalMs = 3 * MIN)
            .build()
        val r = run(pings)
        assertEquals(1, r.stays.size)
        assertTrue(r.trips.isEmpty())
        assertTrue(r.gaps.isEmpty())
        // Without the stale rule the repeats would form a phantom visit, so the case really exercises it.
        val unfiltered = Segmenter.segment(SegmentationInput(pings.range(), pings), SegmentationParams(maxAccuracyM = 1_000f))
        assertTrue(unfiltered.stays.size > 1)
    }

    @Test
    fun driveThenWalkAway() {
        val pings = builder(14)
            .stay(30 * MIN, intervalMs = 3 * MIN)
            .moveBy(90.0, 5_000.0, 12.5)
            .moveBy(90.0, 600.0, 1.4, mode = Activity.WALKING, accuracyM = 15f)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        assertEquals(listOf(TripMode.VEHICLE, TripMode.WALK), r.trips.map { it.mode })
        val (drive, walk) = r.trips
        assertEquals(5_000.0, drive.distanceM, 250.0)
        // The last 200 m of the walk belong to the visit; the rest is its own on-foot trip.
        assertEquals(400.0, walk.distanceM, 100.0)
        assertEquals(0, drive.fromStay)
        assertEquals(1, walk.toStay)
        assertEquals(drive.range.endMs, walk.range.startMs)
        assertEquals(walk.range.endMs, r.stays[1].range.startMs)
    }

    @Test
    fun trafficStops() {
        val pings = builder(17)
            .stay(HOUR, intervalMs = 3 * MIN)
            .moveBy(0.0, 2_000.0, 12.5).stay(2 * MIN, intervalMs = 30_000, jitterM = 5.0)
            .moveBy(0.0, 2_000.0, 12.5).stay(3 * MIN, intervalMs = 30_000, jitterM = 5.0)
            .moveBy(0.0, 2_000.0, 12.5).stay(4 * MIN, intervalMs = 30_000, jitterM = 5.0)
            .moveBy(0.0, 2_000.0, 12.5)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val trip = r.trips.single()
        assertEquals(TripMode.VEHICLE, trip.mode)
        assertEquals(8_000.0, trip.distanceM, 300.0)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
