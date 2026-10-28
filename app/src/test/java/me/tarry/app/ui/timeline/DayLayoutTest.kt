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
        assertThat(l.scale).isWithin(eps).of(144f)
        assertThat(l.heightDp).isWithin(eps).of(6 * 144f)
        assertThat(l.hourMarks.map { it.epochMs }).containsExactly(
            at(8), at(9), at(10), at(11), at(12), at(13), at(14),
        ).inOrder()
        assertThat(l.hourMarks.map { it.topDp }.first()).isWithin(eps).of(0f)
        assertThat(l.hourMarks.last().topDp).isWithin(eps).of(864f)
        val v1 = l.blocks[0]
        assertThat(v1.topDp).isWithin(eps).of(20 * 144f / 60)
        assertThat(v1.heightDp).isWithin(eps).of(100 * 144f / 60)
        assertThat(v1.compact).isFalse()
        assertThat(l.blocks[1].topDp).isWithin(eps).of(3 * 144f)
        assertThat(l.blocks[2].item).isInstanceOf(TimelineItem.Gap::class.java)
        assertThat(l.blocks[2].heightDp).isWithin(eps).of(45 * 144f / 60)
        assertThat(l.blocks[2].compact).isFalse()
        assertThat(l.trips).hasSize(1)
        assertThat(l.trips[0].topDp).isWithin(eps).of(2 * 144f)
        assertThat(l.trips[0].heightDp).isWithin(eps).of(40 * 144f / 60)
        assertThat(l.trips[0].iconOnly).isFalse()
        assertThat(l.blocks.all { it.foldedTrips.isEmpty() }).isTrue()
        assertThat(l.nowDp).isNull()
    }

    @Test
    fun emptyDayDefaultsTo7To17() {
        val l = layout(emptyList())
        assertThat(l.canvasStart).isEqualTo(at(7))
        assertThat(l.canvasEnd).isEqualTo(at(17))
        assertThat(l.heightDp).isWithin(eps).of(1440f)
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
        assertThat(l.nowDp!!).isWithin(eps).of(12.5f * 144f)
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
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(24 * 144f)
        assertThat(l.heightDp).isWithin(eps).of(24 * 144f)
        assertThat(l.hourMarks).hasSize(25)
    }

    private fun assertTripsDoNotOverlapBlocks(l: DayLayout) {
        for (t in l.trips) for (b in l.blocks) {
            if (b.cluster.isNotEmpty()) continue
            val overlap = minOf(t.topDp + t.heightDp, b.topDp + b.heightDp) - maxOf(t.topDp, b.topDp)
            assertThat(overlap).isAtMost(eps)
        }
    }

    @Test
    fun manyShortTripsDoNotShiftAnythingOffTheHourGrid() {
        // 10 short visits 6 min apart (5 min visit + 1 min trip), then a visit at exactly 10:00.
        val items = mutableListOf<TimelineItem>()
        for (i in 0 until 10) {
            val s = at(9, i * 6)
            items += visit("v$i", s, s + 5 * 60_000L)
            if (i < 9) items += trip("t$i", s + 5 * 60_000L, s + 6 * 60_000L)
        }
        items += visit("last", at(10, 0), at(11, 0))
        val l = layout(items)
        assertThat(l.blocks).hasSize(11)
        // Short visits are below the minimum height, so they are never trimmed: every top is the true top.
        l.blocks.forEach {
            assertThat(it.topDp).isWithin(eps).of(((it.item.start - l.canvasStart) / 3_600_000.0 * 144).toFloat())
            assertThat(it.compact).isEqualTo(it.item.end - it.item.start < 30 * 60_000L)
        }
        assertThat(l.blocks.last().topDp).isWithin(eps).of(144f)
        assertThat(l.blocks.last().heightDp).isWithin(eps).of(144f)
        assertThat(l.hourMarks.first { it.epochMs == at(10) }.topDp).isWithin(eps).of(l.blocks.last().topDp)
        assertThat(l.trips).hasSize(9)
        assertThat(l.blocks.flatMap { it.foldedTrips }).isEmpty()
        l.trips.forEachIndexed { i, t -> assertThat(t.trip.id).isEqualTo("t$i") }
        assertTripsDoNotOverlapBlocks(l)
    }

    @Test
    fun visitAfterATripSitsOnTheHourWithinTheTrimAllowance_andLaterVisitsAreNotPushed() {
        val l = layout(
            listOf(
                visit("a", at(9, 0), at(9, 40)),
                trip("t", at(9, 40), at(9, 41)),
                visit("b", at(9, 41), at(9, 59)),
                trip("u", at(9, 59), at(10, 0)),
                visit("c", at(10, 0), at(11, 0)),
                visit("d", at(11, 0), at(12, 0)),
            ),
        )
        val c = l.blocks.first { it.item.id == "c" }
        val tenAm = l.hourMarks.first { it.epochMs == at(10) }.topDp
        assertThat(c.topDp).isAtLeast(tenAm - eps)
        assertThat(c.topDp).isAtMost(tenAm + 12f + eps)
        // Not adjacent to a trip: exactly on the grid.
        val d = l.blocks.first { it.item.id == "d" }
        assertThat(d.topDp).isWithin(eps).of(l.hourMarks.first { it.epochMs == at(11) }.topDp)
        assertThat(l.blocks.first { it.item.id == "a" }.topDp).isWithin(eps).of(0f)
        assertTripsDoNotOverlapBlocks(l)
    }

    @Test
    fun oneAndSevenMinuteTripsSitInASlotBetweenTheVisitsTrimmedEvenly() {
        val l = layout(
            listOf(
                visit("a", at(9, 0), at(9, 40)),
                trip("t", at(9, 40), at(9, 47)),
                visit("b", at(9, 47), at(11, 0)),
                trip("u", at(11, 0), at(11, 1)),
                visit("c", at(11, 1), at(12, 0)),
            ),
        )
        val (a, b, c) = l.blocks
        val (t, u) = l.trips
        // 7 min = 16.8 dp, needs 24: 3.6 dp trimmed from each side.
        assertThat(a.topDp).isWithin(eps).of(0f)
        assertThat(a.heightDp).isWithin(eps).of(40 * 144f / 60 - 3.6f)
        assertThat(t.topDp).isWithin(eps).of(40 * 144f / 60 - 3.6f)
        assertThat(t.heightDp).isWithin(eps).of(24f)
        assertThat(t.iconOnly).isFalse()
        assertThat(b.topDp).isWithin(eps).of(47 * 144f / 60 + 3.6f)
        // 1 min = 2.4 dp, needs 24: 10.8 dp from each side (under the 12 dp cap).
        assertThat(u.heightDp).isWithin(eps).of(24f)
        assertThat(u.iconOnly).isFalse()
        assertThat(b.topDp + b.heightDp).isWithin(eps).of(120 * 144f / 60 - 10.8f)
        assertThat(c.topDp).isWithin(eps).of(121 * 144f / 60 + 10.8f)
        // The later visit's bottom is untouched.
        assertThat(c.topDp + c.heightDp).isWithin(eps).of(180 * 144f / 60)
        assertTripsDoNotOverlapBlocks(l)
        assertThat(l.ordered().map { (it as? PlacedTrip)?.trip?.id ?: (it as PlacedBlock).item.id })
            .containsExactly("a", "t", "b", "u", "c").inOrder()
    }

    @Test
    fun tripFallsBackToIconOnlyWhenTheTrimCapIsNotEnough() {
        // Font scale 2: a trip row needs 48 dp, 1 min is 4.8 dp, the neighbours give at most 12 dp each.
        val l = layout(
            listOf(visit("a", at(9, 0), at(9, 40)), trip("t", at(9, 40), at(9, 41)), visit("b", at(9, 41), at(10, 30))),
            fontScale = 2f,
        )
        val t = l.trips.single()
        assertThat(t.iconOnly).isTrue()
        assertThat(t.heightDp).isWithin(eps).of(4.8f + 24f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(40 * 288f / 60 - 12f)
        assertThat(l.blocks[1].topDp).isWithin(eps).of(41 * 288f / 60 + 12f)
        assertTripsDoNotOverlapBlocks(l)
    }

    @Test
    fun trimNeverTakesAVisitBelowItsMinimumHeight() {
        // 15 min = 36 dp, minimum 28 dp: each neighbour can give only 8 dp.
        val l = layout(
            listOf(visit("a", at(9, 0), at(9, 15)), trip("t", at(9, 15), at(9, 16)), visit("b", at(9, 16), at(9, 31))),
        )
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(28f)
        assertThat(l.blocks[1].heightDp).isWithin(eps).of(28f)
        assertThat(l.trips.single().iconOnly).isTrue()
        assertThat(l.trips.single().heightDp).isWithin(eps).of(2.4f + 16f)
        assertTripsDoNotOverlapBlocks(l)
    }

    @Test
    fun longTripKeepsAFullHeightRowOnItsTrueSpan() {
        val l = layout(listOf(visit("a", at(9, 0), at(10, 0)), trip("t", at(10, 0), at(11, 0)), visit("b", at(11, 0), at(12, 0))))
        val t = l.trips.single()
        assertThat(t.iconOnly).isFalse()
        assertThat(t.topDp).isWithin(eps).of(144f)
        assertThat(t.heightDp).isWithin(eps).of(144f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(144f)
    }

    @Test
    fun aTripOfExactlyOneRowIsNotTrimmedAgainstAndTheRowGrowsWithFontScale() {
        // 10 min is 24 dp at 144 dp/h: exactly a row at font scale 1, so nobody is trimmed.
        val items = listOf(visit("a", at(9, 0), at(9, 40)), trip("t", at(9, 40), at(9, 50)), visit("b", at(9, 50), at(10, 30)))
        val one = layout(items, fontScale = 1f)
        assertThat(one.trips.single().heightDp).isWithin(eps).of(24f)
        assertThat(one.blocks[0].heightDp).isWithin(eps).of(40 * 144f / 60)
        val three = layout(items, fontScale = 3f)
        assertThat(three.trips.single().heightDp).isWithin(eps).of(72f)
    }

    @Test
    fun tripAfterTheLastBlockGrowsTheCanvasWithoutMovingTheBlock() {
        val l = layout(listOf(visit("v", at(9, 0), at(9, 10)), trip("t", at(9, 10), at(9, 12))))
        assertThat(l.trips).hasSize(1)
        assertThat(l.blocks[0].topDp).isWithin(eps).of(0f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(24f)
        assertThat(l.trips[0].heightDp).isWithin(eps).of(24f)
        assertThat(l.heightDp).isAtLeast(l.trips[0].topDp + l.trips[0].heightDp - eps)
        assertTripsDoNotOverlapBlocks(l)
    }

    @Test
    fun tripBeforeAGapTrimsTheVisitAndLeavesTheGapOnItsTrueSpan() {
        val l = layout(
            listOf(
                visit("v", at(9, 0), at(9, 30)),
                trip("t", at(9, 30), at(9, 31)),
                gap("g", at(9, 31), at(9, 40)),
            ),
        )
        val v = l.blocks.first { it.item is TimelineItem.Visit }
        val g = l.blocks.first { it.item is TimelineItem.Gap }
        assertThat(v.heightDp).isWithin(eps).of(72f - 12f)
        assertThat(g.topDp).isWithin(eps).of(31 * 144f / 60)
        assertThat(l.trips.single().iconOnly).isTrue()
        assertThat(l.ordered().map { (it as? PlacedTrip)?.trip?.id ?: (it as PlacedBlock).item.id })
            .containsExactly("v", "t", "g").inOrder()
        assertTripsDoNotOverlapBlocks(l)
    }

    @Test
    fun quarterHourTicksSitBetweenTheHourMarks() {
        val l = layout(listOf(visit("a", at(8, 20), at(10, 0))))
        assertThat(l.canvasStart).isEqualTo(at(8))
        assertThat(l.canvasEnd).isEqualTo(at(10))
        assertThat(l.quarterMarks).hasSize(6)
        listOf(36f, 72f, 108f, 180f, 216f, 252f).forEachIndexed { i, y ->
            assertThat(l.quarterMarks[i]).isWithin(eps).of(y)
        }
    }

    @Test
    fun midnightSpanningItemsAreClamped() {
        val prevStart = at(22, 0, day.minusDays(1))
        val nextEnd = at(2, 0, day.plusDays(1))
        val l = layout(listOf(visit("a", prevStart, at(1, 0)), visit("b", at(23, 0), nextEnd)))
        assertThat(l.canvasStart).isEqualTo(bounds.first)
        assertThat(l.canvasEnd).isEqualTo(bounds.second)
        assertThat(l.blocks[0].topDp).isWithin(eps).of(0f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(144f)
        assertThat(l.blocks[1].topDp).isWithin(eps).of(23 * 144f)
        assertThat(l.blocks[1].heightDp).isWithin(eps).of(144f)
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
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(23 * 144f)
        assertThat(l.heightDp).isWithin(eps).of(23 * 144f)
        val tops = l.hourMarks.map { it.topDp }
        assertThat(l.hourMarks).hasSize(24)
        for (i in 1 until tops.size) assertThat(tops[i] - tops[i - 1]).isWithin(eps).of(144f)
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
        assertThat(l.scale).isWithin(eps).of(288f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(288f)
        assertThat(l.trips[0].heightDp).isWithin(eps).of(144f)
        assertThat(l.trips[0].topDp).isWithin(eps).of(288f)
        assertThat(l.blocks[1].topDp).isWithin(eps).of(576f)
    }

    @Test
    fun fontScaleTwoDoublesTheMinimumBlockHeight() {
        val items = listOf(visit("a", at(9, 0), at(9, 5)))
        assertThat(layout(items).blocks[0].heightDp).isWithin(eps).of(28f)
        assertThat(layout(items, fontScale = 2f).blocks[0].heightDp).isWithin(eps).of(56f)
        assertThat(layout(items, fontScale = 0.8f).blocks[0].heightDp).isWithin(eps).of(28f)
    }

    @Test
    fun dstFallBackDaySydney() {
        val date = LocalDate.of(2026, 4, 5)
        val b = dayBounds(date, sydney)
        val l = layout(listOf(visit("v", b.first, b.second)), z = sydney, b = b, now = at(0, 0, date.plusDays(9), sydney))
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(25 * 144f)
        assertThat(l.heightDp).isWithin(eps).of(25 * 144f)
        assertThat(l.hourMarks).hasSize(26)
        val tops = l.hourMarks.map { it.topDp }
        assertThat(tops).isInStrictOrder()
        for (i in 1 until tops.size) assertThat(tops[i] - tops[i - 1]).isWithin(eps).of(144f)
        val hours = l.hourMarks.map { java.time.Instant.ofEpochMilli(it.epochMs).atZone(sydney).hour }
        assertThat(hours.count { it == 2 }).isEqualTo(2)
    }

    @Test
    fun fontScaleBelowOneIsIgnored() {
        assertThat(layout(emptyList(), fontScale = 0.8f).scale).isWithin(eps).of(144f)
    }

    @Test
    fun nowBeforeFirstItemStartsCanvasAtNowHour() {
        val now = at(6, 40)
        val l = layout(listOf(visit("v", at(9, 0), at(10, 0))), now = now)
        assertThat(l.canvasStart).isEqualTo(at(6))
        assertThat(l.nowDp!!).isWithin(eps).of(40 * 144f / 60)
    }

    @Test
    fun nowAfterLastItemEndsCanvasAtCeilNow() {
        val now = at(15, 10)
        val l = layout(listOf(visit("v", at(9, 0), at(10, 0))), now = now)
        assertThat(l.canvasStart).isEqualTo(at(9))
        assertThat(l.canvasEnd).isEqualTo(at(16))
        assertThat(l.nowDp!!).isWithin(eps).of((6 * 60 + 10) * 144f / 60)
    }

    @Test
    fun nowOnExactHourIsNotRoundedUp() {
        val l = layout(listOf(visit("v", at(9, 0), at(10, 0))), now = at(12, 0))
        assertThat(l.canvasEnd).isEqualTo(at(12))
        assertThat(l.nowDp!!).isWithin(eps).of(3 * 144f)
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
    fun overlappingSourceItemsAreNotPushed() {
        val l = layout(listOf(visit("a", at(9, 0), at(10, 0)), visit("b", at(9, 30), at(10, 30))))
        assertThat(l.blocks[1].topDp).isWithin(eps).of(72f)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(144f)
    }

    @Test
    fun compactThreshold() {
        val l = layout(listOf(visit("a", at(9, 0), at(9, 29)), visit("b", at(10, 0), at(10, 30))))
        assertThat(l.blocks[0].compact).isTrue()
        assertThat(l.blocks[1].compact).isFalse()
    }

    @Test
    fun adjacentBlocksSitExactlyOnTheirTimes() {
        val l = layout(listOf(visit("a", at(9, 0), at(10, 0)), visit("b", at(10, 0), at(11, 0))))
        val (a, b) = l.blocks
        assertThat(a.topDp).isWithin(eps).of(0f)
        assertThat(a.heightDp).isWithin(eps).of(144f)
        assertThat(b.topDp).isWithin(eps).of(144f)
    }

    private fun atS(h: Int, m: Int, s: Int, date: LocalDate, z: ZoneId): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(h, m, s)).atZone(z).toInstant().toEpochMilli()

    @Test
    fun deviceCase_gapInsideVisitIsInner_andNowLineIsOnTheTimeScale() {
        val date = LocalDate.of(2026, 10, 21)
        val b = dayBounds(date, sydney)
        val v = visit("v", atS(19, 55, 45, date, sydney), atS(20, 12, 45, date, sydney))
        val g = gap("g", atS(20, 7, 45, date, sydney), atS(20, 8, 35, date, sydney))
        val now = atS(20, 14, 21, date, sydney)
        val prev = visit("p", atS(18, 0, 0, date, sydney), atS(19, 30, 0, date, sydney))
        val l = layout(listOf(prev, v, g), now = now, z = sydney, b = b)
        assertThat(l.blocks.map { it.item.id }).containsExactly("p", "v").inOrder()
        val pv = l.blocks.first { it.item.id == "v" }
        assertThat(pv.heightDp).isWithin(eps).of(maxOf(17f * 144f / 60, 28f))
        assertThat(pv.innerGaps).hasSize(1)
        assertThat(pv.innerGaps[0].gap.id).isEqualTo("g")
        assertThat(pv.innerGaps[0].offsetDp).isWithin(0.1f).of(12f / 17f * pv.heightDp)
        // Straight from the clock: 18:00 -> 20:14:21 on the 72 dp/h scale.
        assertThat(l.nowDp!!).isWithin(0.01f).of((2 * 60 + 14 + 21 / 60f) * 144f / 60)
    }

    @Test
    fun longVisitWithFourInnerGapsKeepsItsProportionalHeight() {
        val v = visit("v", at(14, 17), at(16, 43))
        val gaps = listOf(at(14, 30) to at(14, 31), at(15, 0) to at(15, 2), at(15, 20) to at(15, 21), at(16, 10) to at(16, 12))
            .mapIndexed { i, (s, e) -> gap("g$i", s, e) }
        val l = layout(listOf(v) + gaps)
        assertThat(l.blocks).hasSize(1)
        assertThat(l.blocks[0].heightDp).isWithin(eps).of(146 * 144f / 60)
        val offs = l.blocks[0].innerGaps.map { it.offsetDp }
        assertThat(offs).hasSize(4)
        assertThat(offs).isInStrictOrder()
        l.blocks[0].innerGaps.forEach {
            assertThat(it.heightDp).isAtLeast(3f - eps)
            assertThat(it.offsetDp + it.heightDp).isAtMost(l.blocks[0].heightDp + eps)
        }
    }

    @Test
    fun gapOverlappingNoVisitStaysABlock() {
        val l = layout(listOf(visit("v", at(9, 0), at(10, 0)), gap("g", at(10, 30), at(11, 0))))
        assertThat(l.blocks.map { it.item.id }).containsExactly("v", "g").inOrder()
        assertThat(l.blocks.all { it.innerGaps.isEmpty() }).isTrue()
    }

    @Test
    fun partialOverlapIsInnerOnlyWhenAtLeastHalfInsideTheVisit() {
        // 10 min gap, 6 min inside (60 %) -> inner; 4 min inside (40 %) -> block.
        val inner = layout(listOf(visit("v", at(9, 0), at(10, 0)), gap("g", at(9, 54), at(10, 4))))
        assertThat(inner.blocks).hasSize(1)
        assertThat(inner.blocks[0].innerGaps).hasSize(1)
        val outer = layout(listOf(visit("v", at(9, 0), at(10, 0)), gap("g", at(9, 56), at(10, 6))))
        assertThat(outer.blocks.map { it.item.id }).containsExactly("v", "g").inOrder()
        assertThat(outer.blocks[0].innerGaps).isEmpty()
    }

    @Test
    fun nowLineSitsInsideAnOngoingVisit() {
        val n = layout(listOf(visit("v", at(9, 0), at(10, 0))), now = at(9, 30))
        assertThat(n.nowDp!!).isWithin(eps).of(72f)
        assertThat(n.nowDp!!).isAtLeast(n.blocks[0].topDp)
        assertThat(n.nowDp!!).isAtMost(n.blocks[0].topDp + n.blocks[0].heightDp)
    }

    @Test
    fun minimumHeightNeverPushesOrOverlapsTheNextBlock() {
        // 10 min = 24 dp true height; the minimum (28) would reach into b, so it stops at b's true start.
        val l = layout(listOf(visit("a", at(9, 0), at(9, 10)), visit("b", at(9, 10), at(10, 0))))
        val (a, b) = l.blocks
        assertThat(a.heightDp).isWithin(eps).of(24f)
        assertThat(b.topDp).isWithin(eps).of(24f)
    }

    @Test
    fun everyBlockIsOnItsTimeAtAllFontScales() {
        val items = listOf(
            visit("a", at(9, 0), at(10, 0)),
            trip("t", at(10, 0), at(10, 20)),
            gap("g", at(10, 20), at(10, 30)),
            visit("b", at(10, 30), at(11, 0)),
        )
        for (fs in listOf(1f, 2f)) {
            val l = layout(items, fontScale = fs)
            l.blocks.forEach {
                assertThat(it.topDp).isWithin(eps).of(((it.item.start - l.canvasStart) / 3_600_000.0 * 144 * fs).toFloat())
                assertThat(it.heightDp).isAtLeast(((it.item.end - it.item.start) / 3_600_000.0 * 144 * fs).toFloat() - eps)
            }
        }
    }

    // --- Outage clusters ---

    private fun clusters(l: DayLayout) = l.blocks.filter { it.cluster.isNotEmpty() }

    @Test
    fun clusters_noneWhenGapsAreSeparatedByALongVisitOrThereIsOnlyOneGap() {
        val single = layout(listOf(visit("v0", at(8), at(9)), gap("g1", at(9, 5), at(9, 30)), trip("t", at(9, 30), at(9, 40))))
        assertThat(clusters(single)).isEmpty()
        assertThat(single.blocks.filter { it.item is TimelineItem.Gap }).hasSize(1)

        val split = layout(
            listOf(
                gap("g1", at(9), at(9, 20)),
                visit("long", at(9, 30), at(9, 45)),
                gap("g2", at(9, 50), at(10, 10)),
            ),
        )
        assertThat(clusters(split)).isEmpty()
        assertThat(split.blocks.filter { it.item is TimelineItem.Gap }.map { it.item.id }).containsExactly("g1", "g2").inOrder()
    }

    @Test
    fun clusters_aVisitOfExactlyTheThresholdSeparates_shorterJoins() {
        fun with(visitMinutes: Int) = layout(
            listOf(
                gap("g1", at(9), at(9, 20)),
                visit("v", at(9, 30), at(9, 30 + visitMinutes)),
                gap("g2", at(9, 59), at(10, 20)),
            ),
        )
        assertThat(clusters(with(10))).isEmpty()
        assertThat(clusters(with(9))).hasSize(1)
    }

    @Test
    fun clusters_twoGapsSeparatedByATripBecomeOneBlock_andTheTripIsDropped() {
        val l = layout(
            listOf(
                visit("v0", at(8), at(9)),
                gap("g1", at(10, 5), at(10, 30)),
                trip("t", at(10, 30), at(10, 50)),
                gap("g2", at(10, 50), at(11, 40)),
            ),
        )
        val c = clusters(l).single()
        assertThat(c.cluster.map { it.id }).containsExactly("g1", "g2").inOrder()
        assertThat(c.item.id).isEqualTo("cluster:g1")
        assertThat(c.item.start).isEqualTo(at(10, 5))
        assertThat(c.item.end).isEqualTo(at(11, 40))
        assertThat(l.trips).isEmpty()
        assertThat(l.blocks.filter { it.item is TimelineItem.Gap }).hasSize(1)
        // Stacked like a normal block: proportional position and height.
        assertThat(c.topDp).isWithin(eps).of(2 * 144f + 5 * 144f / 60)
        assertThat(c.heightDp).isWithin(eps).of(95 * 144f / 60)
    }

    @Test
    fun clusters_shortVisitsInsideStillDrawOnTopOfTheBand() {
        val l = layout(
            listOf(
                gap("g1", at(10, 5), at(10, 30)),
                visit("quick", at(10, 32), at(10, 38)),
                trip("t", at(10, 38), at(10, 45)),
                gap("g2", at(10, 45), at(10, 55)),
                visit("quick2", at(10, 56), at(11, 2)),
                gap("g3", at(11, 3), at(11, 40)),
            ),
        )
        val c = clusters(l).single()
        assertThat(c.cluster.map { it.id }).containsExactly("g1", "g2", "g3").inOrder()
        assertThat(l.blocks.first()).isSameInstanceAs(c) // drawn first, so the visits overlay it
        val visits = l.blocks.filter { it.item is TimelineItem.Visit }
        assertThat(visits.map { it.item.id }).containsExactly("quick", "quick2").inOrder()
        visits.forEach {
            assertThat(it.topDp).isAtLeast(c.topDp - eps)
            assertThat(it.topDp + it.heightDp).isAtMost(c.topDp + c.heightDp + eps)
        }
        assertThat(l.trips).isEmpty()
        assertThat(l.ordered().first()).isSameInstanceAs(c)
    }

    @Test
    fun clusters_formByElapsedTimeAcrossTheSpringForwardGap() {
        // Sydney 2026-10-04: 02:00 -> 03:00. 01:58 to 03:02 on the wall is only 4 minutes of elapsed time.
        val date = LocalDate.of(2026, 10, 4)
        val b = dayBounds(date, sydney)
        fun s(h: Int, m: Int) = at(h, m, date, sydney)
        val l = layout(
            listOf(
                gap("g1", s(1, 30), s(1, 50)),
                visit("v", s(1, 58), s(3, 2)),
                gap("g2", s(3, 5), s(3, 30)),
            ),
            z = sydney, b = b, now = at(0, 0, date.plusDays(9), sydney),
        )
        assertThat(visitMs(s(1, 58), s(3, 2))).isEqualTo(4 * 60_000L)
        assertThat(clusters(l).single().cluster.map { it.id }).containsExactly("g1", "g2").inOrder()
    }

    private fun visitMs(s: Long, e: Long) = e - s

    private fun job(id: String, s: Long, e: Long) = JobBlockItem(id, "j", "Job $id", 0, s, e, true, null)
    private fun suggestion(visitId: String, s: Long, e: Long) = JobSuggestionItem(JobSuggestion(visitId, "j", s, e), "Job", 0)

    @Test
    fun jobBlockSitsAtItsTrueTimeInOneLane() {
        val l = layoutDay(
            listOf(visit("v", at(8), at(10))), bounds, zone, pastNow, 1f,
            jobBlocks = listOf(job("a", at(8, 30), at(9, 30))),
        )
        val p = l.jobs.single()
        assertThat(p.topDp).isWithin(eps).of(72f)
        assertThat(p.heightDp).isWithin(eps).of(144f)
        assertThat(p.lane).isEqualTo(0)
        assertThat(p.laneCount).isEqualTo(1)
    }

    @Test
    fun overlappingBlocksShareLanesAndASuggestionTakesAFreeLane() {
        val l = layoutDay(
            listOf(visit("v", at(8), at(12))), bounds, zone, pastNow, 1f,
            jobBlocks = listOf(job("a", at(8), at(10)), job("b", at(9), at(11))),
            jobSuggestions = listOf(suggestion("v", at(10), at(12))),
        )
        assertThat(l.jobs.map { it.item.id to it.lane }).containsExactly("a" to 0, "b" to 1).inOrder()
        assertThat(l.jobs.map { it.laneCount }).containsExactly(2, 2)
        val s = l.suggestions.single()
        assertThat(s.lane).isEqualTo(0)
        assertThat(s.laneCount).isEqualTo(2)
    }

    @Test
    fun jobBlockOutsideTheVisitsExtendsTheCanvas() {
        val l = layoutDay(
            listOf(visit("v", at(9), at(10))), bounds, zone, pastNow, 1f,
            jobBlocks = listOf(job("a", at(6, 30), at(7, 30)), job("b", at(15), at(16, 10))),
        )
        assertThat(l.canvasStart).isEqualTo(at(6))
        assertThat(l.canvasEnd).isEqualTo(at(17))
        assertThat(l.jobs.first().topDp).isWithin(eps).of(72f)
    }

    @Test
    fun aShortJobBlockIsDrawnAtLeastTheMinimumHeight() {
        val l = layoutDay(
            listOf(visit("v", at(8), at(10))), bounds, zone, pastNow, 1f,
            jobBlocks = listOf(job("a", at(9), at(9, 5))),
        )
        assertThat(l.jobs.single().heightDp).isWithin(eps).of(28f)
    }
}
