package me.tarry.app.ui.drawer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DrawerTimeTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 10, 21)

    private fun at(date: LocalDate, hour: Int, minute: Int) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun sameDayTimeKeepsTheAnchorsDate() {
        assertThat(instantNear(10, 30, zone, at(day, 9, 0))).isEqualTo(at(day, 10, 30))
    }

    @Test
    fun lateEveningPickedFromJustAfterMidnightIsTheDayBefore() {
        assertThat(instantNear(23, 50, zone, at(day, 0, 10))).isEqualTo(at(day.minusDays(1), 23, 50))
    }

    @Test
    fun earlyMorningPickedFromJustBeforeMidnightIsTheNextDay() {
        assertThat(instantNear(0, 20, zone, at(day, 23, 40))).isEqualTo(at(day.plusDays(1), 0, 20))
    }
}
