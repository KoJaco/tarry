package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DaySummaryTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private val bounds = dayBounds(date, zone)

    private fun at(hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val hour = 3_600_000L

    @Test
    fun emptyDayIsAllZero() {
        assertThat(summarize(emptyList(), bounds)).isEqualTo(DaySummary(0, 0, 0, 0, 0))
    }

    @Test
    fun totalsOnlyVisitsAndCountsEachKind() {
        val s = summarize(
            listOf(
                TimelineItem.Visit("a", at(9), at(10, 30), "A", needsReview = true),
                TimelineItem.Visit("b", at(11), at(12), "B"),
                TimelineItem.Trip("t", at(10, 30), at(11), 1000f, TripMode.VEHICLE),
                TimelineItem.Gap("g", at(13), at(14), GapCause.NO_FIX),
            ),
            bounds,
        )
        assertThat(s.trackedMs).isEqualTo(hour * 5 / 2)
        assertThat(s.visits).isEqualTo(2)
        assertThat(s.trips).isEqualTo(1)
        assertThat(s.gaps).isEqualTo(1)
        assertThat(s.needsReview).isEqualTo(1)
    }

    @Test
    fun midnightSpanningVisitIsClampedToTheDay() {
        val s = summarize(
            listOf(TimelineItem.Visit("v", bounds.first - 2 * hour, bounds.first + hour, "A")),
            bounds,
        )
        assertThat(s.trackedMs).isEqualTo(hour)
    }

    @Test
    fun itemsOutsideTheDayAreIgnored() {
        val s = summarize(listOf(TimelineItem.Visit("v", bounds.second, bounds.second + hour, "A")), bounds)
        assertThat(s.visits).isEqualTo(0)
        assertThat(s.needsReview).isEqualTo(0)
    }
}
