package me.tarry.segmentation

import me.tarry.segmentation.trace.Trace
import me.tarry.segmentation.trace.TraceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripsTest {
    private val params = SegmentationParams()
    private val t0 = 1_760_000_000_000L
    private val min = 60_000L
    private fun builder() = TraceBuilder(seed = 5, startTimeMs = t0)

    private fun trips(pings: Trace): List<TripCandidate> {
        val stays = mergeStays(detectStays(pings, params), pings, params)
        return buildTrips(stays, pings)
    }

    @Test
    fun driveBetweenTwoStaysIsOneVehicleTrip() {
        val pings = builder().stay(10 * min).moveBy(90.0, 5_000.0, 12.5).stay(10 * min).build()
        val t = trips(pings).single()
        assertEquals(0, t.fromStay)
        assertEquals(1, t.toStay)
        assertEquals(5_000.0, t.distanceM, 100.0)
        assertEquals(TripMode.VEHICLE, t.mode)
    }

    @Test
    fun walkIsWalkMode() {
        val pings = builder().stay(10 * min).moveBy(90.0, 1_500.0, 1.4, mode = Activity.WALKING).stay(10 * min).build()
        assertEquals(TripMode.WALK, trips(pings).single().mode)
    }

    @Test
    fun nullActivitiesStillGetAModeFromHopSpeeds() {
        val pings = builder().stay(10 * min).moveBy(90.0, 3_000.0, 12.5).stay(10 * min).build()
            .map { it.copy(activity = null) }
        assertEquals(TripMode.VEHICLE, trips(pings).single().mode)
    }

    @Test
    fun noActivityAndOnlyPoorFixesGiveUnknown() {
        val pings = builder().stay(10 * min).moveBy(90.0, 3_000.0, 12.5, accuracyM = 80f).stay(10 * min).build()
            .map { it.copy(activity = null) }
        assertEquals(TripMode.UNKNOWN, trips(pings).single().mode)
    }

    @Test
    fun leadingPingsFormTripWithNullFrom() {
        val pings = builder().moveBy(90.0, 2_000.0, 12.5).stay(10 * min).build()
        val stays = mergeStays(detectStays(pings, params), pings, params)
        assertEquals(1, stays.size)
        val t = buildTrips(stays, pings).first()
        assertNull(t.fromStay)
        assertEquals(0, t.toStay)
        assertTrue(t.distanceM > 1_000.0)
        assertEquals(TripMode.VEHICLE, t.mode)
    }

    @Test
    fun noStaysGivesOneNullToNullTrip() {
        val pings = builder().moveBy(90.0, 2_000.0, 12.5).build()
        val t = buildTrips(emptyList(), pings).single()
        assertNull(t.fromStay)
        assertNull(t.toStay)
        assertEquals(pings.first().timeMs, t.range.startMs)
        assertEquals(pings.last().timeMs, t.range.endMs)
        assertEquals(1_750.0, t.distanceM, 100.0) // ping path only: no stay centroid before the first ping
    }

    @Test
    fun majorityActivityWinsInMixedTrip() {
        val walkHeavy = builder().stay(10 * min).moveBy(90.0, 3_000.0, 12.5)
            .moveBy(90.0, 1_000.0, 1.4, mode = Activity.WALKING).stay(10 * min).build()
        // Changed by dogfood-1: a vehicle part ending beyond arrivalRadiusM, then on foot, splits into two legs.
        assertEquals(listOf(TripMode.VEHICLE, TripMode.WALK), trips(walkHeavy).map { it.mode })
        val driveHeavy = builder().stay(10 * min).moveBy(90.0, 5_000.0, 12.5)
            .moveBy(90.0, 100.0, 1.4, mode = Activity.WALKING).stay(10 * min).build()
        assertEquals(TripMode.VEHICLE, trips(driveHeavy).single().mode)
    }

    /** Stays as the Segmenter keeps them: a trailing open candidate shorter than the minimum stay is not a stay yet. */
    private fun settledStays(pings: Trace): List<StayCandidate> {
        var detected = detectStays(pings, params)
        val last = detected.lastOrNull()
        if (last != null && last.open && last.range.durationMs < params.minStayDurationMs) detected = detected.dropLast(1)
        return mergeStays(detected, pings, params)
    }

    @Test
    fun leadingAndTrailingMovesUnderTheMinimumTripDistanceAreNotTrips() {
        val short = builder().moveBy(90.0, 100.0, 1.4, mode = Activity.WALKING, accuracyM = 8f)
            .stay(10 * min, accuracyM = 8f)
            .moveBy(90.0, 100.0, 1.4, mode = Activity.WALKING, accuracyM = 8f)
            .build()
        val stays = settledStays(short)
        assertEquals(1, stays.size)
        assertTrue(buildTrips(stays, short).isEmpty())
    }

    @Test
    fun leadingAndTrailingMovesOverTheMinimumTripDistanceAreTrips() {
        val long = builder().moveBy(90.0, 400.0, 1.4, mode = Activity.WALKING, accuracyM = 8f)
            .stay(10 * min, accuracyM = 8f)
            .moveBy(90.0, 400.0, 1.4, mode = Activity.WALKING, accuracyM = 8f)
            .build()
        val stays = settledStays(long)
        val trips = buildTrips(stays, long)
        assertEquals(2, trips.size)
        assertNull(trips.first().fromStay)
        assertNull(trips.last().toStay)
        assertTrue(trips.all { it.distanceM >= params.minTripDistanceM })
    }
}
