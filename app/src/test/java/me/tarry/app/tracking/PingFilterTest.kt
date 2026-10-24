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
        assertThat(filter.offer(fixAt(0))).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun dropsAccuracyWorseThanLimitButKeepsExactlyAtLimit() {
        assertThat(filter.offer(fixAt(0, accuracyM = config.maxAccuracyM + 1)))
            .isEqualTo(FilterDecision.DROP_ACCURACY)
        assertThat(filter.lastAccepted).isNull()
        assertThat(filter.offer(fixAt(0, accuracyM = config.maxAccuracyM)))
            .isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun dropsImpossibleSpeedAndKeepsLastAccepted() {
        val first = fixAt(0)
        filter.offer(first)
        // 1000 m in 10 s = 100 m/s > 70 m/s
        assertThat(filter.offer(fixAt(10_000, northM = 1000.0)))
            .isEqualTo(FilterDecision.DROP_SPEED)
        assertThat(filter.lastAccepted).isEqualTo(first)
        // 500 m in 10 s = 50 m/s is fine
        assertThat(filter.offer(fixAt(10_000, northM = 500.0)))
            .isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun dropsFixNotNewerThanLastAccepted() {
        filter.offer(fixAt(5_000))
        assertThat(filter.offer(fixAt(5_000, northM = 100.0)))
            .isEqualTo(FilterDecision.DROP_SPEED)
    }

    @Test
    fun keepsEveryFixInsideTheCapsInBothModes() {
        // No jitter or displacement drop: a still phone's 3 m wobble and a 5 m step in MOVING are all kept.
        assertThat(filter.offer(fixAt(0))).isEqualTo(FilterDecision.ACCEPT)
        assertThat(filter.offer(fixAt(60_000, northM = 3.0, accuracyM = 20f)))
            .isEqualTo(FilterDecision.ACCEPT)
        assertThat(filter.offer(fixAt(70_000, northM = 8.0))).isEqualTo(FilterDecision.ACCEPT)
        assertThat(filter.offer(fixAt(80_000, northM = 8.0, accuracyM = 5f))).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun spikeReturnedFromIsNotTheAnchorSoTheReturnIsNotDroppedOnSpeed() {
        val home = fixAt(0)
        filter.offer(home)
        // 3 km away 60 s later is 50 m/s: accepted (and saved) as a spike.
        val spike = fixAt(60_000, northM = 3_000.0)
        assertThat(filter.offer(spike)).isEqualTo(FilterDecision.ACCEPT)
        // Back at home 20 s later: 150 m/s from the spike would be dropped; against the pre-spike fix it is fine.
        val back = fixAt(80_000, northM = 5.0)
        assertThat(filter.offer(back)).isEqualTo(FilterDecision.ACCEPT)
        assertThat(filter.lastAccepted).isEqualTo(back)
        // Later fixes compare with the return, not the spike.
        assertThat(filter.offer(fixAt(140_000, northM = 8.0))).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun withoutAReturnTheSpikeStaysTheAnchor() {
        filter.offer(fixAt(0))
        filter.offer(fixAt(60_000, northM = 3_000.0))
        // Not within spikeReturnM of the first fix (1 km from it): compared with the 3 km fix, 2 km in 10 s is too fast.
        assertThat(filter.offer(fixAt(70_000, northM = 1_000.0))).isEqualTo(FilterDecision.DROP_SPEED)
    }

    @Test
    fun aFarFixThatFollowsAnotherFarFixIsNotMistakenForAReturn() {
        filter.offer(fixAt(0))
        // A real trip: 600 m steps, never back near the start.
        assertThat(filter.offer(fixAt(30_000, northM = 600.0))).isEqualTo(FilterDecision.ACCEPT)
        assertThat(filter.offer(fixAt(60_000, northM = 1_200.0))).isEqualTo(FilterDecision.ACCEPT)
        assertThat(filter.lastAccepted!!.timeMs).isEqualTo(60_000)
    }


    @Test
    fun resetForgetsLastAccepted() {
        filter.offer(fixAt(0))
        filter.reset()
        assertThat(filter.lastAccepted).isNull()
        assertThat(filter.offer(fixAt(1_000, northM = 5000.0))).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun speedJustUnderTheLimitIsAcceptedAndJustOverIsDropped() {
        filter.offer(fixAt(0))
        // 690 m in 10 s = 69 m/s (under 70), 720 m = 72 m/s (over).
        assertThat(filter.offer(fixAt(10_000, northM = 720.0))).isEqualTo(FilterDecision.DROP_SPEED)
        assertThat(filter.offer(fixAt(10_000, northM = 690.0))).isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun olderFixThanLastAcceptedIsDropped() {
        filter.offer(fixAt(5_000))
        assertThat(filter.offer(fixAt(4_000, northM = 5.0))).isEqualTo(FilterDecision.DROP_SPEED)
    }

    @Test
    fun poorAccuracyIsReportedBeforeSpeedAndDoesNotMoveLastAccepted() {
        val first = fixAt(0)
        filter.offer(first)
        val bad = fixAt(10_000, northM = 5_000.0, accuracyM = config.maxAccuracyM + 1)
        assertThat(filter.offer(bad)).isEqualTo(FilterDecision.DROP_ACCURACY)
        assertThat(filter.lastAccepted).isEqualTo(first)
    }

    @Test
    fun repeatedPositionAtTheAccuracyLimitIsStale() {
        // Shape of the dogfood repeats: the provider re-emits the last position minutes later at the accuracy limit.
        filter.offer(fixAt(0, accuracyM = 20f))
        val repeat = fixAt(12 * 60_000L, accuracyM = config.maxAccuracyM)
        assertThat(filter.offer(repeat)).isEqualTo(FilterDecision.DROP_STALE)
        assertThat(filter.lastAccepted!!.timeMs).isEqualTo(0L)
    }

    @Test
    fun differentPositionAtTheLimitIsNotStale() {
        filter.offer(fixAt(0))
        assertThat(filter.offer(fixAt(12 * 60_000L, northM = 1.0, accuracyM = config.maxAccuracyM)))
            .isEqualTo(FilterDecision.ACCEPT)
    }

    @Test
    fun fixNotNewerByElapsedRealtimeIsDroppedEvenIfWallTimeAdvanced() {
        filter.offer(fixAt(0).copy(elapsedRealtimeNanos = 5_000_000_000L))
        val older = fixAt(60_000, northM = 100.0).copy(elapsedRealtimeNanos = 4_000_000_000L)
        assertThat(filter.offer(older)).isEqualTo(FilterDecision.DROP_SPEED)
    }
}
