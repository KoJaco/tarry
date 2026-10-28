package me.tarry.app.ui.schedule

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.Schedule
import org.junit.Test
import java.time.DayOfWeek

class ScheduleSummaryTest {
    private fun fmt(u: ScheduleSummary.Uniform) = formatUniform(
        u,
        dayName = { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) },
        time = { "%02d:%02d".format(it / 2 % 24, it % 2 * 30) },
    )

    @Test
    fun empty_isOff() {
        assertThat(summarize(Schedule.EMPTY)).isEqualTo(ScheduleSummary.Off)
    }

    @Test
    fun preset_isUniformMonFri() {
        val s = summarize(Schedule.MON_FRI_7_TO_17)
        assertThat(s).isEqualTo(
            ScheduleSummary.Uniform(DayOfWeek.entries.take(5), startSlot = 14, endSlotExclusive = 34),
        )
        assertThat(fmt(s as ScheduleSummary.Uniform)).isEqualTo("Mon–Fri 07:00–17:00")
    }

    @Test
    fun differentRanges_areCustom() {
        val s = Schedule.MON_FRI_7_TO_17.withSlots(DayOfWeek.FRIDAY, 34..35, true)
        assertThat(summarize(s)).isEqualTo(ScheduleSummary.Custom)
    }

    @Test
    fun twoRangesOnADay_isCustom() {
        val s = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 14..16, true).withSlots(DayOfWeek.MONDAY, 20..22, true)
        assertThat(summarize(s)).isEqualTo(ScheduleSummary.Custom)
    }

    @Test
    fun nonConsecutiveDays_useCommaList() {
        val s = Schedule.EMPTY
            .withSlots(DayOfWeek.MONDAY, 14..33, true)
            .withSlots(DayOfWeek.WEDNESDAY, 14..33, true)
            .withSlots(DayOfWeek.THURSDAY, 14..33, true)
            .withSlots(DayOfWeek.FRIDAY, 14..33, true)
            .withSlots(DayOfWeek.SUNDAY, 14..33, true)
        assertThat(fmt(summarize(s) as ScheduleSummary.Uniform)).isEqualTo("Mon, Wed–Fri, Sun 07:00–17:00")
    }

    @Test
    fun singleDay_andAllDayRange() {
        val s = Schedule.EMPTY.withSlots(DayOfWeek.SATURDAY, 0..47, true)
        val u = summarize(s) as ScheduleSummary.Uniform
        assertThat(u.endSlotExclusive).isEqualTo(48)
        assertThat(fmt(u)).isEqualTo("Sat 00:00–00:00")
    }
}
