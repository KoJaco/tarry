package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.Schedule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class ScheduleEditTest {
    private val week = LocalDate.of(2026, 10, 19)
    private val otherWeek = LocalDate.of(2026, 10, 26)
    private val base = Schedule.MON_FRI_7_TO_17
    private val dayMs = 24 * 60 * 60 * 1000L

    private fun edit(atMs: Long) = ScheduleEdit(atMs, base)

    @Test
    fun appendKeepsAscendingOrder() {
        val out = ScheduleEdit.append(listOf(edit(2 * dayMs)), edit(3 * dayMs), retentionDays = 14, max = 50)
        assertThat(out.map { it.atMs }).containsExactly(2 * dayMs, 3 * dayMs).inOrder()
    }

    @Test
    fun appendDropsEditsOlderThanTheRetention() {
        val history = listOf(edit(1 * dayMs), edit(10 * dayMs), edit(20 * dayMs))
        val out = ScheduleEdit.append(history, edit(25 * dayMs), retentionDays = 14, max = 50)
        // Oldest kept: 25 - 14 = day 11, so days 1 and 10 go.
        assertThat(out.map { it.atMs }).containsExactly(20 * dayMs, 25 * dayMs).inOrder()
    }

    @Test
    fun appendKeepsAnEditExactlyAtTheRetentionBoundary() {
        val out = ScheduleEdit.append(listOf(edit(11 * dayMs)), edit(25 * dayMs), retentionDays = 14, max = 50)
        assertThat(out.map { it.atMs }).containsExactly(11 * dayMs, 25 * dayMs).inOrder()
    }

    @Test
    fun appendCapsTheCountDroppingTheOldest() {
        val history = (1..5).map { edit(it * 1000L) }
        val out = ScheduleEdit.append(history, edit(6000L), retentionDays = 14, max = 3)
        assertThat(out.map { it.atMs }).containsExactly(4000L, 5000L, 6000L).inOrder()
    }

    @Test
    fun scheduleAtUndoesOnlyTheEditsAfterTheInstant() {
        val t1 = base.withSlots(DayOfWeek.SATURDAY, 18..19, true)
        val t2 = t1.withSlots(DayOfWeek.SUNDAY, 18..19, true)
        val inputs = ScheduleInputs(t2, edits = listOf(ScheduleEdit(100, base), ScheduleEdit(200, t1)))
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(50))).isEqualTo(base)
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(100))).isEqualTo(t1) // an edit counts from its own instant
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(199))).isEqualTo(t1)
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(200))).isEqualTo(t2)
    }

    @Test
    fun scheduleAtUsesTheOverrideBeforeOnlyForTheEditedWeek() {
        val override = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 20..20, true)
        val other = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 22..22, true)
        val inputs = ScheduleInputs(
            base,
            weekOverrides = mapOf(otherWeek to other),
            edits = listOf(ScheduleEdit(100, base, WeekBefore(week, override))),
        )
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(50))).isEqualTo(override)
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(100))).isEqualTo(base) // cleared since: current has none
        assertThat(inputs.scheduleAt(otherWeek, Instant.ofEpochMilli(50))).isEqualTo(other)
    }

    @Test
    fun scheduleAtTreatsANullOverrideBeforeAsNoOverride() {
        val current = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 20..20, true)
        val inputs = ScheduleInputs(
            base,
            weekOverrides = mapOf(week to current),
            edits = listOf(ScheduleEdit(100, base, WeekBefore(week, null))),
        )
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(50))).isEqualTo(base)
        assertThat(inputs.scheduleAt(week, Instant.ofEpochMilli(100))).isEqualTo(current)
    }
}
