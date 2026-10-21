package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import org.junit.Test

class DayLayoutTest {
    private val zone: ZoneId = ZoneId.of("Australia/Brisbane") // no DST
    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 3, 10)
    private val bounds = dayBounds(day, zone)
    private val eps = 0.01f

    private fun at(h: Int, m: Int = 0, date: LocalDate = day, z: ZoneId = zone): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(h, m)).atZone(z).toInstant().toEpochMilli()

    private fun visit(id: String, s: Long, e: Long) = TimelineItem.Visit(id, s, e, "P")
    private fun trip(id: String, s: Long, e: Long) = TimelineItem.Trip(id, s, e, 1000f, TripMode.entries.first())
    private fun gap(id: String, s: Long, e: Long) = TimelineItem.Gap(id, s, e, GapCause.NO_FIX)

    private val pastNow = at(0, 0, day.plusDays(5))

    private fun layout(
        items: List<TimelineItem>,
        now: Long = pastNow,
        fontScale: Float = 1f,
        window: Pair<Long, Long>? = null,
        z: ZoneId = zone,
        b: Pair<Long, Long> = bounds,
    ) = layoutDay(items, b, z, now, fontScale, window = window)

    @Test
    fun normalDay() {
        val l = layout(
            listOf(
                visit("v1", at(8, 20), at(10, 0)),
                trip("t1", at(10, 0), at(10, 40)),
                visit("v2", at(11, 0), at(12, 30)),
                gap("g1", at(12, 30), at(13, 15)),
            ),
        )
        assertThat(l.canvasStart).isEqualTo(at(8))
        assertThat(l.canvasEnd).isEqualTo(at(14))
        assertThat(l.scale).isWithin(eps).of(72f)
        assertThat(l.heightDp).isWithin(eps).of(6 * 72f)
        assertThat(l.hourMarks.map { it.epochMs }).containsExactly(
            at(8), at(9), at(10), at(11), at(12), at(13), at(14),
        ).inOrder()
        assertThat(l.hourMarks.map { it.topDp }.first()).isWithin(eps).of(0f)
        assertThat(l.hourMarks.last().topDp).isWithin(eps).of(432f)
        val v1 = l.blocks[0]
        assertThat(v1.topDp).isWithin(eps).of(20 * 72f / 60)
        assertThat(v1.heightDp).isWithin(eps).of(100 * 72f / 60)
        assertThat(v1.compact).isFalse()
        assertThat(l.blocks[1].topDp).isWithin(eps).of(3 * 72f)
        assertThat(l.blocks[2].item).isInstanceOf(TimelineItem.Gap::class.java)
        assertThat(l.blocks[2].heightDp).isWithin(eps).of(45 * 72f / 60)
        assertThat(l.blocks[2].compact).isFalse()
        assertThat(l.trips).hasSize(1)
        assertThat(l.trips[0].topDp).isWithin(eps).of(2 * 72f)
        assertThat(l.trips[0].heightDp).isWithin(eps).of(24f)
        assertThat(l.blocks.all { it.foldedTrips.isEmpty() }).isTrue()
        assertThat(l.nowDp).isNull()
    }

    @Test
    fun emptyDayDefaultsTo7To17() {
        val l = layout(emptyList())
        assertThat(l.canvasStart).isEqualTo(at(7))
        assertThat(l.canvasEnd).isEqualTo(at(17))
        assertThat(l.heightDp).isWithin(eps).of(720f)
        assertThat(l.blocks).isEmpty()
        assertThat(l.hourMarks).hasSize(11)
    }

    @Test
    fun emptyDayWithWindowUsesRoundedWindow() {
        val l = layout(emptyList(), window = at(8, 30) to at(16, 10))
        assertThat(l.canvasStart).isEqualTo(at(8))
        assertThat(l.canvasEnd).isEqualTo(at(17))
    }

    @Test
    fun emptyTodayExtendsToNow() {
        val now = at(19, 30)
        val l = layout(emptyList(), now = now)
        assertThat(l.canvasStart).isEqualTo(at(7))
        assertThat(l.canvasEnd).isEqualTo(at(20))
        assertThat(l.nowDp!!).isWithin(eps).of(12.5f * 72f)
    }

    @Test
    fun windowExtendsCanvasBeyondItems() {
        val l = layout(listOf(visit("v", at(9, 10), at(9, 50))), window = at(8, 0) to at(11, 5))
        assertThat(l.canvasStart).isEqualTo(at(8))
        assertThat(l.canvasEnd).isEqualTo(at(12))
    }

    @Test
    fun allDayStayFillsWholeDay() {
        val l = layout(listOf(visit("v", bounds.first, bounds.second)))
        assertThat(l.canvasStart).isEqualTo(bounds.first)
        assertThat(l.canvasEnd).isEqualTo(bounds.second)
        assertThat(l.blocks).hasSize(1)
        assertThat(l.blocks[0].topDp).isWithin(eps).of(0f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(24 * 72f)
        assertThat(l.heightDp).isWithin(eps).of(24 * 72f)
        assertThat(l.hourMarks).hasSize(25)
    }

    @Test
    fun manyShortVisitsAreSeparatedAndFoldTrips() {
        val items = mutableListOf<TimelineItem>()
        for (i in 0 until 10) {
            val s = at(9, i * 6)
            items += visit("v$i", s, s + 5 * 60_000L)
            if (i < 9) items += trip("t$i", s + 5 * 60_000L, s + 6 * 60_000L)
        }
        val l = layout(items)
        assertThat(l.blocks).hasSize(10)
        l.blocks.forEach {
            assertThat(it.heightDp).isAtLeast(28f - eps)
            assertThat(it.compact).isTrue()
        }
        for (i in 1 until l.blocks.size) {
            assertThat(l.blocks[i].topDp).isAtLeast(l.blocks[i - 1].topDp + l.blocks[i - 1].heightDp - eps)
        }
        val proportional = 72f // 09:00..10:00
        assertThat(l.heightDp).isGreaterThan(proportional)
        assertThat(l.heightDp).isAtLeast(l.blocks.last().topDp + l.blocks.last().heightDp - eps)
        // Every trip is either a row or folded into exactly one visit.
        val folded = l.blocks.flatMap { it.foldedTrips }
        assertThat(l.trips.size + folded.size).isEqualTo(9)
        assertThat(folded).isNotEmpty()
        // Folded into the NEXT visit: trip t_i belongs to block i+1.
        l.blocks.forEachIndexed { i, b -> b.foldedTrips.forEach { assertThat(it.id).isEqualTo("t${i - 1}") } }
        // No trip row overlaps a block.
        l.trips.forEach { t ->
            l.blocks.forEach { b ->
                val overlap = t.topDp < b.topDp + b.heightDp - eps && b.topDp < t.topDp + t.heightDp - eps
                assertThat(overlap).isFalse()
            }
        }
    }

    @Test
    fun tripAfterLastBlockAlwaysFitsAndGrowsCanvas() {
        val l = layout(listOf(visit("v", at(9, 0), at(9, 10)), trip("t", at(9, 10), at(9, 12))))
        assertThat(l.trips).hasSize(1)
        assertThat(l.blocks[0].foldedTrips).isEmpty()
        assertThat(l.trips[0].topDp).isAtLeast(l.blocks[0].topDp + l.blocks[0].heightDp - eps)
    }

    @Test
    fun tripWithNoNextVisitFoldsIntoPrevious() {
        val l = layout(
            listOf(
                visit("v", at(9, 0), at(9, 30)),
                trip("t", at(9, 30), at(9, 31)),
                gap("g", at(9, 31), at(9, 40)),
            ),
        )
        // Gap block starts at 9:31 (true top 37.2) but is pushed below the visit; trip row cannot fit between.
        val v = l.blocks.first { it.item is TimelineItem.Visit }
        assertThat(v.foldedTrips.map { it.id }).containsExactly("t")
        assertThat(l.trips).isEmpty()
    }

    @Test
    fun midnightSpanningItemsAreClamped() {
        val prevStart = at(22, 0, day.minusDays(1))
        val nextEnd = at(2, 0, day.plusDays(1))
        val l = layout(listOf(visit("a", prevStart, at(1, 0)), visit("b", at(23, 0), nextEnd)))
        assertThat(l.canvasStart).isEqualTo(bounds.first)
        assertThat(l.canvasEnd).isEqualTo(bounds.second)
        assertThat(l.blocks[0].topDp).isWithin(eps).of(0f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(72f)
        assertThat(l.blocks[1].topDp).isWithin(eps).of(23 * 72f)
        assertThat(l.blocks[1].heightDp).isWithin(eps).of(72f)
    }

    @Test
    fun itemsEntirelyOutsideDayAreDropped() {
        val l = layout(listOf(visit("x", at(9, 0, day.minusDays(1)), at(10, 0, day.minusDays(1)))))
        assertThat(l.blocks).isEmpty()
        assertThat(l.canvasStart).isEqualTo(at(7))
    }

    @Test
    fun dstShortDaySydney() {
        val date = LocalDate.of(2026, 10, 4)
        val b = dayBounds(date, sydney)
        val l = layout(listOf(visit("v", b.first, b.second)), z = sydney, b = b, now = at(0, 0, date.plusDays(9), sydney))
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(23 * 72f)
        assertThat(l.heightDp).isWithin(eps).of(23 * 72f)
        val tops = l.hourMarks.map { it.topDp }
        assertThat(l.hourMarks).hasSize(24)
        for (i in 1 until tops.size) assertThat(tops[i] - tops[i - 1]).isWithin(eps).of(72f)
        val hours = l.hourMarks.map {
            java.time.Instant.ofEpochMilli(it.epochMs).atZone(sydney).hour
        }
        assertThat(hours).doesNotContain(2)
        assertThat(hours.take(3)).containsExactly(0, 1, 3).inOrder()
    }

    @Test
    fun fontScaleTwoDoublesScaleAndTripRows() {
        val l = layout(
            listOf(
                visit("a", at(9, 0), at(10, 0)),
                trip("t", at(10, 0), at(10, 30)),
                visit("b", at(11, 0), at(12, 0)),
            ),
            fontScale = 2f,
        )
        assertThat(l.scale).isWithin(eps).of(144f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(144f)
        assertThat(l.trips[0].heightDp).isWithin(eps).of(48f)
        assertThat(l.trips[0].topDp).isWithin(eps).of(144f)
        assertThat(l.blocks[1].topDp).isWithin(eps).of(288f)
    }

    @Test
    fun fontScaleBelowOneIsIgnored() {
        assertThat(layout(emptyList(), fontScale = 0.8f).scale).isWithin(eps).of(72f)
    }

    @Test
    fun nowBeforeFirstItemStartsCanvasAtNowHour() {
        val now = at(6, 40)
        val l = layout(listOf(visit("v", at(9, 0), at(10, 0))), now = now)
        assertThat(l.canvasStart).isEqualTo(at(6))
        assertThat(l.nowDp!!).isWithin(eps).of(40 * 72f / 60)
    }

    @Test
    fun nowAfterLastItemEndsCanvasAtCeilNow() {
        val now = at(15, 10)
        val l = layout(listOf(visit("v", at(9, 0), at(10, 0))), now = now)
        assertThat(l.canvasStart).isEqualTo(at(9))
        assertThat(l.canvasEnd).isEqualTo(at(16))
        assertThat(l.nowDp!!).isWithin(eps).of((6 * 60 + 10) * 72f / 60)
    }

    @Test
    fun nowOnExactHourIsNotRoundedUp() {
        val l = layout(listOf(visit("v", at(9, 0), at(10, 0))), now = at(12, 0))
        assertThat(l.canvasEnd).isEqualTo(at(12))
        assertThat(l.nowDp!!).isWithin(eps).of(3 * 72f)
    }

    @Test
    fun nowOnPastDayIsNull() {
        assertThat(layout(listOf(visit("v", at(9, 0), at(10, 0)))).nowDp).isNull()
    }

    @Test
    fun tripsWithoutAnyVisitKeepTheirRowsInOrder() {
        val l = layout(
            listOf(
                trip("t1", at(9, 0), at(9, 1)),
                gap("g", at(9, 1), at(9, 2)),
                trip("t2", at(9, 2), at(9, 3)),
            ),
        )
        assertThat(l.trips).hasSize(2)
        val o = l.ordered()
        assertThat(o.map { it.topDp }).isInStrictOrder()
        assertThat(o.map { (it as? PlacedTrip)?.trip?.id ?: (it as PlacedBlock).item.id })
            .containsExactly("t1", "g", "t2").inOrder()
    }

    @Test
    fun orderedIsTimeOrderAndUnsortedInputIsHandled() {
        val items = listOf(
            visit("v2", at(11, 0), at(12, 0)),
            trip("t1", at(10, 0), at(10, 30)),
            visit("v1", at(9, 0), at(10, 0)),
            gap("g", at(12, 0), at(12, 20)),
        )
        val ids = layout(items).ordered().map { (it as? PlacedTrip)?.trip?.id ?: (it as PlacedBlock).item.id }
        assertThat(ids).containsExactly("v1", "t1", "v2", "g").inOrder()
    }

    @Test
    fun overlappingSourceItemsArePushedDown() {
        val l = layout(listOf(visit("a", at(9, 0), at(10, 0)), visit("b", at(9, 30), at(10, 30))))
        assertThat(l.blocks[1].topDp).isWithin(eps).of(l.blocks[0].topDp + l.blocks[0].heightDp)
    }

    @Test
    fun compactThreshold() {
        val l = layout(listOf(visit("a", at(9, 0), at(9, 29)), visit("b", at(10, 0), at(10, 30))))
        assertThat(l.blocks[0].compact).isTrue()
        assertThat(l.blocks[1].compact).isFalse()
    }
}
