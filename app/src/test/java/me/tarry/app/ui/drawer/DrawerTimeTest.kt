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
    fun aPickedEndBeforeTheStartResolvesToTheNextDay() {
        val start = at(day, 22, 0)
        val end = at(day, 23, 50)
        assertThat(instantForEdge(0, 10, zone, end, TimeEdge.END, start, start, end)).isEqualTo(at(day.plusDays(1), 0, 10))
    }

    @Test
    fun aPickedStartAfterTheEndResolvesToThePreviousDay() {
        val start = at(day, 0, 10)
        val end = at(day, 2, 0)
        assertThat(instantForEdge(23, 50, zone, start, TimeEdge.START, end, start, end)).isEqualTo(at(day.minusDays(1), 23, 50))
    }

    @Test
    fun anEdgePickThatKeepsTheOrderNeverChangesDay() {
        val start = at(day, 9, 0)
        val end = at(day, 17, 0)
        assertThat(instantForEdge(18, 0, zone, end, TimeEdge.END, start, start, end)).isEqualTo(at(day, 18, 0))
        assertThat(instantForEdge(8, 0, zone, start, TimeEdge.START, end, start, end)).isEqualTo(at(day, 8, 0))
        // New-visit form: only the anchor, no segment range.
        assertThat(instantForEdge(23, 50, zone, at(day, 22, 0), TimeEdge.END, at(day, 22, 0))).isEqualTo(at(day, 23, 50))
        assertThat(instantForEdge(0, 10, zone, at(day, 23, 50), TimeEdge.END, at(day, 22, 0))).isEqualTo(at(day.plusDays(1), 0, 10))
        assertThat(instantForEdge(23, 0, zone, at(day, 0, 10), TimeEdge.START, at(day, 2, 0))).isEqualTo(at(day.minusDays(1), 23, 0))
    }

    @Test
    fun aSegmentCrossingMidnightCanBeTrimmedOnEitherSide() {
        val start = at(day, 22, 0)
        val end = at(day.plusDays(1), 1, 0)
        assertThat(instantNear(23, 30, zone, start, start, end)).isEqualTo(at(day, 23, 30))
        assertThat(instantNear(0, 20, zone, end, start, end)).isEqualTo(at(day.plusDays(1), 0, 20))
    }

    @Test
    fun aSegmentEndingAtMidnightStaysOnTheDayItCloses() {
        val start = at(day, 22, 0)
        val end = at(day.plusDays(1), 0, 0)
        assertThat(instantNear(0, 30, zone, end, start, end)).isEqualTo(at(day, 0, 30))
        assertThat(instantForEdge(0, 30, zone, end, TimeEdge.END, start, start, end)).isEqualTo(at(day.plusDays(1), 0, 30))
    }

    @Test
    fun theDstChangeDayInSydneyResolvesOnItsOwnDate() {
        // 2026-10-04: clocks go from 02:00 to 03:00 (AEST to AEDT). The day is 23 hours long.
        val dst = LocalDate.of(2026, 10, 4)
        val start = at(dst, 1, 0)
        val end = at(dst, 23, 30)
        assertThat(instantNear(14, 0, zone, start, start, end)).isEqualTo(at(dst, 14, 0))
        assertThat(instantNear(23, 45, zone, end, start, end)).isEqualTo(at(dst, 23, 45))
        // 02:30 does not exist that day; it resolves to 03:30 on the same date rather than the neighbouring day.
        assertThat(instantNear(2, 30, zone, start, start, end)).isEqualTo(at(dst, 3, 30))
    }

    @Test
    fun theDstEndDayResolvesOnItsOwnDate() {
        // 2027-04-04: clocks go back from 03:00 to 02:00; the day is 25 hours long.
        val back = LocalDate.of(2027, 4, 4)
        val start = at(back, 0, 30)
        val end = at(back, 23, 0)
        assertThat(instantNear(23, 50, zone, end, start, end)).isEqualTo(at(back, 23, 50))
        assertThat(instantNear(0, 10, zone, start, start, end)).isEqualTo(at(back, 0, 10))
    }
}
