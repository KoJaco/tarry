package me.tarry.app.ui

import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.shell.untilTime
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class UntilTimeTest {
    private val brisbane = ZoneId.of("Australia/Brisbane")
    private val sydney = ZoneId.of("Australia/Sydney")

    private fun at(zone: ZoneId, date: LocalDate, h: Int, m: Int, s: Int = 0) =
        ZonedDateTime.of(date, LocalTime.of(h, m, s), zone)

    private fun inZone(zone: ZoneId, date: LocalDate, h: Int, m: Int) = at(zone, date, h, m).toInstant()

    @Test
    fun laterToday() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 10, 0)
        assertThat(untilTime(now, LocalTime.of(11, 15))).isEqualTo(inZone(brisbane, LocalDate.of(2026, 3, 10), 11, 15))
    }

    @Test
    fun theEndIsTheExactMinuteNotMinutesFromNow() {
        // 10:00:40 to "until 11:00" is 59 min 20 s; a whole-minute count from now would end up to 59 s late.
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 10, 0, 40)
        assertThat(untilTime(now, LocalTime.of(11, 0))).isEqualTo(inZone(brisbane, LocalDate.of(2026, 3, 10), 11, 0))
    }

    @Test
    fun theCurrentMinuteMeansTomorrow() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 10, 0, 40)
        assertThat(untilTime(now, LocalTime.of(10, 0))).isEqualTo(inZone(brisbane, LocalDate.of(2026, 3, 11), 10, 0))
    }

    @Test
    fun anEarlierTimeMeansTomorrow() {
        val now = at(brisbane, LocalDate.of(2026, 3, 10), 22, 0)
        assertThat(untilTime(now, LocalTime.of(6, 30))).isEqualTo(inZone(brisbane, LocalDate.of(2026, 3, 11), 6, 30))
    }

    @Test
    fun springForwardNightIsOneHourShorter() {
        // Sydney clocks go 02:00 -> 03:00 on 4 October 2026: 22:00 to 07:00 is 8 elapsed hours.
        val now = at(sydney, LocalDate.of(2026, 10, 3), 22, 0)
        val until = untilTime(now, LocalTime.of(7, 0))
        assertThat(until).isEqualTo(inZone(sydney, LocalDate.of(2026, 10, 4), 7, 0))
        assertThat(until.toEpochMilli() - now.toInstant().toEpochMilli()).isEqualTo(8 * 3_600_000L)
    }

    @Test
    fun fallBackNightIsOneHourLonger() {
        // Sydney clocks go 03:00 -> 02:00 on 5 April 2026: 22:00 to 07:00 is 10 elapsed hours.
        val now = at(sydney, LocalDate.of(2026, 4, 4), 22, 0)
        val until = untilTime(now, LocalTime.of(7, 0))
        assertThat(until).isEqualTo(inZone(sydney, LocalDate.of(2026, 4, 5), 7, 0))
        assertThat(until.toEpochMilli() - now.toInstant().toEpochMilli()).isEqualTo(10 * 3_600_000L)
    }

    @Test
    fun sameTimeTomorrowOnAFallBackDayIsCappedAtTwentyFourRealHours() {
        // 5 April 2026 is 25 h long in Sydney: 10:00 on the 4th to 10:00 on the 5th would be almost 25 h, so it is capped at now + 24 h.
        val now = at(sydney, LocalDate.of(2026, 4, 4), 10, 0, 30)
        val until = untilTime(now, LocalTime.of(10, 0))
        assertThat(until).isEqualTo(now.toInstant().plusSeconds(24 * 3600))
    }

    @Test
    fun aTimeInTheSkippedHourLandsAfterTheGap() {
        // 02:30 does not exist on 4 October 2026 in Sydney; it resolves to 03:30.
        val now = at(sydney, LocalDate.of(2026, 10, 4), 0, 0)
        assertThat(untilTime(now, LocalTime.of(2, 30))).isEqualTo(Instant.parse("2026-10-03T16:30:00Z"))
    }
}
