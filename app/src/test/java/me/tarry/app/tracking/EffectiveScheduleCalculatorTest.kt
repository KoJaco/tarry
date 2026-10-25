package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.Schedule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class EffectiveScheduleCalculatorTest {
    private val config = TrackingConfig()
    private val calculator = EffectiveScheduleCalculator(config)
    private val sydney = ZoneId.of("Australia/Sydney")
    private val template = ScheduleInputs(Schedule.MON_FRI_7_TO_17)

    /** October 2026: the 19th is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10): Instant =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, sydney).toInstant()

    private fun override(
        type: ManualOverrideType,
        mode: ManualOverrideEndMode,
        start: Instant,
        end: Instant? = null,
        active: Boolean = true,
    ) = ManualOverride(
        id = start.toEpochMilli(),
        type = type,
        start = start.toEpochMilli(),
        end = end?.toEpochMilli(),
        zoneId = sydney.id,
        endMode = mode,
        active = active,
    )

    private fun calc(inputs: ScheduleInputs, instant: Instant) = calculator.calculate(inputs, instant, sydney)

    @Test
    fun windowEdgesAreStartInclusiveEndExclusive() {
        assertThat(calc(template, at(19, 6, 59)).isActive).isFalse()
        assertThat(calc(template, at(19, 6, 59)).nextTransition).isEqualTo(at(19, 7))
        assertThat(calc(template, at(19, 7)).isActive).isTrue()
        assertThat(calc(template, at(19, 7)).nextTransition).isEqualTo(at(19, 17))
        assertThat(calc(template, at(19, 16, 59)).isActive).isTrue()
        assertThat(calc(template, at(19, 17)).isActive).isFalse()
    }

    @Test
    fun currentWindowBoundsAreReported() {
        assertThat(calc(template, at(19, 10)).currentWindow).isEqualTo(WindowBounds(at(19, 7), at(19, 17)))
        assertThat(calc(template, at(19, 18)).currentWindow).isNull()
    }

    @Test
    fun fridayEveningSkipsTheWeekendToMonday() {
        val result = calc(template, at(23, 18))
        assertThat(result.isActive).isFalse()
        assertThat(result.nextTransition).isEqualTo(at(26, 7))
    }

    @Test
    fun weekOverrideReplacesTemplateForThatWeekOnly() {
        // Week of 2026-10-19: only Tuesday 09:00-10:00 is active.
        val week = Schedule.EMPTY.set(DayOfWeek.TUESDAY, 18, true).set(DayOfWeek.TUESDAY, 19, true)
        val inputs = template.copy(weekOverrides = mapOf(LocalDate.of(2026, 10, 19) to week))

        assertThat(calc(inputs, at(19, 10)).isActive).isFalse()
        assertThat(calc(inputs, at(20, 9, 30)).isActive).isTrue()
        assertThat(calc(inputs, at(20, 9, 30)).currentWindow).isEqualTo(WindowBounds(at(20, 9), at(20, 10)))
        // The following week falls back to the template.
        assertThat(calc(inputs, at(26, 10)).isActive).isTrue()
    }

    @Test
    fun windowsMergeAcrossMidnight() {
        val schedule = Schedule.EMPTY.set(DayOfWeek.MONDAY, 47, true).set(DayOfWeek.TUESDAY, 0, true)
        val result = calc(ScheduleInputs(schedule), at(19, 23, 45))
        assertThat(result.currentWindow).isEqualTo(WindowBounds(at(19, 23, 30), at(20, 0, 30)))
    }

    @Test
    fun emptyScheduleHasNoTransition() {
        val result = calc(ScheduleInputs(Schedule.EMPTY), at(19, 10))
        assertThat(result.isActive).isFalse()
        assertThat(result.nextTransition).isNull()
    }

    @Test
    fun debugTestWindowIsLayeredIn() {
        val start = at(19, 20)
        val inputs = template.copy(testWindowStartMs = start.toEpochMilli())
        assertThat(calc(inputs, start.minusSeconds(1)).isActive).isFalse()
        assertThat(calc(inputs, start.minusSeconds(1)).nextTransition).isEqualTo(start)
        assertThat(calc(inputs, start).isActive).isTrue()
        assertThat(calc(inputs, start).nextTransition).isEqualTo(start.plusMillis(config.debugTestWindowLengthMs))
    }

    @Test
    fun manualStartUntilStoppedHoldsOutsideWindow() {
        val inputs = template.copy(
            manualOverrides = listOf(override(ManualOverrideType.START, ManualOverrideEndMode.UNTIL_STOPPED, at(19, 20))),
        )
        val result = calc(inputs, at(19, 23))
        assertThat(result.isActive).isTrue()
        assertThat(result.manuallyStarted).isTrue()
        // The only change ahead is the next scheduled window, which is also active: no transition.
        assertThat(result.nextTransition).isNull()
    }

    @Test
    fun manualStartEndOfDayEndsAtLocalMidnight() {
        val inputs = template.copy(
            manualOverrides = listOf(override(ManualOverrideType.START, ManualOverrideEndMode.END_OF_DAY, at(19, 20))),
        )
        assertThat(calc(inputs, at(19, 23, 59)).isActive).isTrue()
        assertThat(calc(inputs, at(19, 23, 59)).nextTransition).isEqualTo(at(20, 0))
        assertThat(calc(inputs, at(20, 0)).isActive).isFalse()
    }

    @Test
    fun manualStartDurationUsesStoredEnd() {
        val start = at(19, 20)
        val inputs = template.copy(
            manualOverrides = listOf(
                override(ManualOverrideType.START, ManualOverrideEndMode.DURATION, start, end = start.plus(Duration.ofHours(2))),
            ),
        )
        assertThat(calc(inputs, at(19, 21, 59)).isActive).isTrue()
        assertThat(calc(inputs, at(19, 22)).isActive).isFalse()
    }

    @Test
    fun manualStartNextWindowHandsOverToTheSchedule() {
        val inputs = template.copy(
            manualOverrides = listOf(override(ManualOverrideType.START, ManualOverrideEndMode.NEXT_WINDOW, at(19, 20))),
        )
        assertThat(calc(inputs, at(20, 6, 59)).isActive).isTrue()
        // At 07:00 the schedule takes over without a gap.
        assertThat(calc(inputs, at(20, 7)).isActive).isTrue()
        assertThat(calc(inputs, at(20, 7)).winningOverride).isNull()
    }

    @Test
    fun manualPauseDurationSuppressesWindowThenResumes() {
        val start = at(19, 10)
        val inputs = template.copy(
            manualOverrides = listOf(
                override(ManualOverrideType.PAUSE, ManualOverrideEndMode.DURATION, start, end = start.plus(Duration.ofHours(1))),
            ),
        )
        val paused = calc(inputs, at(19, 10, 30))
        assertThat(paused.isActive).isFalse()
        assertThat(paused.pausedUntil).isEqualTo(at(19, 11))
        assertThat(paused.nextTransition).isEqualTo(at(19, 11))
        assertThat(calc(inputs, at(19, 11)).isActive).isTrue()
    }

    @Test
    fun manualPauseUntilNextWindowEndsAtNextWindowStart() {
        val inputs = template.copy(
            manualOverrides = listOf(override(ManualOverrideType.PAUSE, ManualOverrideEndMode.NEXT_WINDOW, at(19, 10))),
        )
        assertThat(calc(inputs, at(19, 16)).isActive).isFalse()
        assertThat(calc(inputs, at(19, 16)).pausedUntil).isEqualTo(at(20, 7))
        assertThat(calc(inputs, at(20, 6, 59)).isActive).isFalse()
        assertThat(calc(inputs, at(20, 7)).isActive).isTrue()
    }

    @Test
    fun laterOverrideWinsAndInactiveOpenRowsAreIgnored() {
        val pause = override(ManualOverrideType.PAUSE, ManualOverrideEndMode.UNTIL_STOPPED, at(19, 9))
        val start = override(ManualOverrideType.START, ManualOverrideEndMode.UNTIL_STOPPED, at(19, 10))
        val stale = override(ManualOverrideType.PAUSE, ManualOverrideEndMode.UNTIL_STOPPED, at(19, 11), active = false)
        val inputs = template.copy(manualOverrides = listOf(pause, start, stale))
        assertThat(calc(inputs, at(19, 12)).winningOverride).isEqualTo(start)
        assertThat(calc(inputs, at(19, 12)).isActive).isTrue()
    }

    @Test
    fun intersectsActiveDetectsPartialOverlap() {
        assertThat(calculator.intersectsActive(template, at(19, 5), at(19, 8), sydney)).isTrue()
        assertThat(calculator.intersectsActive(template, at(19, 17), at(19, 23), sydney)).isFalse()
        assertThat(calculator.intersectsActive(template, at(19, 18), at(20, 8), sydney)).isTrue()
    }

    @Test
    fun dstStartWindowLosesAnHour() {
        // Sunday 2026-10-04: clocks go 02:00 -> 03:00 in Sydney. Window 01:00-05:00 wall clock.
        val schedule = (2..9).fold(Schedule.EMPTY) { s, slot -> s.set(DayOfWeek.SUNDAY, slot, true) }
        val window = calc(ScheduleInputs(schedule), at(4, 1, 30)).currentWindow!!
        assertThat(window.start).isEqualTo(at(4, 1))
        assertThat(window.end).isEqualTo(at(4, 5))
        assertThat(Duration.between(window.start, window.end)).isEqualTo(Duration.ofHours(3))
    }

    @Test
    fun dstEndWindowGainsAnHour() {
        // Sunday 2026-04-05: clocks go 03:00 -> 02:00 in Sydney.
        val schedule = (2..9).fold(Schedule.EMPTY) { s, slot -> s.set(DayOfWeek.SUNDAY, slot, true) }
        val window = calc(ScheduleInputs(schedule), at(5, 1, 30, month = 4)).currentWindow!!
        assertThat(window.start).isEqualTo(at(5, 1, month = 4))
        assertThat(window.end).isEqualTo(at(5, 5, month = 4))
        assertThat(Duration.between(window.start, window.end)).isEqualTo(Duration.ofHours(5))
    }

    @Test
    fun weekdayWindowStaysAtSevenLocalAcrossDst() {
        // Friday 2026-10-02 is on +10, Monday 2026-10-05 on +11; both start at 07:00 wall clock.
        val next = calc(template, at(2, 18)).nextTransition!!
        assertThat(next).isEqualTo(at(5, 7))
        assertThat(next.atZone(sydney).hour).isEqualTo(7)
    }

    private val sundayDay = (14..33).fold(Schedule.EMPTY) { s, slot -> s.set(DayOfWeek.SUNDAY, slot, true) }

    @Test
    fun sevenToFiveWindowOnSpringForwardSundayIsTenHoursWithCorrectTransitions() {
        // 2026-10-04: the 02:00 jump is before 07:00, so the window is a full 10 h but starts at +11.
        val inputs = ScheduleInputs(sundayDay)
        val before = calc(inputs, at(4, 6, 59))
        assertThat(before.isActive).isFalse()
        assertThat(before.nextTransition).isEqualTo(at(4, 7))
        val inside = calc(inputs, at(4, 12))
        assertThat(inside.currentWindow).isEqualTo(WindowBounds(at(4, 7), at(4, 17)))
        assertThat(Duration.between(at(4, 7), at(4, 17))).isEqualTo(Duration.ofHours(10))
        assertThat(inside.nextTransition).isEqualTo(at(4, 17))
        assertThat(at(4, 7).atZone(sydney).offset.totalSeconds).isEqualTo(11 * 3600)
        // From the prior Sunday evening the next start is 07:00 local on the transition day.
        assertThat(calc(inputs, at(3, 12)).nextTransition).isEqualTo(at(4, 7))
    }

    @Test
    fun sevenToFiveWindowOnFallBackSundayIsTenHoursWithCorrectTransitions() {
        // 2026-04-05: the 03:00 -> 02:00 repeat is before 07:00, so the window is a full 10 h starting at +10.
        val inputs = ScheduleInputs(sundayDay)
        val inside = calc(inputs, at(5, 12, month = 4))
        assertThat(inside.currentWindow).isEqualTo(WindowBounds(at(5, 7, month = 4), at(5, 17, month = 4)))
        assertThat(Duration.between(at(5, 7, month = 4), at(5, 17, month = 4))).isEqualTo(Duration.ofHours(10))
        assertThat(inside.nextTransition).isEqualTo(at(5, 17, month = 4))
        assertThat(at(5, 7, month = 4).atZone(sydney).offset.totalSeconds).isEqualTo(10 * 3600)
        assertThat(calc(inputs, at(4, 12, month = 4)).nextTransition).isEqualTo(at(5, 7, month = 4))
        // After the window the next one is a week away, still at 07:00 local.
        assertThat(calc(inputs, at(5, 18, month = 4)).nextTransition).isEqualTo(at(12, 7, month = 4))
    }

    @Test
    fun fridayWindowEndAndWeekendSkipAreCorrectAcrossBothDstChanges() {
        // Oct 2026: Fri the 2nd is +10, Mon the 5th is +11. Apr 2026: Fri the 3rd is +11, Mon the 6th is +10.
        assertThat(calc(template, at(2, 10)).nextTransition).isEqualTo(at(2, 17))
        assertThat(calc(template, at(3, 12)).nextTransition).isEqualTo(at(5, 7))
        assertThat(calc(template, at(3, 10, month = 4)).nextTransition).isEqualTo(at(3, 17, month = 4))
        assertThat(calc(template, at(4, 12, month = 4)).nextTransition).isEqualTo(at(6, 7, month = 4))
    }

    @Test
    fun overrideWeekBoundaryIsMondayBasedSoSundayStaysInThePreviousWeek() {
        // Override only for the week starting Mon 2026-10-19: Sunday 25th is still that week, Monday 26th is not.
        val week = Schedule.EMPTY.set(DayOfWeek.SUNDAY, 20, true)
        val inputs = template.copy(weekOverrides = mapOf(LocalDate.of(2026, 10, 19) to week))

        assertThat(calc(inputs, at(25, 10, 15)).isActive).isTrue()
        assertThat(calc(inputs, at(26, 10)).isActive).isTrue() // back to the template
        assertThat(calc(inputs, at(26, 6)).isActive).isFalse()
        // Saturday night of the template week jumps over the override Sunday window, not to Monday.
        assertThat(calc(inputs, at(24, 12)).nextTransition).isEqualTo(at(25, 10))
        // After the override window ends, the next transition is the template's Monday start.
        assertThat(calc(inputs, at(25, 12)).nextTransition).isEqualTo(at(26, 7))
    }

    @Test
    fun activeIntervalsClipToTheRangeAndSkipInactiveTime() {
        // Monday 15:00 to Tuesday 06:50: only 15:00-17:00 was active, the night was not.
        assertThat(calculator.activeIntervals(template, at(19, 15), at(20, 6, 50), sydney))
            .containsExactly(WindowBounds(at(19, 15), at(19, 17)))
        // Back inside a later window: the active tail of both windows, clipped at the end.
        assertThat(calculator.activeIntervals(template, at(19, 15), at(20, 10), sydney))
            .containsExactly(WindowBounds(at(19, 15), at(19, 17)), WindowBounds(at(20, 7), at(20, 10)))
        // Entirely outside, or empty range.
        assertThat(calculator.activeIntervals(template, at(19, 18), at(20, 6), sydney)).isEmpty()
        assertThat(calculator.activeIntervals(template, at(19, 10), at(19, 10), sydney)).isEmpty()
    }

    @Test
    fun activeIntervalsMergeAdjacentActiveStretches() {
        val manual = override(ManualOverrideType.START, ManualOverrideEndMode.UNTIL_STOPPED, at(19, 16))
        val inputs = template.copy(manualOverrides = listOf(manual))
        assertThat(calculator.activeIntervals(inputs, at(19, 15), at(19, 20), sydney))
            .containsExactly(WindowBounds(at(19, 15), at(19, 20)))
    }

    private val editWeek = LocalDate.of(2026, 10, 19)

    private fun total(list: List<WindowBounds>) = list.sumOf { Duration.between(it.start, it.end).toMinutes() }

    private fun assertAscendingAndDisjoint(list: List<WindowBounds>) {
        for (w in list) assertThat(w.start).isLessThan(w.end)
        for ((a, b) in list.zipWithNext()) assertThat(a.end).isLessThan(b.start)
    }

    private val monFri = Schedule.MON_FRI_7_TO_17

    private fun edit(at: Instant, templateBefore: Schedule, weekBefore: WeekBefore? = null) =
        ScheduleEdit(at.toEpochMilli(), templateBefore, weekBefore)

    private fun intervals(inputs: ScheduleInputs, from: Instant, to: Instant) =
        calculator.activeIntervals(inputs, from, to, sydney)

    @Test
    fun editAddedSlotsBeforeTheSaveDoNotCount() {
        val painted = monFri.withSlots(DayOfWeek.MONDAY, 10..13, true) // 05:00-07:00
        val inputs = ScheduleInputs(painted, edits = listOf(edit(at(19, 10), monFri)))
        assertThat(intervals(inputs, at(19, 0), at(20, 0))).containsExactly(WindowBounds(at(19, 7), at(19, 17)))
        assertThat(intervals(ScheduleInputs(painted), at(19, 0), at(20, 0)))
            .containsExactly(WindowBounds(at(19, 5), at(19, 17)))
    }

    @Test
    fun editRemovedSlotsBeforeTheSaveStillCount() {
        val erased = monFri.withSlots(DayOfWeek.MONDAY, 16..17, false) // 08:00-09:00
        val inputs = ScheduleInputs(erased, edits = listOf(edit(at(19, 10), monFri)))
        assertThat(intervals(inputs, at(19, 0), at(20, 0))).containsExactly(WindowBounds(at(19, 7), at(19, 17)))
    }

    @Test
    fun editSlotSpanningTheSaveIsClippedAtTheSave() {
        val save = at(19, 10, 15)
        // Added slot 10:00-10:30 spans the save: counts from 10:15.
        val added = ScheduleInputs(Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 20..20, true), edits = listOf(edit(save, Schedule.EMPTY)))
        assertThat(intervals(added, at(19, 0), at(20, 0))).containsExactly(WindowBounds(at(19, 10, 15), at(19, 10, 30)))
        // Removed slot 10:00-10:30 spans the save: ends at 10:15.
        val removed = ScheduleInputs(
            Schedule.EMPTY,
            edits = listOf(edit(save, Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 20..20, true))),
        )
        assertThat(intervals(removed, at(19, 0), at(20, 0))).containsExactly(WindowBounds(at(19, 10), at(19, 10, 15)))
    }

    @Test
    fun editSlotsAfterTheSaveUseTheCurrentSchedule() {
        val painted = monFri.withSlots(DayOfWeek.MONDAY, 36..37, true) // 18:00-19:00
        val inputs = ScheduleInputs(painted, edits = listOf(edit(at(19, 10), monFri)))
        assertThat(intervals(inputs, at(19, 0), at(20, 0)))
            .containsExactly(WindowBounds(at(19, 7), at(19, 17)), WindowBounds(at(19, 18), at(19, 19)))
        assertThat(calc(inputs, at(19, 10)).nextTransition).isEqualTo(at(19, 17))
    }

    @Test
    fun aTemplateSaveOnMondayDoesNotMakeLastSaturdayCount() {
        val withSaturday = monFri.withSlots(DayOfWeek.SATURDAY, 18..19, true) // 09:00-10:00
        val inputs = ScheduleInputs(withSaturday, edits = listOf(edit(at(19, 10), monFri)))
        assertThat(intervals(inputs, at(17, 0), at(18, 0))).isEmpty()
        assertThat(intervals(inputs, at(24, 0), at(25, 0))).containsExactly(WindowBounds(at(24, 9), at(24, 10)))
    }

    @Test
    fun aSundayEditStaysProtectedAfterAMondaySave() {
        val sunday = monFri.withSlots(DayOfWeek.SUNDAY, 12..15, true) // 06:00-08:00
        val monday = sunday.withSlots(DayOfWeek.MONDAY, 10..13, true) // 05:00-07:00
        val inputs = ScheduleInputs(monday, edits = listOf(edit(at(18, 10), monFri), edit(at(19, 10), sunday)))
        assertThat(intervals(inputs, at(18, 0), at(19, 0))).isEmpty()
        assertThat(intervals(inputs, at(19, 0), at(20, 0))).containsExactly(WindowBounds(at(19, 7), at(19, 17)))
        assertThat(intervals(inputs, at(25, 0), at(26, 0))).containsExactly(WindowBounds(at(25, 6), at(25, 8)))
    }

    @Test
    fun aRepeatSaveNeverMakesTheEarlierPartOfASlotCount() {
        val first = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 20..20, true) // save 1 at 10:15 turns on 10:00-10:30
        val second = first.withSlots(DayOfWeek.MONDAY, 30..30, true) // save 2 at 10:45 paints 15:00-15:30
        val inputs = ScheduleInputs(
            second,
            edits = listOf(edit(at(19, 10, 15), Schedule.EMPTY), edit(at(19, 10, 45), first)),
        )
        assertThat(intervals(inputs, at(19, 0), at(20, 0)))
            .containsExactly(WindowBounds(at(19, 10, 15), at(19, 10, 30)), WindowBounds(at(19, 15), at(19, 15, 30)))
    }

    @Test
    fun anOverrideSavedThenClearedJudgesThePastByTheOverrideThatWasInForce() {
        val override = Schedule.EMPTY.withSlots(DayOfWeek.TUESDAY, 24..25, true) // 12:00-13:00
        val inputs = ScheduleInputs(
            monFri, // current: no override for the week
            edits = listOf(
                edit(at(20, 9), monFri, WeekBefore(editWeek, null)), // saved the override
                edit(at(21, 9), monFri, WeekBefore(editWeek, override)), // then cleared it
            ),
        )
        assertThat(intervals(inputs, at(19, 0), at(20, 0))).containsExactly(WindowBounds(at(19, 7), at(19, 17)))
        assertThat(intervals(inputs, at(20, 0), at(21, 0)))
            .containsExactly(WindowBounds(at(20, 7), at(20, 9)), WindowBounds(at(20, 12), at(20, 13)))
        assertThat(intervals(inputs, at(21, 9), at(22, 0))).containsExactly(WindowBounds(at(21, 9), at(21, 17)))
    }

    @Test
    fun aWeekEditLeavesOtherWeeksOverridesAlone() {
        val otherWeek = LocalDate.of(2026, 10, 26)
        val other = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 20..21, true) // 10:00-11:00
        val inputs = ScheduleInputs(
            monFri,
            weekOverrides = mapOf(otherWeek to other),
            edits = listOf(edit(at(19, 10), monFri, WeekBefore(editWeek, null))),
        )
        assertThat(intervals(inputs, at(26, 0), at(27, 0))).containsExactly(WindowBounds(at(26, 10), at(26, 11)))
    }

    @Test
    fun editLeavesNonEditedTimeUntouched() {
        val painted = monFri.withSlots(DayOfWeek.MONDAY, 10..13, true)
        val inputs = ScheduleInputs(painted, edits = listOf(edit(at(19, 10), monFri)))
        assertThat(intervals(inputs, at(26, 0), at(27, 0))).containsExactly(WindowBounds(at(26, 5), at(26, 17)))
    }

    @Test
    fun editAppliesToAWeekOverrideToo() {
        val overrideWeek = Schedule.EMPTY.withSlots(DayOfWeek.TUESDAY, 18..19, true) // 09:00-10:00
        val inputs = ScheduleInputs(
            monFri,
            weekOverrides = mapOf(editWeek to overrideWeek),
            edits = listOf(edit(at(20, 9, 30), monFri, WeekBefore(editWeek, Schedule.EMPTY))),
        )
        assertThat(intervals(inputs, at(20, 0), at(21, 0))).containsExactly(WindowBounds(at(20, 9, 30), at(20, 10)))
    }

    @Test
    fun springForward_paintedNonexistentSlots_giveSaneWindows() {
        // 2026-10-04: clocks jump 02:00 -> 03:00. Painted 02:00, 02:30 and 03:00 (slots 4, 5, 6).
        val sunday = Schedule.EMPTY.withSlots(DayOfWeek.SUNDAY, 4..6, true)
        val list = calculator.activeIntervals(ScheduleInputs(sunday), at(4, 0), at(5, 0), sydney)
        assertAscendingAndDisjoint(list)
        assertThat(total(list)).isEqualTo(30)
        // Saved inside the gap: still no crash, still ordered.
        val edited = ScheduleInputs(sunday, edits = listOf(ScheduleEdit(at(4, 3, 15).toEpochMilli(), Schedule.EMPTY, WeekBefore(LocalDate.of(2026, 9, 28), Schedule.EMPTY))))
        assertAscendingAndDisjoint(calculator.activeIntervals(edited, at(4, 0), at(5, 0), sydney))
    }

    @Test
    fun fallBack_paintedRepeatedHour_givesSaneWindows() {
        // 2026-04-05: clocks go back 03:00 -> 02:00, so 02:00-03:00 happens twice. Painted slots 4, 5, 6.
        val sunday = Schedule.EMPTY.withSlots(DayOfWeek.SUNDAY, 4..6, true)
        val list = calculator.activeIntervals(ScheduleInputs(sunday), at(5, 0, month = 4), at(6, 0, month = 4), sydney)
        assertAscendingAndDisjoint(list)
        // 02:00 AEDT through 03:30 AEST: 1.5 h of wall clock plus the repeated hour.
        assertThat(total(list)).isEqualTo(150)
        val edited = ScheduleInputs(
            sunday,
            edits = listOf(ScheduleEdit(at(5, 2, 45, month = 4).toEpochMilli(), Schedule.EMPTY, WeekBefore(LocalDate.of(2026, 3, 30), Schedule.EMPTY))),
        )
        val editedList = calculator.activeIntervals(edited, at(5, 0, month = 4), at(6, 0, month = 4), sydney)
        assertAscendingAndDisjoint(editedList)
        assertThat(total(editedList)).isAtMost(150)
    }
}
