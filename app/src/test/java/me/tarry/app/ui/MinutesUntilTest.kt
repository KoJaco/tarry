package me.tarry.app.ui

import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.shell.minutesUntil
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MinutesUntilTest {
    private val brisbane = ZoneId.of("Australia/Brisbane")
    private val sydney = ZoneId.of("Australia/Sydney")

    private fun at(zone: ZoneId, date: LocalDate, h: Int, m: Int, s: Int = 0) =
        ZonedDateTime.of(date, LocalTime.of(h, m, s), zone)

    @Test
    fun laterToday() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 10, 0)
        assertThat(minutesUntil(now, LocalTime.of(11, 15))).isEqualTo(75)
    }

    @Test
    fun secondsAreIgnored() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 10, 0, 40)
        assertThat(minutesUntil(now, LocalTime.of(11, 0))).isEqualTo(60)
    }

    @Test
    fun theSameMinuteMeansTomorrow() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 10, 0, 40)
        assertThat(minutesUntil(now, LocalTime.of(10, 0))).isEqualTo(1440)
    }

    @Test
    fun anEarlierTimeMeansTomorrow() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 22, 0)
        assertThat(minutesUntil(now, LocalTime.of(6, 30))).isEqualTo(510)
    }

    @Test
    fun justPastTheTimeIsAlmostADay() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 10, 1)
        assertThat(minutesUntil(now, LocalTime.of(10, 0))).isEqualTo(1439)
    }

    @Test
    fun springForwardNightIsOneHourShorter() {
        // Sydney clocks go 02:00 -> 03:00 on 4 October 2026: 22:00 to 07:00 is 8 elapsed hours.
        val now = at(sydney, LocalDate.of(2026, 10, 3), 22, 0)
        assertThat(minutesUntil(now, LocalTime.of(7, 0))).isEqualTo(8 * 60)
    }

    @Test
    fun fallBackNightIsOneHourLonger() {
        // Sydney clocks go 03:00 -> 02:00 on 5 April 2026: 22:00 to 07:00 is 10 elapsed hours.
        val now = at(sydney, LocalDate.of(2026, 4, 4), 22, 0)
        assertThat(minutesUntil(now, LocalTime.of(7, 0))).isEqualTo(10 * 60)
    }

    @Test
    fun aTimeInTheSkippedHourLandsAfterTheGap() {
        // 02:30 does not exist on 4 October 2026 in Sydney; it resolves to 03:30, 150 elapsed minutes after 00:00.
        val now = at(sydney, LocalDate.of(2026, 10, 4), 0, 0)
        assertThat(minutesUntil(now, LocalTime.of(2, 30))).isEqualTo(150)
    }
}
