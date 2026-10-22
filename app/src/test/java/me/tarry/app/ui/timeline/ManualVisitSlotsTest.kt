package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ManualVisitSlotsTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private fun at(h: Int, m: Int = 0, d: LocalDate = date) = d.atTime(h, m).atZone(sydney).toInstant().toEpochMilli()
    private fun visit(s: Long, e: Long) = TimelineItem.Visit("v$s", s, e, "P")

    @Test
    fun gapWithNoVisitsIsFreeAsIs() {
        assertThat(freeSlotForGap(at(10), at(11), emptyList())).isEqualTo(at(10) to at(11))
    }

    @Test
    fun gapIsClampedToTheVisitsEitherSide() {
        val slot = freeSlotForGap(at(10), at(12), listOf(visit(at(9), at(10, 20)), visit(at(11, 40), at(13))))
        assertThat(slot).isEqualTo(at(10, 20) to at(11, 40))
    }

    @Test
    fun clusterSkipsInnerVisitsAndTakesTheLongestFreePiece() {
        val slot = freeSlotForGap(at(10), at(12), listOf(visit(at(10, 10), at(10, 20)), visit(at(11, 50), at(11, 55))))
        assertThat(slot).isEqualTo(at(10, 20) to at(11, 50))
    }

    @Test
    fun nothingFreeIsNull() {
        assertThat(freeSlotForGap(at(10), at(11), listOf(visit(at(9), at(12))))).isNull()
    }

    @Test
    fun emptyDayDefaultsTo9To10() {
        assertThat(firstFreeSlot(date, sydney, emptyList())).isEqualTo(at(9) to at(10))
    }

    @Test
    fun otherwiseTheFirstWholeHourAtOrAfterTheLastVisitEnd() {
        assertThat(firstFreeSlot(date, sydney, listOf(visit(at(8), at(10, 15))))).isEqualTo(at(11) to at(12))
        assertThat(firstFreeSlot(date, sydney, listOf(visit(at(8), at(10))))).isEqualTo(at(10) to at(11))
    }

    @Test
    fun lateLastVisitKeepsTheSlotInsideTheDay() {
        val slot = firstFreeSlot(date, sydney, listOf(visit(at(20), at(23, 30))))
        assertThat(slot).isEqualTo(at(23) to dayBounds(date, sydney).second)
    }

    @Test
    fun springForwardDayUsesLocalHours() {
        val d = LocalDate.of(2026, 10, 4) // 02:00 -> 03:00
        assertThat(firstFreeSlot(d, sydney, listOf(visit(at(0, 30, d), at(1, 30, d))))).isEqualTo(at(3, 0, d) to at(4, 0, d))
    }
}
