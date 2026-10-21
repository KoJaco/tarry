package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import org.junit.Test

internal const val TEST_LAT = -33.8688
internal const val TEST_LNG = 151.2093
private const val METRES_PER_DEG_LAT = 111_195.0

/** A fix [northM] metres north of the test origin. */
internal fun fixAt(timeMs: Long, northM: Double = 0.0, accuracyM: Float = 10f) =
    Fix(lat = TEST_LAT + northM / METRES_PER_DEG_LAT, lng = TEST_LNG, accuracyM = accuracyM, timeMs = timeMs)

class PingFilterTest {
    private val config = TrackingConfig()
    private val filter = PingFilter(config)

    @Test
    fun firstFixWithGoodAccuracyIsAccepted() {
        assertThat(filter.offer(fixAt(0), CaptureMode.MOVING)).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun dropsAccuracyWorseThanLimitButKeepsExactlyAtLimit() {
        assertThat(filter.offer(fixAt(0, accuracyM = config.maxAccuracyM + 1), CaptureMode.MOVING))
            .isEqualTo(FilterDecision.DROP_ACCURACY)
        assertThat(filter.lastAccepted).isNull()
        assertThat(filter.offer(fixAt(0, accuracyM = config.maxAccuracyM), CaptureMode.MOVING))
            .isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun dropsImpossibleSpeedAndKeepsLastAccepted() {
        val first = fixAt(0)
        filter.offer(first, CaptureMode.MOVING)
        // 1000 m in 10 s = 100 m/s > 70 m/s
        assertThat(filter.offer(fixAt(10_000, northM = 1000.0), CaptureMode.MOVING))
            .isEqualTo(FilterDecision.DROP_SPEED)
        assertThat(filter.lastAccepted).isEqualTo(first)
        // 500 m in 10 s = 50 m/s is fine
        assertThat(filter.offer(fixAt(10_000, northM = 500.0), CaptureMode.MOVING))
            .isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun dropsFixNotNewerThanLastAccepted() {
        filter.offer(fixAt(5_000), CaptureMode.MOVING)
        assertThat(filter.offer(fixAt(5_000, northM = 100.0), CaptureMode.MOVING))
            .isEqualTo(FilterDecision.DROP_SPEED)
    }

    @Test
    fun jitterInsideAccuracyCircleIsTheStationaryRuleAndMovingUsesTheDisplacementGate() {
        filter.offer(fixAt(0), CaptureMode.STATIONARY)
        val jittery = fixAt(60_000, northM = 8.0, accuracyM = 15f)
        assertThat(filter.offer(jittery, CaptureMode.STATIONARY)).isEqualTo(FilterDecision.DROP_JITTER)
        assertThat(filter.offer(jittery, CaptureMode.MOVING)).isEqualTo(FilterDecision.DROP_DISPLACEMENT)
        val moved = fixAt(60_000, northM = 30.0, accuracyM = 15f)
        assertThat(filter.offer(moved, CaptureMode.MOVING)).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun stationaryFixOutsideAccuracyCircleIsAccepted() {
        filter.offer(fixAt(0), CaptureMode.STATIONARY)
        assertThat(filter.offer(fixAt(60_000, northM = 30.0, accuracyM = 10f), CaptureMode.STATIONARY))
            .isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun resetForgetsLastAccepted() {
        filter.offer(fixAt(0), CaptureMode.MOVING)
        filter.reset()
        assertThat(filter.lastAccepted).isNull()
        assertThat(filter.offer(fixAt(1_000, northM = 5000.0), CaptureMode.MOVING)).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun speedJustUnderTheLimitIsAcceptedAndJustOverIsDropped() {
        filter.offer(fixAt(0), CaptureMode.MOVING)
        // 690 m in 10 s = 69 m/s (under 70), 720 m = 72 m/s (over).
        assertThat(filter.offer(fixAt(10_000, northM = 720.0), CaptureMode.MOVING)).isEqualTo(FilterDecision.DROP_SPEED)
        assertThat(filter.offer(fixAt(10_000, northM = 690.0), CaptureMode.MOVING)).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun olderFixThanLastAcceptedIsDropped() {
        filter.offer(fixAt(5_000), CaptureMode.MOVING)
        assertThat(filter.offer(fixAt(4_000, northM = 5.0), CaptureMode.MOVING)).isEqualTo(FilterDecision.DROP_SPEED)
    }

    @Test
    fun poorAccuracyIsReportedBeforeSpeedAndDoesNotMoveLastAccepted() {
        val first = fixAt(0)
        filter.offer(first, CaptureMode.MOVING)
        val bad = fixAt(10_000, northM = 5_000.0, accuracyM = config.maxAccuracyM + 1)
        assertThat(filter.offer(bad, CaptureMode.MOVING)).isEqualTo(FilterDecision.DROP_ACCURACY)
        assertThat(filter.lastAccepted).isEqualTo(first)
    }

    @Test
    fun jitterBoundaryIsInclusiveOfTheAccuracyRadius() {
        filter.offer(fixAt(0), CaptureMode.STATIONARY)
        // 9 m away with 10 m accuracy is inside the circle; 11 m is outside.
        assertThat(filter.offer(fixAt(1_000, northM = 9.0), CaptureMode.STATIONARY)).isEqualTo(FilterDecision.DROP_JITTER)
        assertThat(filter.offer(fixAt(2_000, northM = 11.0), CaptureMode.STATIONARY)).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun movingDisplacementGateCountsFromTheLastSavedPingNotTheLastDroppedFix() {
        val first = fixAt(0)
        filter.offer(first, CaptureMode.MOVING)
        // Each step is 10 m (< 25 m): dropped, and the reference stays at the first fix.
        assertThat(filter.offer(fixAt(20_000, northM = 10.0), CaptureMode.MOVING)).isEqualTo(FilterDecision.DROP_DISPLACEMENT)
        assertThat(filter.offer(fixAt(40_000, northM = 20.0), CaptureMode.MOVING)).isEqualTo(FilterDecision.DROP_DISPLACEMENT)
        assertThat(filter.lastAccepted).isEqualTo(first)
        // 30 m from the saved ping (not 10 m from the previous fix) is accepted.
        assertThat(filter.offer(fixAt(60_000, northM = 30.0), CaptureMode.MOVING)).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun displacementGateDoesNotApplyToTheFirstFixOrStationary() {
        assertThat(filter.offer(fixAt(0), CaptureMode.MOVING)).isEqualTo(FilterDecision.ACCEPT)
        // Stationary fixes outside the accuracy circle are saved even when under 25 m.
        assertThat(filter.offer(fixAt(300_000, northM = 15.0), CaptureMode.STATIONARY)).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun stationaryJitterIsDroppedInsideTheKeepaliveAndAcceptedAtOrAfterIt() {
        filter.offer(fixAt(0), CaptureMode.STATIONARY)
        val keepalive = config.stationaryKeepaliveMs
        assertThat(filter.offer(fixAt(keepalive - 1, northM = 3.0), CaptureMode.STATIONARY))
            .isEqualTo(FilterDecision.DROP_JITTER)
        assertThat(filter.offer(fixAt(keepalive, northM = 3.0), CaptureMode.STATIONARY))
            .isEqualTo(FilterDecision.ACCEPT)
        // The keepalive counts from the last saved ping.
        assertThat(filter.offer(fixAt(keepalive + 1_000, northM = 3.0), CaptureMode.STATIONARY))
            .isEqualTo(FilterDecision.DROP_JITTER)
    }

    @Test
    fun keepaliveDoesNotBypassAccuracyAndMovingIsUnaffected() {
        filter.offer(fixAt(0), CaptureMode.STATIONARY)
        val late = config.stationaryKeepaliveMs + 1
        assertThat(filter.offer(fixAt(late, accuracyM = config.maxAccuracyM + 1), CaptureMode.STATIONARY))
            .isEqualTo(FilterDecision.DROP_ACCURACY)
        assertThat(filter.offer(fixAt(late, northM = 3.0), CaptureMode.MOVING))
            .isEqualTo(FilterDecision.DROP_DISPLACEMENT)
    }
}
