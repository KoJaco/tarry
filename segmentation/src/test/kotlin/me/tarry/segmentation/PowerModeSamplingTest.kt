package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters
import me.tarry.segmentation.trace.TraceBuilder
import me.tarry.segmentation.trace.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Segmentation safety for the app's power modes (docs/handoffs/phase-battery-build.md). One synthetic day, a stay of
 * 15 min, a drive, 40 min, a drive, 2 h, sampled the way each mode samples: Precise 10 s moving and 60 s at a site,
 * Balanced 10 s and 120 s, Saver 30 s and 300 s with about 40 m of noise (and a rougher accuracy) at the sites.
 * The segmentation is not tuned for the modes: any difference seen for Saver is recorded below as its trade-off.
 */
class PowerModeSamplingTest {
    private val params = SegmentationParams()
    private val t0 = 1_760_000_000_000L

    private class Sampling(
        val movingMs: Long,
        val stationaryMs: Long,
        val siteJitterM: Double,
        val siteAccuracyM: Float,
        val movingAccuracyM: Float = 25f,
    )

    private val precise = Sampling(10_000, 60_000, siteJitterM = 10.0, siteAccuracyM = 20f)
    private val balanced = Sampling(10_000, 120_000, siteJitterM = 10.0, siteAccuracyM = 20f)
    private val saver = Sampling(30_000, 300_000, siteJitterM = 40.0, siteAccuracyM = 45f)

    private fun day(s: Sampling, seed: Long = 21): List<Ping> = TraceBuilder(seed = seed, startTimeMs = t0)
        .stay(15 * MIN, intervalMs = s.stationaryMs, jitterM = s.siteJitterM, accuracyM = s.siteAccuracyM)
        .moveBy(90.0, 5_000.0, 12.5, intervalMs = s.movingMs, accuracyM = s.movingAccuracyM)
        .stay(40 * MIN, intervalMs = s.stationaryMs, jitterM = s.siteJitterM, accuracyM = s.siteAccuracyM)
        .moveBy(0.0, 8_000.0, 14.0, intervalMs = s.movingMs, accuracyM = s.movingAccuracyM)
        .stay(2 * HOUR, intervalMs = s.stationaryMs, jitterM = s.siteJitterM, accuracyM = s.siteAccuracyM)
        .build()

    private fun run(pings: List<Ping>) =
        Segmenter.segment(SegmentationInput(pings.range(), pings), params)

    private fun assertSameDay(reference: SegmentationResult, r: SegmentationResult, toleranceMs: Long, mode: String) {
        assertEquals("$mode visits", reference.stays.size, r.stays.size)
        assertEquals("$mode trips", reference.trips.size, r.trips.size)
        for ((a, b) in reference.stays.zip(r.stays)) {
            assertTrue("$mode stay place ${haversineMeters(a.lat, a.lng, b.lat, b.lng)} m", haversineMeters(a.lat, a.lng, b.lat, b.lng) <= params.placeMatchRadiusM)
            assertTrue("$mode stay start ${b.range.startMs - a.range.startMs}", kotlin.math.abs(b.range.startMs - a.range.startMs) <= toleranceMs)
            assertTrue("$mode stay end ${b.range.endMs - a.range.endMs}", kotlin.math.abs(b.range.endMs - a.range.endMs) <= toleranceMs)
        }
        for ((a, b) in reference.trips.zip(r.trips)) {
            assertEquals("$mode trip mode", a.mode, b.mode)
            assertEquals("$mode trip distance", a.distanceM, b.distanceM, 300.0)
        }
    }

    @Test
    fun theDayIsThreeVisitsAndTwoVehicleTripsInPrecise() {
        val r = run(day(precise))
        assertEquals(3, r.stays.size)
        assertEquals(2, r.trips.size)
        assertTrue(r.trips.all { it.mode == TripMode.VEHICLE })
    }

    @Test
    fun balancedFindsTheSameDayWithinThreeMinutes() {
        for (seed in 21L..25L) {
            assertSameDay(run(day(precise, seed)), run(day(balanced, seed)), 3 * MIN, "balanced seed $seed")
        }
    }

    @Test
    fun saverFindsTheSameDayWithinFiveMinutes() {
        for (seed in 21L..25L) {
            assertSameDay(run(day(precise, seed)), run(day(saver, seed)), 5 * MIN, "saver seed $seed")
        }
    }


    /**
     * A stop as the real machine samples it: the moving request keeps running for about a minute after arrival (the
     * stationary mode is entered then), after which fixes come at the profile's stationary interval until departure.
     */
    private fun TraceBuilder.stop(s: Sampling, durationMs: Long) = this
        .stay(MIN, intervalMs = s.movingMs, jitterM = s.siteJitterM, accuracyM = s.siteAccuracyM)
        .stay(durationMs - MIN, intervalMs = s.stationaryMs, jitterM = s.siteJitterM, accuracyM = s.siteAccuracyM)

    /** A drive with a stop of [stopMin] minutes in the middle, between two long visits. */
    private fun withStop(s: Sampling, stopMin: Long, seed: Long = 31): List<Ping> = TraceBuilder(seed = seed, startTimeMs = t0)
        .stay(30 * MIN, intervalMs = s.stationaryMs, jitterM = s.siteJitterM, accuracyM = s.siteAccuracyM)
        .moveBy(90.0, 4_000.0, 12.5, intervalMs = s.movingMs, accuracyM = s.movingAccuracyM)
        .stop(s, stopMin * MIN)
        .moveBy(90.0, 4_000.0, 12.5, intervalMs = s.movingMs, accuracyM = s.movingAccuracyM)
        .stay(30 * MIN, intervalMs = s.stationaryMs, jitterM = s.siteJitterM, accuracyM = s.siteAccuracyM)
        .build()

    private fun assertStopFound(name: String, s: Sampling, stop: Long, seed: Long) {
        val r = run(withStop(s, stop, seed))
        assertEquals("$name seed $seed stop $stop visits", 3, r.stays.size)
        assertEquals("$name seed $seed stop $stop trips", 2, r.trips.size)
    }

    /**
     * Documented trade-off (not tuned away): a stop is only a visit once its fixes span the 5-minute minimum stay.
     * Precise (a fix a minute) finds short stops. Balanced (a fix every 2 minutes after the first minute) is pinned to
     * find a 7-minute stop, but a stop of about 5 to 6 minutes can fall between fixes and be missed. Saver (every
     * 5 minutes) can miss stops under about 10 minutes, so the app tells Saver users that.
     * A stop of 10 minutes or more is found in every mode.
     */
    @Test
    fun aStopOfTenMinutesOrMoreIsFoundInEveryMode() {
        for (seed in 31L..36L) for (stop in listOf(10L, 15L)) for ((n, s) in listOf("precise" to precise, "balanced" to balanced, "saver" to saver)) {
            assertStopFound(n, s, stop, seed)
        }
    }

    /** The Balanced floor: a 7-minute stop is a visit. */
    @Test
    fun aSevenMinuteStopIsFoundWithBalanced() {
        for (seed in 31L..40L) assertStopFound("balanced", balanced, 7, seed)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
