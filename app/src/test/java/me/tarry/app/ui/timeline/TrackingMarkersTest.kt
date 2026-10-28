package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TrackingMarkersTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private val bounds = dayBounds(date, zone)

    private fun at(hour: Int, minute: Int = 0, day: LocalDate = date) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long?, reason: SessionEndReason? = null) =
        TrackingSession(start = start, end = end, zoneId = zone.id, trigger = SessionTrigger.SCHEDULE, endReason = reason)

    private fun markers(vararg s: TrackingSession) = trackingMarkers(s.toList(), bounds.first, bounds.second)

    @Test
    fun closedSessionGivesAStartAndAStop() {
        val m = markers(session(at(7, 2), at(17, 4), SessionEndReason.SCHEDULE_END))
        assertThat(m).containsExactly(
            TrackingMarker(startedMs = at(7, 2), stoppedMs = null),
            TrackingMarker(startedMs = null, stoppedMs = at(17, 4), reason = SessionEndReason.SCHEDULE_END),
        ).inOrder()
    }

    @Test
    fun openSessionShowsOnlyItsStart() {
        assertThat(markers(session(at(7, 2), null))).containsExactly(TrackingMarker(at(7, 2), null))
    }

    @Test
    fun sessionStartedYesterdayShowsOnlyTodaysStop() {
        val m = markers(session(at(22, 0, date.minusDays(1)), at(6, 30)))
        assertThat(m).containsExactly(TrackingMarker(null, at(6, 30), null))
    }

    @Test
    fun sessionRunningPastMidnightShowsOnlyTodaysStart() {
        val m = markers(session(at(20, 0), at(2, 0, date.plusDays(1))))
        assertThat(m).containsExactly(TrackingMarker(at(20, 0), null))
    }

    @Test
    fun sessionSpanningTheWholeDayHasNoMarkers() {
        assertThat(markers(session(at(20, 0, date.minusDays(1)), null))).isEmpty()
    }

    @Test
    fun stopFollowedByAStartWithinTheWindowCollapses() {
        val m = markers(
            session(at(8, 0), at(12, 56), SessionEndReason.USER_PAUSE),
            session(at(12, 58), at(17, 0), SessionEndReason.SCHEDULE_END),
        )
        assertThat(m).containsExactly(
            TrackingMarker(at(8, 0), null),
            TrackingMarker(startedMs = at(12, 58), stoppedMs = at(12, 56), reason = SessionEndReason.USER_PAUSE),
            TrackingMarker(null, at(17, 0), SessionEndReason.SCHEDULE_END),
        ).inOrder()
        assertThat(m[1].timeMs).isEqualTo(at(12, 56))
        assertThat(m[1].ruleTimes).containsExactly(at(12, 56), at(12, 58)).inOrder()
    }

    @Test
    fun aRestartJustOutsideTheWindowStaysTwoMarkers() {
        val gapMs = TrackingMarkerConfig.MergeWindowMs + 1
        val m = markers(session(at(8, 0), at(12, 0)), session(at(12, 0) + gapMs, null))
        assertThat(m.map { (it.stoppedMs != null) to (it.startedMs != null) })
            .containsExactly(false to true, true to false, false to true).inOrder()
    }

    @Test
    fun placementPushesOverlappingLabelsDownAndFlipsAboveAtTheBottom() {
        val start = at(8, 0)
        val markers = listOf(TrackingMarker(null, at(9, 0)), TrackingMarker(at(9, 5), null), TrackingMarker(null, at(11, 0)))
        val placed = placeTrackingMarkers(markers, start, at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f, labelHeightDp = 16f, gapDp = 2f)
        assertThat(placed.map { it.ruleDps.first() }).containsExactly(60f, 65f, 180f).inOrder()
        assertThat(placed[0].labelTopDp).isEqualTo(62f)
        assertThat(placed[1].labelTopDp).isEqualTo(78f) // pushed under the first label
        assertThat(placed[2].labelTopDp).isEqualTo(162f) // no room below the rule: above it
    }
}
