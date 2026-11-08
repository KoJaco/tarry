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
    fun placementCentresLabelsOnTheirTime_pushesOverlapsDown_andKeepsThemInsideTheCanvas() {
        val start = at(8, 0)
        val markers = listOf(TrackingMarker(null, at(9, 0)), TrackingMarker(at(9, 5), null), TrackingMarker(null, at(11, 0)))
        val placed = placeTrackingMarkers(markers, start, at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f, lineHeightDp = 16f, gapDp = 2f)
        assertThat(placed.map { it.anchorDp }).containsExactly(60f, 65f, 180f).inOrder()
        assertThat(placed.map { it.labelHeightDp }).containsExactly(32f, 32f, 32f)
        assertThat(placed[0].labelTopDp).isEqualTo(44f) // centred on 60
        assertThat(placed[1].labelTopDp).isEqualTo(78f) // pushed under the first label (44 + 32 + 2)
        assertThat(placed[2].labelTopDp).isEqualTo(148f) // clamped so it ends at the canvas bottom
    }

    @Test
    fun aLabelUnderTheNowPillMovesAboveIt() {
        // Now pill spans 110..130 (10:00 at 120 dp); a start at 9:55 (anchor 115) would sit under it.
        val placed = placeTrackingMarkers(
            listOf(TrackingMarker(at(9, 55), null)), at(8, 0), at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f,
            lineHeightDp = 16f, gapDp = 2f, avoid = 110f..130f,
        ).single()
        assertThat(placed.labelTopDp).isEqualTo(76f) // 110 - 2 - 32
    }

    @Test
    fun aLabelThatCannotGoAboveThePillGoesBelowIt() {
        // The previous label ends at 106, so above the pill (76) would collide with it: the second label goes below (132).
        val markers = listOf(TrackingMarker(null, at(9, 30)), TrackingMarker(at(9, 55), null))
        val placed = placeTrackingMarkers(
            markers, at(8, 0), at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f, lineHeightDp = 16f, gapDp = 2f, avoid = 110f..130f,
        )
        assertThat(placed[0].labelTopDp).isEqualTo(74f) // centred on 90, clear of the pill
        assertThat(placed[1].labelTopDp).isEqualTo(132f)
    }

    @Test
    fun nearTheCanvasBottomALabelGoesBelowThePill_neverOverlappingAnotherLabel() {
        // Pill 150..166 on a 180 dp canvas; above (116) would overlap the previous label (94..126), so it goes below
        // (168), running into the card padding rather than over text.
        val markers = listOf(TrackingMarker(null, at(9, 50)), TrackingMarker(at(10, 35), null))
        val placed = placeTrackingMarkers(
            markers, at(8, 0), at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f, lineHeightDp = 16f, gapDp = 2f, avoid = 150f..166f,
        )
        assertThat(placed[1].labelTopDp).isEqualTo(168f)
    }

    @Test
    fun aCollapsedPairIsCentredOnTheMiddleOfItsTwoTimes() {
        val pair = TrackingMarker(startedMs = at(9, 2), stoppedMs = at(9, 0))
        val placed = placeTrackingMarkers(listOf(pair), at(8, 0), at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f, lineHeightDp = 16f, gapDp = 2f)
        assertThat(placed.single().anchorDp).isEqualTo(61f)
    }

    @Test
    fun anHourLabelCollidingWithAMarkerIsHidden_onesClearOfItAreNot() {
        val placed = PlacedMarker(TrackingMarker(null, at(9, 0)), anchorDp = 60f, labelTopDp = 44f, labelHeightDp = 32f)
        assertThat(hourLabelCoveredByMarker(60f, placed, 8f)).isTrue()
        assertThat(hourLabelCoveredByMarker(80f, placed, 8f)).isTrue() // its top (72) is inside 44..76
        assertThat(hourLabelCoveredByMarker(84.1f, placed, 8f)).isFalse() // 76.1..92.1 starts below the label
        assertThat(hourLabelCoveredByMarker(35.9f, placed, 8f)).isFalse() // 27.9..43.9 ends above it
    }

    private val words = MarkerWords("Start", "Stop", "Off")
    private fun time(ms: Long) = if (ms == at(12, 5)) "12:05" else if (ms == at(12, 20)) "12:20" else "7:30"

    @Test
    fun startAndStopAreTheWordOverTheTime() {
        assertThat(markerLines(TrackingMarker(at(7, 30), null), words, ::time)).containsExactly("Start", "7:30").inOrder()
        assertThat(markerLines(TrackingMarker(null, at(7, 30)), words, ::time)).containsExactly("Stop", "7:30").inOrder()
    }

    @Test
    fun aCollapsedPairIsAlwaysOffOverTheRange() {
        val pair = TrackingMarker(startedMs = at(12, 20), stoppedMs = at(12, 5))
        assertThat(markerLines(pair, words, ::time)).containsExactly("Off", "12:05–12:20").inOrder()
    }

    @Test
    fun aboveModePutsTheLabelJustAboveItsFirstTime_andBelowItAtTheVeryTop() {
        val markers = listOf(TrackingMarker(null, at(10, 0)), TrackingMarker(at(8, 0), null))
        val placed = placeTrackingMarkers(
            markers, at(8, 0), at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f, lineHeightDp = 16f, gapDp = 2f, above = true,
        )
        // 8:00 is the canvas top: no room above, so the label starts just below it. 10:00 (120 dp) ends 2 dp above its tick.
        assertThat(placed[0].labelTopDp).isEqualTo(2f)
        assertThat(placed[1].labelTopDp).isEqualTo(120f - 2f - 32f)
        assertThat(placed[1].labelTopDp).isAtLeast(placed[0].labelTopDp + placed[0].labelHeightDp + 2f - 0.001f)
    }

    @Test
    fun aboveModeStillKeepsClearOfTheNowPill() {
        val placed = placeTrackingMarkers(
            listOf(TrackingMarker(at(10, 5), null)), at(8, 0), at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f,
            lineHeightDp = 16f, gapDp = 2f, avoid = 100f..130f, above = true,
        ).single()
        assertThat(top(placed, 100f..130f)).isTrue()
    }

    private fun top(p: PlacedMarker, pill: ClosedFloatingPointRange<Float>) = p.labelTopDp + p.labelHeightDp <= pill.start || p.labelTopDp >= pill.endInclusive

    private val dayEnd = bounds.second

    @Test
    fun offStretchRunsFromAStopToTheNextStart() {
        val stretches = offStretches(
            listOf(TrackingMarker(at(7, 0), null), TrackingMarker(null, at(12, 0)), TrackingMarker(at(13, 0), null)),
            dayEnd, now = null,
        )
        assertThat(stretches).containsExactly(OffStretch(at(12, 0), at(13, 0)))
    }

    @Test
    fun aStopNeverFollowedByAStartRunsToNowTodayAndToTheDayEndOnAPastDay() {
        val stop = listOf(TrackingMarker(null, at(17, 0)))
        assertThat(offStretches(stop, dayEnd, now = at(20, 0))).containsExactly(OffStretch(at(17, 0), at(20, 0)))
        assertThat(offStretches(stop, dayEnd, now = null)).containsExactly(OffStretch(at(17, 0), dayEnd))
    }

    @Test
    fun aCollapsedPairIsItsOwnShortStretch_andPlainStartsMakeNone() {
        val pair = TrackingMarker(startedMs = at(12, 20), stoppedMs = at(12, 5))
        assertThat(offStretches(listOf(pair), dayEnd, null)).containsExactly(OffStretch(at(12, 5), at(12, 20)))
        assertThat(offStretches(listOf(TrackingMarker(at(7, 0), null)), dayEnd, now = at(20))).isEmpty()
    }

    @Test
    fun firstTrackingStartIgnoresStopsAndTakesTheEarliestStart() {
        val markers = listOf(TrackingMarker(null, at(0, 5)), TrackingMarker(at(7, 30), null), TrackingMarker(at(13, 0), null))
        assertThat(firstTrackingStart(markers)).isEqualTo(at(7, 30))
        assertThat(firstTrackingStart(listOf(TrackingMarker(null, at(17, 0))))).isNull()
    }

    @Test
    fun stackedSlotPrefersAboveThenBelowThenOverTheBlocks() {
        // Tick at 100, label 32 tall, gap 2: above is 66..98, below is 102..134.
        assertThat(stackedMarkerSlot(100f, 32f, 2f, emptyList())).isEqualTo(MarkerSlot.Above)
        assertThat(stackedMarkerSlot(100f, 32f, 2f, listOf(70f..90f))).isEqualTo(MarkerSlot.Below)
        assertThat(stackedMarkerSlot(100f, 32f, 2f, listOf(70f..90f, 110f..140f))).isEqualTo(MarkerSlot.OverBlocks)
        // A block that only touches the label's space edge does not cover it.
        assertThat(stackedMarkerSlot(100f, 32f, 2f, listOf(0f..66f, 134f..200f))).isEqualTo(MarkerSlot.Above)
        // No room above at the top of the canvas.
        assertThat(stackedMarkerSlot(10f, 32f, 2f, emptyList())).isEqualTo(MarkerSlot.Below)
    }

    @Test
    fun stackedPlacementPutsTheLabelOnTheFreeSideAndFlagsOverBlocks() {
        val tick = listOf(TrackingMarker(at(9, 0), null)) // 60 dp from 8:00
        fun place(blocks: List<ClosedFloatingPointRange<Float>>) = placeTrackingMarkers(
            tick, at(8, 0), at(11, 0), dpPerHour = 60f, canvasHeightDp = 180f, lineHeightDp = 16f, gapDp = 2f, above = true, blocks = blocks,
        ).single()
        assertThat(place(emptyList()).labelTopDp).isEqualTo(26f) // 60 - 32 - 2
        val below = place(listOf(20f..58f))
        assertThat(below.labelTopDp).isEqualTo(62f)
        assertThat(below.overBlocks).isFalse()
        val both = place(listOf(20f..58f, 62f..120f))
        assertThat(both.labelTopDp).isEqualTo(26f)
        assertThat(both.overBlocks).isTrue()
    }
}
