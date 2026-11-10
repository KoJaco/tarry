package me.tarry.app.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NextReviewReminderAtTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val monFri: (LocalDate) -> Boolean = { it.dayOfWeek in weekdays }
    private val at1730 = 17 * 60 + 30

    private fun inSydney(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Instant =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, sydney).toInstant()

    private val grace = 2 * 60 * 60_000L

    private fun next(
        now: Instant,
        zone: ZoneId = sydney,
        minute: Int = at1730,
        ahead: Int = 9,
        lastFired: LocalDate? = null,
        scheduled: (LocalDate) -> Boolean = monFri,
    ) =
        nextReviewReminderAt(now, zone, minute, ahead, grace, lastFired, scheduled)

    // 2026-11-09 is a Monday.
    @Test
    fun beforeTheTimeOnAScheduledDayFiresToday() {
        assertThat(next(inSydney(2026, 11, 9, 9))).isEqualTo(inSydney(2026, 11, 9, 17, 30))
    }

    @Test
    fun afterTheTimeFiresOnTheNextScheduledDay() {
        assertThat(next(inSydney(2026, 11, 9, 19, 31))).isEqualTo(inSydney(2026, 11, 10, 17, 30))
    }

    @Test
    fun exactlyAtTheTimeUnfiredStillFiresNow() {
        assertThat(next(inSydney(2026, 11, 9, 17, 30))).isEqualTo(inSydney(2026, 11, 9, 17, 30))
    }

    @Test
    fun openingTheAppAfterTheTimeBeforeItFiredKeepsTodayAndFiresPromptly() {
        // 17:33, the alarm (17:30 + up to 10 min) has not run: arm at now, not tomorrow.
        val now = inSydney(2026, 11, 9, 17, 33)
        assertThat(next(now)).isEqualTo(now)
    }

    @Test
    fun afterItFiredTodayMovesToTheNextScheduledDay() {
        val now = inSydney(2026, 11, 9, 17, 33)
        assertThat(next(now, lastFired = LocalDate.of(2026, 11, 9))).isEqualTo(inSydney(2026, 11, 10, 17, 30))
    }

    @Test
    fun aFiredTodayGuardHoldsEvenBeforeTheTime() {
        // A stale guard for today (for example the time moved later after it fired) keeps to once a day.
        assertThat(next(inSydney(2026, 11, 9, 9), lastFired = LocalDate.of(2026, 11, 9))).isEqualTo(inSydney(2026, 11, 10, 17, 30))
    }

    @Test
    fun pastTheGraceWithoutAFireSkipsToTheNextScheduledDay() {
        // 19:31, one minute past the 2 h grace, with the phone off all evening: today's reminder is not shown late.
        assertThat(next(inSydney(2026, 11, 9, 19, 31))).isEqualTo(inSydney(2026, 11, 10, 17, 30))
    }

    @Test
    fun theLastGraceMinuteStillCounts() {
        val now = inSydney(2026, 11, 9, 19, 29)
        assertThat(next(now)).isEqualTo(now)
    }

    @Test
    fun anOldFiredDateDoesNotBlockToday() {
        val now = inSydney(2026, 11, 9, 9)
        assertThat(next(now, lastFired = LocalDate.of(2026, 11, 6))).isEqualTo(inSydney(2026, 11, 9, 17, 30))
    }

    @Test
    fun anUnscheduledTodayInsideTheGraceIsStillSkipped() {
        // Saturday 17:33 on a Mon-Fri schedule.
        assertThat(next(inSydney(2026, 11, 14, 17, 33))).isEqualTo(inSydney(2026, 11, 16, 17, 30))
    }

    @Test
    fun skipsUnscheduledDays() {
        // Friday evening: Saturday and Sunday have no work window, so Monday.
        assertThat(next(inSydney(2026, 11, 13, 19, 31))).isEqualTo(inSydney(2026, 11, 16, 17, 30))
        // A Saturday morning on a Mon-Fri schedule.
        assertThat(next(inSydney(2026, 11, 14, 8))).isEqualTo(inSydney(2026, 11, 16, 17, 30))
    }

    @Test
    fun noScheduledDaysMeansNoAlarm() {
        assertThat(next(inSydney(2026, 11, 9, 9), scheduled = { false })).isNull()
    }

    @Test
    fun aScheduledDayBeyondTheLookAheadIsNotFound() {
        val far = LocalDate.of(2026, 11, 20)
        assertThat(next(inSydney(2026, 11, 9, 9), ahead = 9, scheduled = { it == far })).isNull()
        assertThat(next(inSydney(2026, 11, 9, 9), ahead = 11, scheduled = { it == far })).isEqualTo(inSydney(2026, 11, 20, 17, 30))
    }

    @Test
    fun aTimeInTheDstGapMovesForwardByTheGap() {
        // 4 Oct 2026: clocks go from 02:00 to 03:00, so 02:30 does not exist and becomes 03:30 (AEDT).
        val now = inSydney(2026, 10, 3, 23)
        val sunday: (LocalDate) -> Boolean = { it == LocalDate.of(2026, 10, 4) }
        assertThat(next(now, minute = 2 * 60 + 30, scheduled = sunday)).isEqualTo(inSydney(2026, 10, 4, 3, 30))
    }

    @Test
    fun aTimeThatHappensTwiceAtTheEndOfDstTakesTheFirst() {
        // 5 Apr 2026: clocks go from 03:00 back to 02:00, so 02:30 happens twice; the first is still AEDT (+11:00).
        val now = inSydney(2026, 4, 4, 23)
        val sunday: (LocalDate) -> Boolean = { it == LocalDate.of(2026, 4, 5) }

        val at = next(now, minute = 2 * 60 + 30, scheduled = sunday)

        assertThat(at!!.atZone(sydney).offset.totalSeconds).isEqualTo(11 * 3600)
        assertThat(at.atZone(sydney).toLocalTime().toString()).isEqualTo("02:30")
    }

    @Test
    fun theWallClockTimeHoldsAcrossTheDstChange() {
        // Saturday 3 Oct (AEST, +10): Monday 5 Oct 17:30 is AEDT (+11), so the reminder stays at 17:30 on the wall clock.
        val now = inSydney(2026, 10, 3, 18)
        val at = next(now, scheduled = { it.dayOfWeek == DayOfWeek.MONDAY })
        assertThat(at).isEqualTo(inSydney(2026, 10, 5, 17, 30))
        assertThat(at!!.atZone(sydney).offset.totalSeconds).isEqualTo(11 * 3600)
    }

    @Test
    fun aTimeZoneChangeRecomputesFromTheSameInstant() {
        // 08:31 UTC on Monday 9 Nov 2026: 19:31 in Sydney (past 17:30 and its 2 h grace), 16:31 in Perth (before it).
        val now = Instant.parse("2026-11-09T08:31:00Z")
        val perth = ZoneId.of("Australia/Perth")

        assertThat(next(now, sydney)).isEqualTo(inSydney(2026, 11, 10, 17, 30))
        assertThat(next(now, perth)).isEqualTo(ZonedDateTime.of(2026, 11, 9, 17, 30, 0, 0, perth).toInstant())
    }

    @Test
    fun aReArmAfterTheTimeStillGivesTodayWhenItIsLate() {
        // 17:35 re-arm, then a 17:41 re-arm (the alarm was delayed): both still due today, at now.
        val a = inSydney(2026, 11, 9, 17, 35)
        val b = inSydney(2026, 11, 9, 17, 41)
        assertThat(next(a)).isEqualTo(a)
        assertThat(next(b)).isEqualTo(b)
    }
}
