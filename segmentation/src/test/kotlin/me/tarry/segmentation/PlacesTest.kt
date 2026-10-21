package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters
import me.tarry.segmentation.trace.SYDNEY_LAT
import me.tarry.segmentation.trace.SYDNEY_LNG
import me.tarry.segmentation.trace.TraceBuilder
import me.tarry.segmentation.trace.offsetMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private val params = SegmentationParams()
    private val t0 = 1_760_000_000_000L

    private val boat1 = SYDNEY_LAT to SYDNEY_LNG
    private val boat2 = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 0.0, 40.0)

    /** An hour at boat 1, a 40 m walk, an hour at boat 2. */
    private fun marina(jitterM: Double = 5.0) = TraceBuilder(seed = 3, startTimeMs = t0)
        .stay(HOUR, jitterM = jitterM, accuracyM = 8f)
        .move(boat2.first, boat2.second, speedMps = 1.0, mode = Activity.WALKING, accuracyM = 8f)
        .stay(HOUR, jitterM = jitterM, accuracyM = 8f)
        .build()

    private fun pipeline(pings: List<Ping>, places: List<KnownPlace>): List<StayCandidate> {
        val split = splitStaysByPlace(detectStays(pings, params), places, params)
        return mergeStays(split, pings, params, distinctKnownPlaces(places))
    }

    @Test
    fun nearestContainingPlaceMatchesAndMergedPlacesNever() {
        val near = KnownPlace("near", boat1.first, boat1.second, radiusM = 50f)
        val far = KnownPlace("far", boat2.first, boat2.second, radiusM = 100f)
        assertEquals("near", matchPlace(boat1.first, boat1.second, listOf(far, near))?.id)
        assertEquals("far", matchPlace(boat1.first, boat1.second, listOf(far, near.copy(merged = true)))?.id)
        assertEquals("near", matchPlace(boat1.first, boat1.second, listOf(near.copy(ignored = true)))?.id)
        assertNull(matchPlace(boat1.first, boat1.second, listOf(far.copy(radiusM = 20f))))
    }

    @Test
    fun marinaWithoutPlacesIsOneStay() {
        assertEquals(1, pipeline(marina(), emptyList()).size)
    }

    @Test
    fun marinaWithOneWidePlaceIsOneStay() {
        val wide = KnownPlace("marina", boat1.first, boat1.second, radiusM = 75f)
        assertEquals(1, pipeline(marina(), listOf(wide)).size)
    }

    @Test
    fun marinaWithTwoSmallPlacesSplitsIntoTwoStays() {
        val places = listOf(
            KnownPlace("b1", boat1.first, boat1.second, radiusM = 18f),
            KnownPlace("b2", boat2.first, boat2.second, radiusM = 18f),
        )
        val stays = pipeline(marina(), places)
        assertEquals(2, stays.size)
        assertEquals("b1", matchPlace(stays[0].lat, stays[0].lng, places)?.id)
        assertEquals("b2", matchPlace(stays[1].lat, stays[1].lng, places)?.id)
        assertTrue(stays[1].open)
    }

    @Test
    fun shrinkingThePlaceRadiusSplitsOffTheOtherBoat() {
        val shrunk = KnownPlace("marina", boat1.first, boat1.second, radiusM = 18f)
        val stays = pipeline(marina(), listOf(shrunk))
        assertEquals(2, stays.size)
        val assignment = assignPlaces(stays, listOf(shrunk), params)
        assertEquals(PlaceRef.Existing("marina"), assignment.refs[0])
        assertEquals(PlaceRef.New(0), assignment.refs[1])
        val created = assignment.newPlaces.single()
        assertTrue(haversineMeters(created.lat, created.lng, boat2.first, boat2.second) < 10.0)
    }

    @Test
    fun edgeNoiseAroundASmallPlaceDoesNotSplit() {
        // Jitter up to 30 m around boat 1 with an 18 m place: many pings fall outside, in short runs.
        val pings = TraceBuilder(seed = 11, startTimeMs = t0).stay(HOUR * 2, jitterM = 30.0, accuracyM = 15f).build()
        val small = KnownPlace("b1", boat1.first, boat1.second, radiusM = 18f)
        assertEquals(1, pipeline(pings, listOf(small)).size)
    }

    @Test
    fun unmatchedStaysNearEachOtherShareOneNewPlace() {
        val a = stayAt(boat1, 0, HOUR)
        val b = stayAt(offsetMeters(boat1.first, boat1.second, 30.0, 0.0), 3 * HOUR, 4 * HOUR)
        val c = stayAt(offsetMeters(boat1.first, boat1.second, 0.0, 2_000.0), 5 * HOUR, 6 * HOUR)
        val assignment = assignPlaces(listOf(a, b, c), emptyList(), params)
        assertEquals(listOf(PlaceRef.New(0), PlaceRef.New(0), PlaceRef.New(1)), assignment.refs)
        val first = assignment.newPlaces[0]
        assertTrue(haversineMeters(first.lat, first.lng, boat1.first, boat1.second) in 14.0..16.0)
    }

    @Test
    fun matchedPlaceCentroidMovesByDurationWeight() {
        val place = KnownPlace("p", boat1.first, boat1.second, radiusM = 75f, weightMs = 3 * HOUR)
        val shifted = offsetMeters(boat1.first, boat1.second, 40.0, 0.0)
        val stay = stayAt(shifted, 0, HOUR)
        val update = assignPlaces(listOf(stay), listOf(place), params).updates.single()
        assertEquals("p", update.placeId)
        // One hour at +40 m against three hours at 0 m: +10 m.
        assertEquals(10.0, haversineMeters(update.lat, update.lng, boat1.first, boat1.second), 0.5)
    }

    @Test
    fun aPlaceWithNoPriorWeightMovesToItsStays() {
        val place = KnownPlace("p", boat1.first, boat1.second, radiusM = 75f)
        val shifted = offsetMeters(boat1.first, boat1.second, 40.0, 0.0)
        val update = assignPlaces(listOf(stayAt(shifted, 0, HOUR)), listOf(place), params).updates.single()
        assertEquals(0.0, haversineMeters(update.lat, update.lng, shifted.first, shifted.second), 0.5)
    }

    private fun stayAt(at: Pair<Double, Double>, fromMs: Long, toMs: Long) = StayCandidate(
        range = TimeRange(t0 + fromMs, t0 + toMs),
        lat = at.first,
        lng = at.second,
        pings = listOf(Ping(t0 + fromMs, at.first, at.second, 10f), Ping(t0 + toMs, at.first, at.second, 10f)),
        bridged = false,
        open = false,
    )

    private companion object {
        const val HOUR = 3_600_000L
    }
}
