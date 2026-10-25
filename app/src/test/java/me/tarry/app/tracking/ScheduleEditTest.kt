package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.Schedule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleEditTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val week = LocalDate.of(2026, 10, 19)
    private val base = Schedule.MON_FRI_7_TO_17

    private fun ms(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, sydney).toInstant().toEpochMilli()

    @Test
    fun noPrevious_usesTheEffectiveBitmap() {
        val e = ScheduleEdit.record(null, base, week, ms(19, 10), sydney)
        assertThat(e).isEqualTo(ScheduleEdit(ms(19, 10), week, base))
    }

    @Test
    fun previousInAnotherWeek_isIgnored() {
        val prev = ScheduleEdit(ms(12, 10), LocalDate.of(2026, 10, 12), Schedule.EMPTY)
        val e = ScheduleEdit.record(prev, base, week, ms(19, 10), sydney)
        assertThat(e.before).isEqualTo(base)
    }

    @Test
    fun sameWeek_slotsEndedBeforePreviousSaveKeepThePreviousBitmap() {
        val first = base.withSlots(DayOfWeek.MONDAY, 10..13, true) // 05:00-07:00 painted by save 1
        val prev = ScheduleEdit(ms(19, 10), week, base)
        // Save 2 at 12:00 follows `first`.
        val e = ScheduleEdit.record(prev, first, week, ms(19, 12), sydney)
        assertThat(e.atMs).isEqualTo(ms(19, 12))
        assertThat(e.before.isActive(DayOfWeek.MONDAY, 10)).isFalse() // ended before save 1: still protected
        assertThat(e.before.isActive(DayOfWeek.MONDAY, 22)).isTrue()
        assertThat(e.before.isActive(DayOfWeek.MONDAY, 36)).isFalse()
    }

    @Test
    fun sameWeek_slotEndingExactlyAtPreviousSaveIsProtected_slotStartingThereIsNot() {
        val painted = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 19..20, true) // 09:30-10:30
        val prev = ScheduleEdit(ms(19, 10), week, Schedule.EMPTY)
        val e = ScheduleEdit.record(prev, painted, week, ms(19, 12), sydney)
        assertThat(e.before.isActive(DayOfWeek.MONDAY, 19)).isFalse() // ends 10:00 == prev.atMs
        assertThat(e.before.isActive(DayOfWeek.MONDAY, 20)).isTrue() // starts at prev.atMs
    }
}
