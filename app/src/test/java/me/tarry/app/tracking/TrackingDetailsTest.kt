package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.settings.PowerMode
import org.junit.Test

class TrackingDetailsTest {
    private fun shown(state: TrackingState, flag: Boolean) =
        DefaultTrackingStateRepository.detailsOf(ReducedTrackingState(state), flag, PowerMode.BALANCED).lowBatterySaverActive

    @Test
    fun theLowBatteryFlagShowsOnlyWhileTracking() {
        assertThat(shown(TrackingState.TRACKING, true)).isTrue()
        assertThat(shown(TrackingState.TRACKING, false)).isFalse()
        for (s in listOf(TrackingState.IDLE, TrackingState.PAUSED, TrackingState.ATTENTION)) {
            assertThat(shown(s, true)).isFalse()
        }
    }
}
