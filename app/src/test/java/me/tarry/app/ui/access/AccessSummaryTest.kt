package me.tarry.app.ui.access

import com.google.common.truth.Truth.assertThat
import me.tarry.app.access.AccessState
import me.tarry.app.access.TrialClock
import org.junit.Test

class AccessSummaryTest {
    private val now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun theCardShowsAtTwentyFourHoursLeftButNotAtTwentyFive() {
        assertThat(trialCardVisible(AccessState.Trial(now + 24 * hour), false, now)).isTrue()
        assertThat(trialCardVisible(AccessState.Trial(now + 25 * hour), false, now)).isFalse()
        assertThat(trialCardVisible(AccessState.Trial(now + 1), false, now)).isTrue()
    }

    @Test
    fun theCardNeverShowsOnceDismissedOrWhenOwnedOrEnded() {
        val ending = AccessState.Trial(now + hour)
        assertThat(trialCardVisible(ending, true, now)).isFalse()
        assertThat(trialCardVisible(AccessState.Owned, false, now)).isFalse()
        assertThat(trialCardVisible(AccessState.TrialEnded, false, now)).isFalse()
        assertThat(trialCardVisible(AccessState.Trial(now), false, now)).isFalse()
        assertThat(trialCardVisible(AccessState.Pending(now + hour), false, now)).isFalse()
    }

    @Test
    fun theRowFollowsTheState() {
        assertThat(accessRow(AccessState.NotStarted, now)).isNull()
        assertThat(accessRow(AccessState.Trial(now + 2 * TrialClock.MS_PER_DAY - 1), now)).isEqualTo(AccessRow.Trial(2))
        assertThat(accessRow(AccessState.Trial(now + 1), now)).isEqualTo(AccessRow.Trial(1))
        assertThat(accessRow(AccessState.Trial(now), now)).isEqualTo(AccessRow.TrialEnded)
        assertThat(accessRow(AccessState.TrialEnded, now)).isEqualTo(AccessRow.TrialEnded)
        assertThat(accessRow(AccessState.Pending(null), now)).isEqualTo(AccessRow.Pending)
        assertThat(accessRow(AccessState.Owned, now)).isEqualTo(AccessRow.Owned)
        assertThat(accessRow(AccessState.Revoked(null), now)).isEqualTo(AccessRow.Revoked)
    }
}
