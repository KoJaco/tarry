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
    fun jitterInsideAccuracyCircleDroppedOnlyWhenStationary() {
        filter.offer(fixAt(0), CaptureMode.STATIONARY)
        val jittery = fixAt(60_000, northM = 8.0, accuracyM = 15f)
        assertThat(filter.offer(jittery, CaptureMode.STATIONARY)).isEqualTo(FilterDecision.DROP_JITTER)
        assertThat(filter.offer(jittery, CaptureMode.MOVING)).isEqualTo(FilterDecision.ACCEPT)
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
}
