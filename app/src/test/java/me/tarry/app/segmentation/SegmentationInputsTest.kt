package me.tarry.app.segmentation

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.segmentation.TimeRange
import org.junit.Test

class SegmentationInputsTest {
    private val minute = 60_000L
    private val shortMs = 5 * minute

    private fun session(start: Long, end: Long?, reason: SessionEndReason?) = TrackingSession(
        start = start, end = end, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE, endReason = reason,
    )

    @Test
    fun aKilledSessionFollowedByAQuickRestartIsBridged() {
        val out = segmentationSessions(
            listOf(session(0, 10 * minute, SessionEndReason.KILLED), session(10 * minute + 77_000, null, null)),
            nowMs = 60 * minute, shortInterruptionMs = shortMs,
        )
        assertThat(out).containsExactly(TimeRange(0, 10 * minute + 77_000), TimeRange(10 * minute + 77_000, 60 * minute)).inOrder()
    }

    @Test
    fun aRunBeforeTheRestartBridgesTheKilledSessionToNowOnlyWithinTheShortLimit() {
        // Crash recovery closed the session; segmentation runs before the restart has saved its session.
        val killed = listOf(session(0, 10 * minute, SessionEndReason.KILLED))
        assertThat(segmentationSessions(killed, nowMs = 11 * minute, shortInterruptionMs = shortMs))
            .containsExactly(TimeRange(0, 11 * minute))
        assertThat(segmentationSessions(killed, nowMs = 15 * minute, shortInterruptionMs = shortMs))
            .containsExactly(TimeRange(0, 10 * minute))
    }

    @Test
    fun anOutageOfTheShortLimitOrMoreIsNotBridged() {
        val out = segmentationSessions(
            listOf(session(0, 10 * minute, SessionEndReason.KILLED), session(15 * minute, 20 * minute, SessionEndReason.USER_STOP)),
            nowMs = 60 * minute, shortInterruptionMs = shortMs,
        )
        assertThat(out[0]).isEqualTo(TimeRange(0, 10 * minute))
    }

    @Test
    fun aNonKilledEndIsNotBridged() {
        val out = segmentationSessions(
            listOf(session(0, 10 * minute, SessionEndReason.USER_STOP), session(11 * minute, 20 * minute, SessionEndReason.USER_STOP)),
            nowMs = 60 * minute, shortInterruptionMs = shortMs,
        )
        assertThat(out[0]).isEqualTo(TimeRange(0, 10 * minute))
    }

    @Test
    fun anOpenSessionEndsAtNowAndInputOrderDoesNotMatter() {
        val out = segmentationSessions(
            listOf(session(30 * minute, null, null), session(0, 10 * minute, SessionEndReason.KILLED)),
            nowMs = 50 * minute, shortInterruptionMs = shortMs,
        )
        assertThat(out).containsExactly(TimeRange(0, 10 * minute), TimeRange(30 * minute, 50 * minute)).inOrder()
    }

    @Test
    fun windowsAreClippedAtTheFirstSessionAndAnEarlierWindowIsDropped() {
        val windows = listOf(TimeRange(0, 100), TimeRange(150, 300), TimeRange(400, 500))
        assertThat(boundedWindows(windows, 200)).containsExactly(TimeRange(200, 300), TimeRange(400, 500)).inOrder()
        // No session ever: nothing could have been captured, so no window yields a NO_SESSION gap.
        assertThat(boundedWindows(windows, null)).isEmpty()
    }

    @Test
    fun windowsAreClippedAtTheMomentTheLockBegan() {
        val windows = listOf(TimeRange(100, 200), TimeRange(300, 400), TimeRange(500, 600))
        // A window wholly after the lock is dropped, one straddling it ends there, earlier ones are untouched.
        assertThat(windowsBeforeLock(windows, 350)).containsExactly(TimeRange(100, 200), TimeRange(300, 350)).inOrder()
        assertThat(windowsBeforeLock(windows, 300)).containsExactly(TimeRange(100, 200))
        assertThat(windowsBeforeLock(windows, 50)).isEmpty()
        assertThat(windowsBeforeLock(windows, 1_000)).isEqualTo(windows)
        // Not locked: nothing changes.
        assertThat(windowsBeforeLock(windows, null)).isEqualTo(windows)
    }
}
