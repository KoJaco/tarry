package me.tarry.app.access

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class AccessModelTest {
    private val day = TrialClock.MS_PER_DAY
    private val anchor = 1_700_000_000_000L
    private val end = anchor + 7 * day

    @Test
    fun noAnchorMeansNotStarted() {
        assertThat(TrialClock.status(null, anchor)).isEqualTo(TrialStatus.NotStarted)
    }

    @Test
    fun trialEndsAtExactlySevenTimes24Hours() {
        assertThat(TrialClock.endsAtMs(anchor)).isEqualTo(anchor + 604_800_000L)
        assertThat(TrialClock.status(anchor, end - 1)).isEqualTo(TrialStatus.Running(end))
        assertThat(TrialClock.status(anchor, end)).isEqualTo(TrialStatus.Ended(end))
    }

    @Test
    fun aZoneChangeDoesNotMoveTheEnd() {
        // The end is plain milliseconds, so the zone the anchor is shown in is irrelevant, across a DST change too.
        val a = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli()
        val inSydney = TrialClock.endsAtMs(a)
        val inLondon = ZonedDateTime.ofInstant(Instant.ofEpochMilli(a), ZoneId.of("Europe/London"))
            .toInstant().toEpochMilli().let { TrialClock.endsAtMs(it) }
        assertThat(inLondon).isEqualTo(inSydney)
        assertThat(inSydney - a).isEqualTo(7 * 24 * 60 * 60 * 1000L)
    }

    @Test
    fun aClockMovedBackDoesNotExtendTheTrial() {
        val lastSeen = end + 1_000
        val effective = TrialClock.effectiveNowMs(nowMs = anchor, lastSeenMs = lastSeen)
        assertThat(effective).isEqualTo(lastSeen)
        assertThat(TrialClock.status(anchor, effective)).isEqualTo(TrialStatus.Ended(end))
        assertThat(TrialClock.effectiveNowMs(nowMs = 5, lastSeenMs = null)).isEqualTo(5)
    }

    @Test
    fun daysLeftRoundsUp() {
        assertThat(TrialClock.daysLeft(end, anchor)).isEqualTo(7)
        assertThat(TrialClock.daysLeft(end, anchor + 1)).isEqualTo(7)
        assertThat(TrialClock.daysLeft(end, anchor + day)).isEqualTo(6)
        assertThat(TrialClock.daysLeft(end, end - 1)).isEqualTo(1)
        assertThat(TrialClock.daysLeft(end, end)).isEqualTo(0)
        assertThat(TrialClock.daysLeft(end, end + day)).isEqualTo(0)
    }

    @Test
    fun canTrackTable() {
        val running = end - 1
        val over = end
        assertThat(AccessState.Owned.canTrack(over)).isTrue()
        assertThat(AccessState.NotStarted.canTrack(over)).isTrue()
        assertThat(AccessState.Trial(end).canTrack(running)).isTrue()
        assertThat(AccessState.Trial(end).canTrack(over)).isFalse()
        assertThat(AccessState.TrialEnded.canTrack(running)).isFalse()
        assertThat(AccessState.TrialEnded.canTrack(over)).isFalse()
        // Pending and Revoked only change wording: the trial still counts.
        assertThat(AccessState.Pending(end).canTrack(running)).isTrue()
        assertThat(AccessState.Pending(end).canTrack(over)).isFalse()
        assertThat(AccessState.Revoked(end).canTrack(running)).isTrue()
        assertThat(AccessState.Revoked(end).canTrack(over)).isFalse()
        assertThat(AccessState.Pending(null).canTrack(over)).isTrue()
        assertThat(AccessState.Revoked(null).canTrack(over)).isTrue()
    }

    @Test
    fun runningTrialEndIsOnlyGivenWhileUnownedAndRunning() {
        assertThat(AccessState.Trial(end).runningTrialEndMs(end - 1)).isEqualTo(end)
        assertThat(AccessState.Trial(end).runningTrialEndMs(end)).isNull()
        assertThat(AccessState.Pending(end).runningTrialEndMs(anchor)).isEqualTo(end)
        assertThat(AccessState.Owned.runningTrialEndMs(anchor)).isNull()
        assertThat(AccessState.NotStarted.runningTrialEndMs(anchor)).isNull()
    }

    @Test
    fun theDebugSwitchCyclesThroughEveryValueAndWraps() {
        var v = DebugAccess.OWNED
        val seen = mutableListOf(v)
        repeat(DebugAccess.entries.size) {
            v = v.next()
            seen += v
        }
        assertThat(seen.take(DebugAccess.entries.size)).containsExactlyElementsIn(DebugAccess.entries)
        assertThat(seen.last()).isEqualTo(DebugAccess.OWNED)
    }

    @Test
    fun debugTrialVariantsSetTheirEndRelativeToNow() {
        assertThat(DebugAccess.TRIAL_6_DAYS.trialEndFor(anchor)).isEqualTo(anchor + 6 * day)
        assertThat(DebugAccess.TRIAL_1_DAY.trialEndFor(anchor)).isEqualTo(anchor + day)
        assertThat(DebugAccess.TRIAL_ENDED.trialEndFor(anchor)).isEqualTo(anchor)
        assertThat(DebugAccess.PENDING.trialEndFor(anchor)).isEqualTo(anchor)
        assertThat(DebugAccess.OWNED.trialEndFor(anchor)).isNull()
    }
}
