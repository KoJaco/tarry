package me.tarry.app.summary

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.settings.RoundingMode
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SummaryCalculatorTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val min = 60_000L
    private val hour = 60 * min

    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = sydney): Long =
        ZonedDateTime.of(y, m, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    /** A day in mid-November 2025 (no DST change). */
    private fun nov(d: Int, h: Int, mi: Int = 0) = at(2025, 11, d, h, mi)

    private val jobA = Job(id = "A", name = "Alpha", colorArgb = 1, updatedAt = 0)
    private val jobB = Job(id = "B", name = "Bravo", colorArgb = 2, updatedAt = 0)

    private fun block(
        id: String, job: String, start: Long, end: Long, billable: Boolean = true, deleted: Boolean = false, notes: String? = null,
    ) = JobBlock(id = id, jobId = job, start = start, end = end, zoneId = "Australia/Sydney", billable = billable, notes = notes, updatedAt = 0, deleted = deleted)

    private fun visit(id: String, start: Long, end: Long, place: String? = "p1", deleted: Boolean = false) = Visit(
        id = id, placeId = place, start = start, end = end, zoneId = "Australia/Sydney", origin = VisitOrigin.DETECTED,
        confidence = 1f, updatedAt = 0, deleted = deleted,
    )

    private fun trip(id: String, start: Long, end: Long) = Trip(
        id = id, start = start, end = end, zoneId = "Australia/Sydney", distanceM = 1000f, mode = TripMode.VEHICLE, updatedAt = 0,
    )

    private fun calc(
        blocks: List<JobBlock>,
        rules: BillingRules = BillingRules(),
        from: LocalDate = LocalDate.of(2025, 11, 10),
        to: LocalDate = from,
        jobs: List<Job> = listOf(jobA, jobB),
        visits: List<Visit> = emptyList(),
        trips: List<Trip> = emptyList(),
        ignored: Set<String> = emptySet(),
        zone: ZoneId = sydney,
    ) = calculateSummary(SummaryInput(SummaryRange(from, to), zone, blocks, jobs, visits, trips, ignored, rules))

    private fun billedFor(rawMin: Long, rules: BillingRules): Long {
        val r = calc(listOf(block("b", "A", nov(10, 9), nov(10, 9) + rawMin * min)), rules)
        return r.billedMs / min
    }

    // ---- rounding ----------------------------------------------------------------------------------------------

    @Test
    fun noRounding_billsTheRawTime() {
        assertThat(billedFor(97, BillingRules())).isEqualTo(97)
    }

    @Test
    fun nearest_roundsHalfUp_at6_15_30() {
        fun n(inc: Int, raw: Long) = billedFor(raw, BillingRules(incrementMin = inc, mode = RoundingMode.NEAREST))
        assertThat(n(6, 2)).isEqualTo(0) // 2 min: below half a step
        assertThat(n(6, 3)).isEqualTo(6) // exactly half a step rounds up
        assertThat(n(6, 8)).isEqualTo(6)
        assertThat(n(6, 9)).isEqualTo(12)
        assertThat(n(15, 7)).isEqualTo(0)
        assertThat(n(15, 8)).isEqualTo(15) // 7.5 min would be 15; 8 is past it
        assertThat(n(15, 22)).isEqualTo(15)
        assertThat(n(15, 23)).isEqualTo(30)
        assertThat(n(30, 14)).isEqualTo(0)
        assertThat(n(30, 15)).isEqualTo(30)
        assertThat(n(30, 44)).isEqualTo(30)
        assertThat(n(30, 45)).isEqualTo(60)
    }

    @Test
    fun nearest_sevenAndAHalfMinutesAtFifteen_becomesFifteen() {
        val rules = BillingRules(incrementMin = 15)
        val r = calc(listOf(block("b", "A", nov(10, 9), nov(10, 9) + 7 * min + 30_000)), rules)
        assertThat(r.billedMs).isEqualTo(15 * min)
    }

    @Test
    fun up_roundsAnyPartOfAStep_at6_15_30() {
        fun u(inc: Int, raw: Long) = billedFor(raw, BillingRules(incrementMin = inc, mode = RoundingMode.UP))
        assertThat(u(6, 1)).isEqualTo(6)
        assertThat(u(6, 6)).isEqualTo(6)
        assertThat(u(6, 7)).isEqualTo(12)
        assertThat(u(15, 1)).isEqualTo(15)
        assertThat(u(15, 15)).isEqualTo(15)
        assertThat(u(15, 16)).isEqualTo(30)
        assertThat(u(30, 31)).isEqualTo(60)
        assertThat(u(30, 60)).isEqualTo(60)
    }

    @Test
    fun up_withIncrementOff_isRaw() {
        assertThat(billedFor(7, BillingRules(incrementMin = 0, mode = RoundingMode.UP))).isEqualTo(7)
    }

    // ---- minimum -----------------------------------------------------------------------------------------------

    @Test
    fun minimum_withoutRounding_topsUpShortBlocks() {
        val rules = BillingRules(minimumMin = 30)
        assertThat(billedFor(10, rules)).isEqualTo(30)
        assertThat(billedFor(45, rules)).isEqualTo(45)
    }

    @Test
    fun minimum_afterRounding() {
        val rules = BillingRules(incrementMin = 15, mode = RoundingMode.UP, minimumMin = 30)
        assertThat(billedFor(5, rules)).isEqualTo(30) // 15 after rounding, then 30
        assertThat(billedFor(40, rules)).isEqualTo(45)
    }

    @Test
    fun minimum_appliesWhenNearestRoundedToZero() {
        val rules = BillingRules(incrementMin = 15, mode = RoundingMode.NEAREST, minimumMin = 15)
        assertThat(billedFor(5, rules)).isEqualTo(15)
    }

    // ---- per-block against per-day -----------------------------------------------------------------------------

    private val twoShortBlocks = listOf(
        block("b1", "A", nov(10, 9), nov(10, 9, 10)),
        block("b2", "A", nov(10, 11), nov(10, 11, 10)),
    )
    private val roundTo15Min30 = BillingRules(incrementMin = 15, mode = RoundingMode.NEAREST, minimumMin = 30)

    @Test
    fun perBlock_roundsAndTopsUpEachBlock() {
        val r = calc(twoShortBlocks, roundTo15Min30.copy(perDay = false))
        // Each 10 min block: nearest 15, then minimum 30.
        assertThat(r.billedMs).isEqualTo(60 * min)
        assertThat(r.byJob.single().pieces.map { it.billedMs }).containsExactly(30 * min, 30 * min)
    }

    @Test
    fun perDay_addsTheJobsBlocksUpFirst() {
        val r = calc(twoShortBlocks, roundTo15Min30.copy(perDay = true))
        // 20 min total: nearest 15, then minimum 30.
        assertThat(r.billedMs).isEqualTo(30 * min)
        val row = r.byJob.single()
        assertThat(row.billableMs).isEqualTo(20 * min)
        assertThat(row.pieces.map { it.billedMs }).containsExactly(null, null)
        assertThat(row.days).containsExactly(DayBilled(LocalDate.of(2025, 11, 10), 20 * min, 30 * min))
    }

    @Test
    fun perDay_isPerJobAndPerLocalDay() {
        val blocks = listOf(
            block("a1", "A", nov(10, 9), nov(10, 9, 20)),
            block("a2", "A", nov(11, 9), nov(11, 9, 20)),
            block("b1", "B", nov(10, 9), nov(10, 9, 20)),
        )
        val r = calc(blocks, BillingRules(incrementMin = 30, mode = RoundingMode.UP, perDay = true), to = LocalDate.of(2025, 11, 11))
        // Three units of 20 min, each up to 30.
        assertThat(r.billedMs).isEqualTo(90 * min)
    }

    // ---- non-billable ------------------------------------------------------------------------------------------

    @Test
    fun nonBillable_countsTowardTotalButNotBilled() {
        val blocks = listOf(
            block("b1", "A", nov(10, 9), nov(10, 10)),
            block("b2", "A", nov(10, 10), nov(10, 11), billable = false),
        )
        val r = calc(blocks, BillingRules(minimumMin = 30))
        val row = r.byJob.single()
        assertThat(row.totalMs).isEqualTo(2 * hour)
        assertThat(row.billableMs).isEqualTo(hour)
        assertThat(row.billedMs).isEqualTo(hour)
        assertThat(row.blockCount).isEqualTo(2)
        assertThat(r.onClockMs).isEqualTo(2 * hour)
        assertThat(row.pieces.first { it.blockId == "b2" }.billedMs).isEqualTo(0L)
    }

    @Test
    fun onlyNonBillable_getsNoMinimum() {
        val r = calc(listOf(block("b", "A", nov(10, 9), nov(10, 9, 5), billable = false)), BillingRules(minimumMin = 30))
        assertThat(r.billedMs).isEqualTo(0)
        assertThat(r.byJob.single().totalMs).isEqualTo(5 * min)
    }

    // ---- clipping ----------------------------------------------------------------------------------------------

    @Test
    fun clipsAtRangeEdges() {
        // 22:00 on the 9th to 02:00 on the 10th: only the 10th's two hours are in a one-day range.
        val r = calc(listOf(block("b", "A", nov(9, 22), nov(10, 2))), from = LocalDate.of(2025, 11, 10))
        val row = r.byJob.single()
        assertThat(row.totalMs).isEqualTo(2 * hour)
        assertThat(row.pieces.single().start).isEqualTo(nov(10, 0))
        assertThat(r.onClockMs).isEqualTo(2 * hour)
    }

    @Test
    fun clipsAtTheEndOfTheLastDay() {
        val r = calc(listOf(block("b", "A", nov(10, 23), nov(11, 3))), from = LocalDate.of(2025, 11, 10))
        assertThat(r.byJob.single().totalMs).isEqualTo(hour)
    }

    @Test
    fun blockOutsideTheRange_isIgnored() {
        val r = calc(listOf(block("b", "A", nov(12, 9), nov(12, 10))), from = LocalDate.of(2025, 11, 10))
        assertThat(r.byJob).isEmpty()
        assertThat(r.hasBlocks).isFalse()
    }

    @Test
    fun blockCrossingMidnight_isTwoDayPieces_inBothModes() {
        val blocks = listOf(block("b", "A", nov(10, 22), nov(11, 3)))
        for (perDay in listOf(false, true)) {
            val r = calc(blocks, BillingRules(incrementMin = 15, mode = RoundingMode.UP, perDay = perDay), to = LocalDate.of(2025, 11, 11))
            assertThat(r.byJob.single().blockCount).isEqualTo(1)
            assertThat(r.byJob.single().pieces.map { it.date }).containsExactly(LocalDate.of(2025, 11, 10), LocalDate.of(2025, 11, 11))
            assertThat(r.byDay.map { it.date }).containsExactly(LocalDate.of(2025, 11, 10), LocalDate.of(2025, 11, 11)).inOrder()
            assertThat(r.byDay.map { it.billedMs }).containsExactly(2 * hour, 3 * hour).inOrder()
            assertThat(r.billedMs).isEqualTo(5 * hour)
        }
    }

    @Test
    fun midnightSplit_minimumAppliesToEachPiece() {
        // 23:50 to 00:10 is two 10 minute pieces; a 30 minute minimum bills each.
        val r = calc(listOf(block("b", "A", nov(10, 23, 50), nov(11, 0, 10))), BillingRules(minimumMin = 30), to = LocalDate.of(2025, 11, 11))
        assertThat(r.billedMs).isEqualTo(60 * min)
        assertThat(r.byJob.single().blockCount).isEqualTo(1)
        assertThat(r.onClockMs).isEqualTo(20 * min)
    }

    // ---- DST ---------------------------------------------------------------------------------------------------

    @Test
    fun sydneyDstStart_dayIs23Hours() {
        // Sunday 4 October 2026: 02:00 jumps to 03:00.
        val day = LocalDate.of(2026, 10, 4)
        val r = calc(listOf(block("b", "A", at(2026, 10, 4, 0), at(2026, 10, 5, 0))), from = day)
        assertThat(r.byJob.single().totalMs).isEqualTo(23 * hour)
        assertThat(r.byDay.single().date).isEqualTo(day)
    }

    @Test
    fun sydneyDstStart_overnightBlockSplitsAtLocalMidnight() {
        // 22:00 on the 3rd to 04:00 on the 4th is 5 real hours: 2 before midnight, 3 after (02:00 does not exist).
        val r = calc(
            listOf(block("b", "A", at(2026, 10, 3, 22), at(2026, 10, 4, 4))),
            from = LocalDate.of(2026, 10, 3), to = LocalDate.of(2026, 10, 4),
        )
        assertThat(r.byDay.map { it.billedMs }).containsExactly(2 * hour, 3 * hour).inOrder()
        assertThat(r.onClockMs).isEqualTo(5 * hour)
    }

    @Test
    fun sydneyDstEnd_dayIs25Hours() {
        // Sunday 5 April 2026: 03:00 falls back to 02:00.
        val day = LocalDate.of(2026, 4, 5)
        val r = calc(listOf(block("b", "A", at(2026, 4, 5, 0), at(2026, 4, 6, 0))), from = day)
        assertThat(r.byJob.single().totalMs).isEqualTo(25 * hour)
        assertThat(r.byJob.single().blockCount).isEqualTo(1)
    }

    @Test
    fun sydneyDstEnd_rangeClipsToTheLongDay() {
        // A block from the 4th evening through the 6th clipped to just the 5th is the whole 25 hour day.
        val r = calc(listOf(block("b", "A", at(2026, 4, 4, 20), at(2026, 4, 6, 8))), from = LocalDate.of(2026, 4, 5))
        assertThat(r.byJob.single().totalMs).isEqualTo(25 * hour)
    }

    // ---- overlap, archived, deleted ----------------------------------------------------------------------------

    @Test
    fun overlappingJobs_billedCountsBoth_onClockCountsOnce() {
        val blocks = listOf(
            block("a", "A", nov(10, 9), nov(10, 11)),
            block("b", "B", nov(10, 10), nov(10, 12)),
        )
        val r = calc(blocks)
        assertThat(r.billedMs).isEqualTo(4 * hour)
        assertThat(r.onClockMs).isEqualTo(3 * hour)
    }

    @Test
    fun archivedJob_isIncluded_andFlagged() {
        val archived = jobA.copy(archived = true)
        val r = calc(listOf(block("a", "A", nov(10, 9), nov(10, 10))), jobs = listOf(archived))
        assertThat(r.byJob.single().archived).isTrue()
        assertThat(r.billedMs).isEqualTo(hour)
    }

    @Test
    fun deletedBlock_isExcluded() {
        val r = calc(listOf(block("a", "A", nov(10, 9), nov(10, 10), deleted = true)))
        assertThat(r.byJob).isEmpty()
        assertThat(r.onClockMs).isEqualTo(0)
    }

    @Test
    fun blockOfADeletedJob_isExcluded() {
        // A deleted job is not in the live job list.
        val r = calc(listOf(block("a", "A", nov(10, 9), nov(10, 10)), block("z", "gone", nov(10, 9), nov(10, 12))))
        assertThat(r.byJob.map { it.jobId }).containsExactly("A")
        assertThat(r.billedMs).isEqualTo(hour)
        assertThat(r.onClockMs).isEqualTo(hour)
    }

    // ---- grouping ----------------------------------------------------------------------------------------------

    @Test
    fun byJob_sortedByBilledDescending() {
        val blocks = listOf(
            block("a", "A", nov(10, 9), nov(10, 10)),
            block("b", "B", nov(10, 10), nov(10, 13)),
        )
        val r = calc(blocks)
        assertThat(r.byJob.map { it.jobId }).containsExactly("B", "A").inOrder()
        assertThat(r.billedMs).isEqualTo(4 * hour)
    }

    @Test
    fun byDay_groupsPerLocalDay_withJobRows() {
        val blocks = listOf(
            block("a1", "A", nov(10, 9), nov(10, 10)),
            block("b1", "B", nov(10, 10), nov(10, 12)),
            block("a2", "A", nov(12, 9), nov(12, 10)),
        )
        val r = calc(blocks, to = LocalDate.of(2025, 11, 12))
        assertThat(r.byDay.map { it.date.dayOfMonth }).containsExactly(10, 12).inOrder()
        assertThat(r.byDay[0].rows.map { it.jobId }).containsExactly("B", "A").inOrder()
        assertThat(r.byDay[1].rows.map { it.jobId }).containsExactly("A")
        assertThat(r.byDay.sumOf { it.billedMs }).isEqualTo(r.billedMs)
    }

    // ---- unlabelled --------------------------------------------------------------------------------------------

    @Test
    fun unlabelled_visitAndTripWithNoBlock_countInFull() {
        val r = calc(
            emptyList(),
            visits = listOf(visit("v", nov(10, 9), nov(10, 11))),
            trips = listOf(trip("t", nov(10, 11), nov(10, 11, 30))),
        )
        assertThat(r.unlabelledOnSiteMs).isEqualTo(2 * hour)
        assertThat(r.unlabelledTravelMs).isEqualTo(30 * min)
        assertThat(r.hasUnlabelled).isTrue()
        assertThat(r.hasBlocks).isFalse()
    }

    @Test
    fun unlabelled_partialCoverage_countsOnlyTheUncoveredPart() {
        val r = calc(
            listOf(block("a", "A", nov(10, 9, 30), nov(10, 10, 15)), block("b", "B", nov(10, 10), nov(10, 10, 30))),
            visits = listOf(visit("v", nov(10, 9), nov(10, 11))),
            trips = listOf(trip("t", nov(10, 10, 20), nov(10, 11, 20))),
        )
        // Visit 09:00-11:00 minus covered 09:30-10:30 (union of both blocks) leaves 30 + 30 min.
        assertThat(r.unlabelledOnSiteMs).isEqualTo(60 * min)
        // Trip 10:20-11:20 minus covered 10:20-10:30 leaves 50 min.
        assertThat(r.unlabelledTravelMs).isEqualTo(50 * min)
    }

    @Test
    fun unlabelled_fullyCovered_isZero() {
        val r = calc(
            listOf(block("a", "A", nov(10, 8), nov(10, 12), billable = false)),
            visits = listOf(visit("v", nov(10, 9), nov(10, 11))),
        )
        assertThat(r.unlabelledOnSiteMs).isEqualTo(0)
        assertThat(r.hasUnlabelled).isFalse()
    }

    @Test
    fun unlabelled_clippedToTheRange() {
        val r = calc(
            emptyList(),
            visits = listOf(visit("v", nov(9, 23), nov(10, 1))),
            trips = listOf(trip("t", nov(10, 23, 30), nov(11, 1))),
        )
        assertThat(r.unlabelledOnSiteMs).isEqualTo(hour)
        assertThat(r.unlabelledTravelMs).isEqualTo(30 * min)
    }

    @Test
    fun unlabelled_ignoredPlaceIsExcluded_deletedVisitToo() {
        val r = calc(
            emptyList(),
            visits = listOf(
                visit("ignored", nov(10, 9), nov(10, 10), place = "home"),
                visit("kept", nov(10, 10), nov(10, 11), place = "site"),
                visit("gone", nov(10, 11), nov(10, 12), place = "site", deleted = true),
                visit("noPlace", nov(10, 12), nov(10, 13), place = null),
            ),
            ignored = setOf("home"),
        )
        assertThat(r.unlabelledOnSiteMs).isEqualTo(2 * hour)
    }

    @Test
    fun unlabelled_ignoredPlaceIsStillCoveredByBlocksForOnClock() {
        val r = calc(
            listOf(block("a", "A", nov(10, 9), nov(10, 10))),
            visits = listOf(visit("v", nov(10, 9), nov(10, 10), place = "home")),
            ignored = setOf("home"),
        )
        assertThat(r.unlabelledOnSiteMs).isEqualTo(0)
        assertThat(r.onClockMs).isEqualTo(hour)
    }

    @Test
    fun emptyInput_isEmpty() {
        assertThat(calc(emptyList())).isEqualTo(SummaryResult.Empty)
    }

    @Test
    fun rangeBounds_followTheZone() {
        val (s, e) = SummaryRange(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4)).bounds(sydney)
        assertThat(e - s).isEqualTo(23 * hour)
    }
}
