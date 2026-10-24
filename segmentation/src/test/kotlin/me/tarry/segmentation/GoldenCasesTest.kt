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

    /** The marina shape with a phantom place 70 m from the real one: GPS cannot tell them apart, so one stay. */
    @Test
    fun marinaJitterWithTwoNearbyKnownPlaces() {
        // The boat sits at A, drifts to the other side of the marina (B, 70 m east) for 16 min, and comes back.
        val a = SYDNEY_LAT to SYDNEY_LNG
        val b = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 0.0, 70.0)
        val pings = builder(5)
            .hops(40 * MIN)
            .wander(90_000, radiusM = 40.0, accuracyM = 30f..98f, activity = Activity.IN_VEHICLE)
            .move(b.first, b.second, speedMps = 1.0, mode = Activity.STILL, accuracyM = 30f)
            .hops(16 * MIN)
            .move(a.first, a.second, speedMps = 1.0, mode = Activity.STILL, accuracyM = 30f)
            .hops(75 * MIN)
            .build()
        val places = listOf(
            KnownPlace("main", a.first, a.second, radiusM = 75f),
            KnownPlace("phantom", b.first, b.second, radiusM = 75f),
        )
        val r = run(pings, places)
        assertEquals(1, r.stays.size)
        assertEquals(PlaceRef.Existing("main"), r.stays.single().place)
        assertTrue(r.trips.isEmpty())
        assertTrue(r.newPlaces.isEmpty())
    }

    /** Places far enough apart for GPS to separate still split a day into two stays around a real walk. */
    @Test
    fun twoKnownPlaces300mApartWithAFiveMinuteWalkStayTwoStays() {
        val a = SYDNEY_LAT to SYDNEY_LNG
        val b = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, 0.0, 300.0)
        val pings = builder(61)
            .stay(HOUR, intervalMs = 3 * MIN)
            .moveBy(90.0, 300.0, 1.0, mode = Activity.WALKING, accuracyM = 15f)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val places = listOf(
            KnownPlace("a", a.first, a.second, radiusM = 75f),
            KnownPlace("b", b.first, b.second, radiusM = 75f),
        )
        val r = run(pings, places)
        assertEquals(2, r.stays.size)
        assertEquals(listOf(PlaceRef.Existing("a"), PlaceRef.Existing("b")), r.stays.map { it.place })
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
        // (Noise-scaled merging is off in the control arm: it would also fold a phantom visit this noisy back in.)
        val unfiltered = Segmenter.segment(
            SegmentationInput(pings.range(), pings),
            SegmentationParams(maxAccuracyM = 1_000f, placeNoiseFactor = 0.0),
        )
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

    @Test
    fun activityLagAfterDrivingIsOneVehicleTrip() {
        val pings = builder(31)
            .stay(30 * MIN, intervalMs = 3 * MIN)
            .moveBy(90.0, 5_000.0, 12.5)
            // Recognition lags: the car keeps rolling for ~800 m after IN_VEHICLE ended.
            .moveBy(90.0, 800.0, 12.5, mode = Activity.WALKING)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val trip = r.trips.single()
        assertEquals(TripMode.VEHICLE, trip.mode)
        assertEquals(5_800.0, trip.distanceM, 400.0)
    }

    @Test
    fun walkBetweenTwoNearbyPlacesStaysATrip() {
        val pings = builder(41)
            .stay(HOUR, intervalMs = 3 * MIN)
            .moveBy(90.0, 170.0, 0.9, mode = Activity.WALKING, accuracyM = 20f)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val walk = r.trips.single()
        // Arrival attribution is capped at half the distance between the stays, so the walk is not swallowed.
        assertTrue(walk.range.durationMs >= 20_000)
        assertTrue(r.stays[0].range.endMs <= walk.range.startMs && walk.range.endMs <= r.stays[1].range.startMs)
    }

    @Test
    fun curvedRouteDistanceIsWithinTenPercent() {
        val radius = 300.0
        val b = builder(51).stay(30 * MIN, intervalMs = 3 * MIN)
        val accuracies = listOf(20f, 100f, 45f, 80f, 30f, 100f)
        for (k in 1..18) {
            val theta = Math.toRadians(15.0 * k)
            val (lat, lng) = offsetMeters(SYDNEY_LAT, SYDNEY_LNG, radius * kotlin.math.sin(theta), radius * (kotlin.math.cos(theta) - 1))
            b.move(lat, lng, speedMps = 8.0, intervalMs = 10_000, accuracyM = accuracies[k % accuracies.size])
        }
        val pings = b.stay(HOUR, intervalMs = 3 * MIN).build()
        val trip = run(pings).trips.single()
        val truth = radius * Math.toRadians(270.0)
        assertEquals(truth, trip.distanceM, truth * 0.10)
    }

    @Test
    fun poorSignalStopDoesNotInflateDriveDistance() {
        val pings = builder(71)
            .stay(30 * MIN, intervalMs = 3 * MIN)
            .moveBy(0.0, 4_000.0, 12.5)
            // A 4 min stop in a bad-signal spot: fixes jump 100-270 m every 20 s at 50-98 m accuracy.
            .hops(4 * MIN, jitterM = 100.0, hopMinM = 100.0, hopMaxM = 270.0, hopEvery = 2, accuracyM = 50f..98f,
                activity = Activity.IN_VEHICLE)
            .moveBy(0.0, 4_000.0, 12.5)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val trip = run(pings).trips.single()
        assertEquals(8_000.0, trip.distanceM, 400.0)
    }

    @Test
    fun goodAccuracyJitterDuringAStopDoesNotInflateDriveDistance() {
        val pings = builder(72)
            .stay(30 * MIN, intervalMs = 3 * MIN)
            .moveBy(0.0, 4_000.0, 12.5)
            // Jitter that passes the good-accuracy filter: 100-270 m hops at 30-50 m accuracy.
            .hops(4 * MIN, jitterM = 100.0, hopMinM = 100.0, hopMaxM = 270.0, hopEvery = 2, accuracyM = 30f..50f,
                activity = Activity.IN_VEHICLE)
            .moveBy(0.0, 4_000.0, 12.5)
            .stay(HOUR, intervalMs = 3 * MIN)
            .build()
        val trip = run(pings).trips.single()
        assertEquals(8_000.0, trip.distanceM, 400.0)
    }

    @Test
    fun jitterWithNoStayIsNotATrip() {
        val pings = builder(61).wander(3 * MIN, radiusM = 30.0, accuracyM = 10f..20f, activity = Activity.STILL).build()
        assertTrue(run(pings).trips.isEmpty())
    }

    @Test
    fun frozenAndExcludedRangesStayFreeOfStaysTripsAndGaps() {
        val pings = builder().stay(HOUR).moveBy(90.0, 6_000.0, 12.5).stay(HOUR).moveBy(90.0, 6_000.0, 12.5).stay(HOUR).build()
        val baseline = run(pings)
        assertEquals(3, baseline.stays.size)
        // The user trimmed the middle visit (frozen, narrower than the detected stay) and deleted the last one (excluded).
        val frozen = TimeRange(baseline.stays[1].range.startMs + 10 * MIN, baseline.stays[1].range.endMs - 10 * MIN)
        val excluded = baseline.stays[2].range
        val r = Segmenter.segment(SegmentationInput(pings.range(), pings, frozen = listOf(frozen), excluded = listOf(excluded)), params)
        val fixed = listOf(frozen, excluded)
        assertTrue(r.stays.isNotEmpty())
        assertTrue(r.stays.none { s -> fixed.any { s.range.overlaps(it) } })
        assertTrue(r.trips.none { t -> fixed.any { t.range.overlaps(it) } })
        assertTrue(r.gaps.none { g -> fixed.any { g.range.overlaps(it) } })
        // The first stay is untouched by the edits.
        assertEquals(baseline.stays[0].range, r.stays[0].range)
    }

    /**
     * A synthetic look-alike of the 2026-10-22 marina day (docs/specs/fix-dogfood-2.md), fake coordinates: seven hours
     * on a boat where the phone only gets network fixes, flipping every 20-60 s between three anchors 200-500 m apart
     * (50-100 m accuracy) and a 20 m anchor that repeats, plus km-scale out-and-back spikes. One stay, one place.
     */
    @Test
    fun boatDayNetworkAnchors() {
        val o = SYDNEY_LAT to SYDNEY_LNG
        val a = o
        val b = offsetMeters(o.first, o.second, 300.0, -100.0)
        val c = offsetMeters(o.first, o.second, -150.0, 300.0)
        val wifi = offsetMeters(o.first, o.second, 50.0, -10.0)
        val points = listOf(a, b, c, wifi, wifi)
        val accuracies = listOf(80f, 90f, 60f, 20f, 20f)
        val builder = builder(31)
        repeat(7) {
            builder.anchors(HOUR - 4 * MIN, points, accuracies, intervalMs = 40_000, intervalJitter = 0.5)
            builder.outAndBack(offsetM = 3_500.0, bearingDeg = 20.0 * it, fixes = 2, accuracyM = 75f)
            builder.anchors(4 * MIN, points, accuracies, intervalMs = 40_000, intervalJitter = 0.5)
        }
        val pings = builder.build()
        assertTrue(pings.size > 500)
        val r = run(pings)
        assertEquals(1, r.stays.size)
        assertTrue(r.trips.isEmpty())
        assertTrue(r.gaps.isEmpty())
        assertTrue(r.stays.single().range.durationMs > 6 * HOUR + 50 * MIN)
        val place = r.newPlaces.single()
        assertTrue(haversineMeters(place.lat, place.lng, o.first, o.second) < 300.0) // none at a spike (3.5 km)
        assertTrue(place.radiusM in 75.0..300.0)
    }

    /** A boat circling a 1 km loop at 2 m/s with good fixes is a real trip: stay, trip, stay. */
    @Test
    fun slowBoatLoopGoodGps() {
        val pings = builder(41)
            .stay(30 * MIN, jitterM = 5.0, accuracyM = 6f)
            .moveBy(90.0, 500.0, 2.0, mode = Activity.UNKNOWN, accuracyM = 6f)
            .moveBy(270.0, 500.0, 2.0, mode = Activity.UNKNOWN, accuracyM = 6f)
            .stay(30 * MIN, jitterM = 5.0, accuracyM = 6f)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        assertEquals(1, r.trips.size)
        // An out-and-back path: the turnaround is not "going somewhere", so the counted path is a little short of 1 km.
        assertTrue(r.trips.single().distanceM in 700.0..1_100.0)
    }

    /**
     * A still phone on good GPS jitter must not be read as alternating network anchors (review of dogfood-2): its
     * accuracy stays what the fix says, so departures are prompt and no place radius grows. Two sites [siteDistM] apart,
     * 8 m GPS accuracy and jitter, a six-minute stay at each in turn (A, B, A, B, ...) so that every 30 minute anchor
     * window sees four or more switches. Returns the pings and the time spans of the walks between the sites.
     */
    private fun backAndForth(siteDistM: Double, stays: Int = 20): Pair<List<Ping>, List<TimeRange>> {
        val builder = builder(71)
        val walks = ArrayList<TimeRange>()
        repeat(stays) { i ->
            builder.stay(6 * MIN, intervalMs = 20_000, jitterM = 8.0, accuracyM = 8f)
            if (i < stays - 1) {
                val from = builder.currentTimeMs
                builder.moveBy(if (i % 2 == 0) 90.0 else 270.0, siteDistM, 1.4, intervalMs = 20_000, mode = Activity.WALKING, accuracyM = 8f)
                walks += TimeRange(from, builder.currentTimeMs)
            }
        }
        return builder.build() to walks
    }

    private fun assertSplitAtEveryMove(stays: List<Stay>, walks: List<TimeRange>) {
        assertEquals(walks.size + 1, stays.size)
        for ((i, walk) in walks.withIndex()) {
            assertTrue("stay $i ends near its move", stays[i].range.endMs in (walk.startMs - MIN)..(walk.endMs + MIN))
            assertTrue("stay ${i + 1} starts near its move", stays[i + 1].range.startMs in (walk.startMs - MIN)..(walk.endMs + MIN))
        }
    }

    @Test
    fun goodGpsJitterBackAndForthBetweenTightKnownPlacesSplitsAtEveryMove() {
        val a = SYDNEY_LAT to SYDNEY_LNG
        val b = offsetMeters(a.first, a.second, 0.0, 100.0)
        val (pings, walks) = backAndForth(100.0)
        val r = run(
            pings,
            listOf(
                KnownPlace("a", a.first, a.second, radiusM = 25f),
                KnownPlace("b", b.first, b.second, radiusM = 25f),
            ),
        )
        assertSplitAtEveryMove(r.stays, walks)
        assertEquals(List(r.stays.size) { PlaceRef.Existing(if (it % 2 == 0) "a" else "b") }, r.stays.map { it.place })
        assertTrue(r.newPlaces.isEmpty())
    }

    @Test
    fun goodGpsJitterBackAndForthWithoutKnownPlacesSplitsAtEveryMoveWithDefaultPlaceRadii() {
        // 220 m apart: past the minimum trip distance, so the visits stay separate; at 100 m they merge by design.
        val (pings, walks) = backAndForth(220.0)
        val r = run(pings)
        assertSplitAtEveryMove(r.stays, walks)
        assertEquals(2, r.newPlaces.size)
        for (place in r.newPlaces) assertEquals(params.placeMatchRadiusM, place.radiusM, 0.5)
    }

    @Test
    fun goodGpsJitterBackAndForthOneHundredMetresApartWithoutKnownPlacesMergesIntoOneVisit() {
        val (pings, _) = backAndForth(100.0)
        val r = run(pings)
        assertEquals(1, r.stays.size)
        // Two sites under minTripDistanceM apart cannot be told apart without known places (by design); the merged
        // place's radius follows the spread of the two sites, never beyond the cap.
        assertTrue(r.newPlaces.single().radiusM <= params.placeMaxRadiusM)
    }

    /**
     * An out-and-back walk turning at 170 m with GPS-grade fixes is a real trip: stay, trip, stay (not merged as noise).
     * It is slow enough (1 m/s) to take longer than [SegmentationParams.shortTripMergeMs] outside the stays.
     */
    @Test
    fun gpsGradeOutAndBackWalkTurningAtOneHundredSeventyMetresIsATrip() {
        val pings = builder(81)
            .stay(30 * MIN, intervalMs = 30_000, jitterM = 5.0, accuracyM = 8f)
            .moveBy(0.0, 170.0, 1.0, mode = Activity.WALKING, accuracyM = 8f)
            .moveBy(180.0, 170.0, 1.0, mode = Activity.WALKING, accuracyM = 8f)
            .stay(30 * MIN, intervalMs = 30_000, jitterM = 5.0, accuracyM = 8f)
            .build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        assertEquals(1, r.trips.size)
        // The turnaround is not "going somewhere", so the counted path is shorter than the 340 m walked.
        assertTrue(r.trips.single().distanceM >= params.minTripDistanceM)
    }

    /** A genuine walk of 250 m away for 25 min at GPS accuracy is not noise: stay, trip, stay, trip, stay. */
    @Test
    fun gpsGradeWalkAwayAndBack() {
        val pings = builder(51)
            .stay(HOUR, intervalMs = 30_000, jitterM = 6.0, accuracyM = 8f)
            .moveBy(0.0, 250.0, 1.4, mode = Activity.WALKING, accuracyM = 8f)
            .stay(25 * MIN, intervalMs = 30_000, jitterM = 6.0, accuracyM = 8f)
            .moveBy(180.0, 250.0, 1.4, mode = Activity.WALKING, accuracyM = 8f)
            .stay(HOUR, intervalMs = 30_000, jitterM = 6.0, accuracyM = 8f)
            .build()
        val r = run(pings)
        assertEquals(3, r.stays.size)
        assertEquals(2, r.trips.size)
        assertEquals(PlaceRef.New(0), r.stays[0].place)
        assertEquals(PlaceRef.New(1), r.stays[1].place)
        assertEquals(PlaceRef.New(0), r.stays[2].place)
    }

    /**
     * Site-A shape: two visits to the same yard ~180 m apart by centroid (noisy network fixes), with a boat trip
     * between them, are one place.
     */
    @Test
    fun noisyVisitsOneHundredEightyMetresApartShareAPlace() {
        val builder = builder(61)
            .hops(15 * MIN, jitterM = 40.0, hopMinM = 80.0, hopMaxM = 160.0, accuracyM = 40f..95f)
            .moveBy(270.0, 700.0, 1.5, mode = Activity.UNKNOWN, accuracyM = 30f)
            .stay(10 * MIN, jitterM = 10.0, accuracyM = 30f)
            .moveBy(90.0, 520.0, 1.5, mode = Activity.UNKNOWN, accuracyM = 30f)
        // The boat is back 180 m west of where it left (700 m out, 520 m back).
        val pings = builder
            .hops(55 * MIN, jitterM = 40.0, hopMinM = 80.0, hopMaxM = 160.0, accuracyM = 40f..95f)
            .build()
        val r = run(pings)
        val first = r.stays.first()
        val last = r.stays.last()
        assertTrue(r.stays.size >= 2 && r.trips.isNotEmpty())
        // The two visits are well apart by centroid (past the old fixed 75 m radius), yet one place.
        assertTrue(haversineMeters(first.lat, first.lng, last.lat, last.lng) > 120.0)
        assertEquals(first.place, last.place)
    }

    /** Real day 3: a directed GPS-grade walk between two places is its own trip; pottering stays in the visits. */
    @Test
    fun walkBetweenTwoPlacesKeepsItsDuration() {
        val walkMs = (470.0 / 1.2 * 1000).toLong()
        val b = builder(31).stay(30 * MIN, intervalMs = 20_000, jitterM = 6.0, accuracyM = 12f, activity = Activity.STILL)
        b.wander(3 * MIN, radiusM = 60.0, accuracyM = 7f..22f)
        val leaveOut = b.currentTimeMs
        b.moveBy(90.0, 470.0, 1.2, intervalMs = 10_000, mode = Activity.WALKING, accuracyM = 12f, jitterM = 8.0)
        val arriveOut = b.currentTimeMs
        b.wander(3 * MIN, radiusM = 60.0, accuracyM = 7f..22f)
        b.stay(20 * MIN, intervalMs = 20_000, jitterM = 6.0, accuracyM = 12f, activity = Activity.STILL)
        b.wander(2 * MIN, radiusM = 80.0, accuracyM = 7f..22f)
        val leaveBack = b.currentTimeMs
        b.moveBy(270.0, 470.0, 1.2, intervalMs = 10_000, mode = Activity.WALKING, accuracyM = 12f, jitterM = 8.0)
        val arriveBack = b.currentTimeMs
        b.wander(2 * MIN, radiusM = 40.0, accuracyM = 7f..22f)
        val pings = b.stay(30 * MIN, intervalMs = 20_000, jitterM = 6.0, accuracyM = 12f, activity = Activity.STILL).build()
        val r = run(pings)
        assertEquals(3, r.stays.size)
        assertEquals(2, r.trips.size)
        for (t in r.trips) {
            assertEquals(walkMs.toDouble(), t.range.durationMs.toDouble(), 1.5 * MIN)
            assertEquals(470.0, t.distanceM, 470.0 * 0.15)
            assertEquals(TripMode.WALK, t.mode)
        }
        assertEquals(leaveOut.toDouble(), r.trips[0].range.startMs.toDouble(), 1.5 * MIN)
        assertEquals(arriveOut.toDouble(), r.trips[0].range.endMs.toDouble(), 1.5 * MIN)
        assertEquals(leaveBack.toDouble(), r.trips[1].range.startMs.toDouble(), 1.5 * MIN)
        assertEquals(arriveBack.toDouble(), r.trips[1].range.endMs.toDouble(), 1.5 * MIN)
        // The pottering is inside the visits.
        assertTrue(r.stays[0].range.endMs >= leaveOut - 1.5 * MIN)
        assertTrue(r.stays[1].range.startMs <= arriveOut + 1.5 * MIN && r.stays[1].range.endMs >= leaveBack - 1.5 * MIN)
        assertTrue(r.stays[2].range.startMs <= arriveBack + 1.5 * MIN)
    }

    /** A vehicle trip still absorbs the walk-in tail: the drive ends where the vehicle stopped. */
    @Test
    fun vehicleTripStillAbsorbsWalkInTail() {
        val b = builder(32).stay(30 * MIN, intervalMs = 3 * MIN)
        b.moveBy(90.0, 4_000.0, 12.5, accuracyM = 15f)
        val parked = b.currentTimeMs
        b.moveBy(90.0, 150.0, 1.2, intervalMs = 10_000, mode = Activity.WALKING, accuracyM = 12f, jitterM = 6.0)
        val pings = b.stay(HOUR, intervalMs = 3 * MIN).build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        val trip = r.trips.single()
        assertEquals(TripMode.VEHICLE, trip.mode)
        assertEquals(parked, trip.range.endMs)
        assertEquals(parked, r.stays[1].range.startMs)
    }

    private fun modeOfTrip(
        distanceM: Double,
        speedMps: Double,
        activity: Activity,
        accuracyM: Float = 15f,
        reportSpeed: Boolean = false,
        intervalMs: Long = 20_000,
    ): TripMode {
        val pings = builder(41).stay(HOUR, intervalMs = 3 * MIN)
            .moveBy(90.0, distanceM, speedMps, intervalMs = intervalMs, mode = activity, accuracyM = accuracyM, reportSpeed = reportSpeed)
            .stay(HOUR, intervalMs = 3 * MIN).build()
        val r = run(pings)
        assertEquals(2, r.stays.size)
        return r.trips.single().mode
    }

    @Test
    fun gpsWalkAtWalkingPaceIsWalk() {
        assertEquals(TripMode.WALK, modeOfTrip(800.0, 1.2, Activity.WALKING, reportSpeed = true, intervalMs = 10_000))
        // Hop speeds alone (no reported speed) agree, even if activity recognition says nothing useful.
        assertEquals(TripMode.WALK, modeOfTrip(800.0, 1.2, Activity.UNKNOWN, intervalMs = 10_000))
    }

    @Test
    fun driveIsVehicle() {
        assertEquals(TripMode.VEHICLE, modeOfTrip(5_000.0, 12.0, Activity.IN_VEHICLE, reportSpeed = true))
    }

    @Test
    fun boatWithStillActivityIsVehicle() {
        assertEquals(TripMode.VEHICLE, modeOfTrip(3_000.0, 5.0, Activity.STILL, reportSpeed = true))
        assertEquals(TripMode.VEHICLE, modeOfTrip(3_000.0, 4.0, Activity.STILL))
    }

    @Test
    fun walkingAboutOnAMovingBoatIsStillVehicle() {
        assertEquals(TripMode.VEHICLE, modeOfTrip(3_000.0, 5.0, Activity.WALKING, reportSpeed = true))
        assertEquals(TripMode.VEHICLE, modeOfTrip(3_000.0, 5.0, Activity.WALKING))
    }

    @Test
    fun poorFixesWalkingActivityButVehicleNetSpeedIsVehicle() {
        assertEquals(TripMode.VEHICLE, modeOfTrip(3_000.0, 5.0, Activity.WALKING, accuracyM = 80f))
    }

    @Test
    fun noSpeedNoActivityAndNoGoodFixesIsUnknown() {
        assertEquals(TripMode.UNKNOWN, modeOfTrip(3_000.0, 5.0, Activity.UNKNOWN, accuracyM = 80f))
    }

    @Test
    fun betweenThresholdsActivityDecidesThenNearerSide() {
        assertEquals(TripMode.WALK, modeOfTrip(2_000.0, 2.8, Activity.WALKING, reportSpeed = true))
        assertEquals(TripMode.VEHICLE, modeOfTrip(2_000.0, 2.8, Activity.ON_BICYCLE, reportSpeed = true))
        assertEquals(TripMode.VEHICLE, modeOfTrip(2_000.0, 2.8, Activity.UNKNOWN, reportSpeed = true))
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
