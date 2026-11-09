package me.tarry.app.ui.schedule

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

class ScheduleLabelsTest {
    private val au = Locale("en", "AU")

    @Test
    fun shortDayNamesHaveNoAbbreviationDotsInEnAu() {
        assertThat(DayOfWeek.values().map { shortDayName(it, au) })
            .containsExactly("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").inOrder()
    }

    @Test
    fun theScheduleSummaryUsesThem() {
        val summary = ScheduleSummary.Uniform(
            listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), 14, 34,
        )
        assertThat(formatUniform(summary, { shortDayName(it, au) }, ::slotTime)).isEqualTo("Mon–Fri 07:00–17:00")
    }
}
