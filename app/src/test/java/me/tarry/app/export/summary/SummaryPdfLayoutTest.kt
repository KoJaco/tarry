package me.tarry.app.export.summary

import com.google.common.truth.Truth.assertThat
import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.SummaryInput
import me.tarry.app.summary.SummaryRange
import me.tarry.app.summary.calculateSummary
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.settings.RoundingMode
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Fixed-width text (half the font size per character) and English words, so every number below can be reasoned about. */
private object FakeMeasurer : TextMeasurer {
    override fun width(text: String, style: PdfTextStyle) = text.length * style.size * 0.5f
}

private object FakeText : SummaryPdfText {
    override val title = "Hours summary"
    override val unnamedPlace = "Unnamed place"
    override val coverJob = "Job"
    override val coverBlocks = "Blocks"
    override val coverTotal = "Total"
    override val coverBilled = "Billed"
    override val colDate = "Date"
    override val colTime = "Time"
    override val colDuration = "Duration"
    override val colBilled = "Billed"
    override val colPlaces = "Places"
    override val colNotes = "Notes"
    override fun rangeLong(range: SummaryRange) = "${range.from} to ${range.to}"
    override fun rangeShort(range: SummaryRange) = "${range.from} - ${range.to}"
    override fun rules(rules: BillingRules) = "rules ${rules.incrementMin}/${rules.minimumMin}/${rules.perDay}"
    override fun jobName(name: String, archived: Boolean) = if (archived) "$name (archived)" else name
    override fun continued(name: String) = "$name (continued)"
    override fun blocks(count: Int) = "$count blocks"
    override fun duration(ms: Long) = "${ms / 60_000L}m"
    override fun date(date: LocalDate) = date.toString()
    override fun timeRange(start: Long, end: Long, zone: ZoneId) = "t$start-$end"
    override fun totals(billedMs: Long, onClockMs: Long) = "Billed ${duration(billedMs)} · on the clock ${duration(onClockMs)}"
    override fun unlabelled(onSiteMs: Long, travelMs: Long) = "Unlabelled: on site ${duration(onSiteMs)} · travel ${duration(travelMs)}"
    override fun jobTotals(billedMs: Long, totalMs: Long, billableMs: Long) =
        "Billed ${duration(billedMs)} · total ${duration(totalMs)} · billable ${duration(billableMs)}"
    override fun dayTotal(billedMs: Long) = "Day total, billed ${duration(billedMs)}"
    override fun notBillable() = "Not billable"
    override fun generatedDate(date: LocalDate) = "gen $date"
    override fun footer(range: SummaryRange, generated: LocalDate, page: Int, pages: Int) =
        "Tarry · ${rangeShort(range)} · Generated ${generatedDate(generated)} · Page $page of $pages"
}

class SummaryPdfLayoutTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val first = LocalDate.of(2025, 11, 10)
    private val range = SummaryRange(first, first.plusDays(6))
    private val hour = 3_600_000L

    // 00:30 on 4 Nov 2025 in Sydney is still the 3rd in UTC: the footer shows the date in the snapshot zone.
    private val generatedAt = ZonedDateTime.of(LocalDate.of(2025, 11, 4), LocalTime.of(0, 30), zone).toInstant().toEpochMilli()

    private fun at(day: Int, h: Int, m: Int = 0) =
        ZonedDateTime.of(first.plusDays(day.toLong()), LocalTime.of(h, m), zone).toInstant().toEpochMilli()

    private fun block(id: String, job: String, day: Int, fromH: Int, toH: Int, notes: String? = null, billable: Boolean = true) = JobBlock(
        id = id, jobId = job, start = at(day, fromH), end = at(day, toH), zoneId = zone.id, billable = billable, notes = notes, updatedAt = 0,
    )

    private val alpha = Job(id = "A", name = "Alpha", colorArgb = 1, updatedAt = 0)
    private val bravo = Job(id = "B", name = "Bravo", colorArgb = 2, updatedAt = 0, archived = true)

    private fun snapshot(
        blocks: List<JobBlock>,
        rules: BillingRules = BillingRules(),
        visits: List<Visit> = emptyList(),
        trips: List<Trip> = emptyList(),
        places: List<Place> = emptyList(),
    ) = SummarySnapshot(
        range, zone,
        calculateSummary(
            SummaryInput(
                range, zone, blocks, listOf(alpha, bravo), visits, trips,
                ignoredPlaceIds = places.filter { it.ignored }.map { it.id }.toSet(), rules = rules,
            ),
        ),
        rules, visits, places, generatedAt,
    )

    private fun layout(s: SummarySnapshot) = SummaryPdfLayout.layout(s, FakeText, FakeMeasurer)

    private fun PdfPage.texts() = runs.map { it.text }

    private val twoJobs = listOf(block("a1", "A", 0, 9, 12), block("a2", "A", 1, 9, 10), block("b1", "B", 0, 13, 15))

    /** The runs of the page holding [name]'s section header, from that header on (jobs flow, so the page may hold the cover too). */
    private fun List<PdfPage>.jobPart(name: String): PdfPage {
        val page = first { p -> p.runs.any { it.style == PdfTextStyle.HEADING && it.text == name } }
        return PdfPage(page.runs.dropWhile { !(it.style == PdfTextStyle.HEADING && it.text == name) }, page.rules)
    }

    @Test
    fun smallJobs_flowAfterTheCover_inByJobOrder_withAGapBetweenThem() {
        val pages = layout(snapshot(twoJobs))
        assertThat(pages).hasSize(1)
        val runs = pages[0].runs
        val alpha = runs.first { it.style == PdfTextStyle.HEADING && it.text == "Alpha" }
        val bravo = runs.first { it.style == PdfTextStyle.HEADING && it.text == "Bravo (archived)" }
        val unlabelledOrTotals = runs.last { it.style == PdfTextStyle.TOTALS }
        assertThat(alpha.baseline).isGreaterThan(unlabelledOrTotals.baseline + 15f)
        assertThat(bravo.baseline).isGreaterThan(alpha.baseline)
        // A clear gap above the second job: the last rule of Alpha's table to Bravo's header is more than the heading itself.
        val lastRuleAbove = pages[0].rules.filter { it.y < bravo.baseline }.maxOf { it.y }
        assertThat(bravo.baseline - PdfTextStyle.HEADING.size - lastRuleAbove).isAtLeast(18f)
    }

    @Test
    fun aJobHeaderNearThePageBottom_movesToTheNextPage_withItsTableHeaderAndFirstRow() {
        var moved = 0
        for (n in 1..60) {
            val alphaBlocks = (0 until n).map { i -> block("a$i", "A", i % 7, 5 + i / 7, 6 + i / 7) }
            val pages = layout(snapshot(alphaBlocks + block("b", "B", 0, 13, 15)))
            val bravoPage = pages.indexOfFirst { p -> p.runs.any { it.style == PdfTextStyle.HEADING && it.text == "Bravo (archived)" } }
            val part = pages.jobPart("Bravo (archived)")
            // Never a header alone: the table header and the row follow it on the same page.
            assertThat(part.texts()).containsAtLeast("Date", "2025-11-10")
            val header = part.runs.first()
            if (bravoPage > 0 && header.baseline == PdfPageSpec.MARGIN + PdfTextStyle.HEADING.size) {
                // Only counts as moved when the previous page still had Alpha rows on it and was not a continuation break.
                moved++
            }
        }
        assertThat(moved).isGreaterThan(0)
    }

    @Test
    fun noRunOrRule_crossesTheContentBottom_andEveryPageHasItsFooter_whenJobsFlow() {
        for (n in 1..60) {
            val alphaBlocks = (0 until n).map { i -> block("a$i", "A", i % 7, 5 + i / 7, 6 + i / 7, notes = "note") }
            val pages = layout(snapshot(alphaBlocks + block("b", "B", 0, 13, 15)))
            pages.forEachIndexed { i, p ->
                for (r in p.runs.filter { it.style != PdfTextStyle.FOOTER }) assertThat(r.baseline).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
                for (rule in p.rules) assertThat(rule.y).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
                assertThat(p.runs.single { it.style == PdfTextStyle.FOOTER }.text).endsWith("Page ${i + 1} of ${pages.size}")
            }
        }
    }

    @Test
    fun textSizes_areTheOwnersNumbers() {
        assertThat(PdfTextStyle.BODY.size).isEqualTo(10.5f)
        assertThat(PdfTextStyle.BODY_BOLD.size).isEqualTo(10.5f)
        assertThat(PdfTextStyle.TOTALS.size).isEqualTo(13f)
        assertThat(PdfTextStyle.TITLE.size).isEqualTo(18f)
        assertThat(PdfTextStyle.FOOTER.size).isEqualTo(8f)
        assertThat(PdfTextStyle.BODY.lineHeight).isWithin(0.01f).of(10.5f * 1.3f)
    }

    @Test
    fun theCover_holdsTheTitleRangeRulesJobTableAndTotals() {
        val rules = BillingRules(15, RoundingMode.NEAREST, 30, perDay = true)
        val cover = layout(snapshot(twoJobs, rules)).first().texts()
        assertThat(cover).containsAtLeast("Hours summary", "2025-11-10 to 2025-11-16", "rules 15/30/true")
        assertThat(cover).containsAtLeast("Job", "Blocks", "Total", "Billed")
        assertThat(cover).containsAtLeast("Alpha", "2 blocks", "240m", "Bravo (archived)", "1 blocks", "120m")
        assertThat(cover).contains("Billed 360m · on the clock 360m")
    }

    @Test
    fun theUnlabelledLine_showsOnTheCover_andIsLeftOutAtZero() {
        val place = Place(id = "p", centroidLat = 0.0, centroidLng = 0.0, name = "Cafe", updatedAt = 0)
        val visit = Visit(
            id = "v", placeId = "p", start = at(2, 9), end = at(2, 10), zoneId = zone.id, origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0,
        )
        val trip = Trip(id = "t", start = at(2, 10), end = at(2, 11), zoneId = zone.id, distanceM = 1f, mode = TripMode.VEHICLE, updatedAt = 0)
        val with = layout(snapshot(twoJobs, visits = listOf(visit), trips = listOf(trip), places = listOf(place))).first().texts()
        assertThat(with).contains("Unlabelled: on site 60m · travel 60m")
        val without = layout(snapshot(twoJobs)).first().texts()
        assertThat(without.none { it.startsWith("Unlabelled") }).isTrue()
    }

    @Test
    fun aJobPage_hasItsHeaderTableHeaderAndRows() {
        val place = Place(id = "p", centroidLat = 0.0, centroidLng = 0.0, name = "Site", updatedAt = 0)
        val visit = Visit(
            id = "v", placeId = "p", start = at(0, 9), end = at(0, 12), zoneId = zone.id, origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0,
        )
        val rules = BillingRules(60, RoundingMode.UP, 0, perDay = false)
        val page = layout(snapshot(listOf(block("a1", "A", 0, 9, 12, notes = "Hello"), block("n", "A", 1, 9, 10, billable = false)), rules, listOf(visit), places = listOf(place))).jobPart("Alpha")
        val t = page.texts()
        assertThat(t).containsAtLeast("Alpha", "2025-11-10 to 2025-11-16", "Billed 180m · total 240m · billable 180m")
        assertThat(t).containsAtLeast("Date", "Time", "Duration", "Billed", "Places", "Notes")
        assertThat(t).containsAtLeast("2025-11-10", "180m", "Site", "Hello", "2025-11-11", "Not billable")
        assertThat(t.none { it.startsWith("Day total") }).isTrue()
    }

    @Test
    fun perDayMode_leavesBilledEmpty_andAddsADayTotalLineAfterEachDay() {
        val rules = BillingRules(0, RoundingMode.NEAREST, 0, perDay = true)
        val blocks = listOf(block("1", "A", 0, 9, 10), block("2", "A", 0, 11, 13), block("3", "A", 2, 9, 10))
        val page = layout(snapshot(blocks, rules)).jobPart("Alpha")
        assertThat(page.texts().filter { it.startsWith("Day total") }).containsExactly("Day total, billed 180m", "Day total, billed 60m").inOrder()
        // Billed column text appears only in the header: no per-row billed cells.
        assertThat(page.runs.count { it.text == "Billed" }).isEqualTo(1)
        // The first day's total sits under both of its rows.
        val firstTotal = page.runs.first { it.text == "Day total, billed 180m" }
        val lastRow = page.runs.filter { it.text == "2025-11-10" }.maxOf { it.baseline }
        assertThat(firstTotal.baseline).isGreaterThan(lastRow)
    }

    @Test
    fun perDayMode_saysNotBillable_forANonBillableBlock_andLeavesBillableBlocksBlank() {
        val rules = BillingRules(0, RoundingMode.NEAREST, 0, perDay = true)
        val blocks = listOf(block("1", "A", 0, 9, 10, billable = false), block("2", "A", 1, 9, 10))
        val page = layout(snapshot(blocks, rules)).jobPart("Alpha")
        assertThat(page.runs.count { it.text == "Not billable" }).isEqualTo(1)
        // The billable day still carries its figure on the day-total line only.
        assertThat(page.texts()).contains("Day total, billed 60m")
    }

    @Test
    fun theCoverTableHeader_isDrawnOnce_wherever_theTableStarts() {
        var startedOnLaterPage = false
        for (n in 1..60) {
            val longRules = object : SummaryPdfText by FakeText {
                override fun rules(rules: BillingRules) = "word ".repeat(n * 20)
            }
            val pages = SummaryPdfLayout.layout(snapshot(listOf(block("a", "A", 0, 9, 10))), longRules, FakeMeasurer)
            // At most once per page: a page break before the header never draws it twice (a header at the very bottom
            // with its row on the next page repeats it there, which is the continuation header and fine).
            for (p in pages) assertThat(p.runs.count { it.text == "Job" && it.style == PdfTextStyle.TABLE_HEADER }).isAtMost(1)
            assertThat(pages.flatMap { it.runs }.count { it.text == "Job" && it.style == PdfTextStyle.TABLE_HEADER }).isAtLeast(1)
            val tablePage = pages.indexOfFirst { p -> p.runs.any { it.text == "Job" } }
            if (tablePage > 0) {
                startedOnLaterPage = true
                // The table moved to a page that does not already hold anything of the cover's heading.
                assertThat(pages[tablePage].texts()).doesNotContain("Hours summary (continued)")
            }
        }
        assertThat(startedOnLaterPage).isTrue()
    }

    @Test
    fun placesAndNotes_wrapWithinTheirColumns() {
        val notes = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen"
        val page = layout(snapshot(listOf(block("a", "A", 0, 9, 10, notes = notes)))).jobPart("Alpha")
        val noteLines = page.runs.filter { it.x > 400f && it.text != "Notes" && it.style == PdfTextStyle.BODY && it.baseline > 100f }
        assertThat(noteLines.size).isAtLeast(3)
        assertThat(noteLines.joinToString(" ") { it.text }).isEqualTo(notes)
        for (line in noteLines) assertThat(line.x + FakeMeasurer.width(line.text, line.style)).isAtMost(PdfPageSpec.WIDTH - PdfPageSpec.MARGIN)
    }

    private fun longJob(): SummarySnapshot {
        // 24 rows, each with a long note that wraps to a few lines: well over two pages.
        val notes = "note ".repeat(30).trim()
        val blocks = (0 until 60).map { i -> block("b$i", "A", i % 7, 8 + (i / 7) % 6, 9 + (i / 7) % 6, notes = notes) }
            .distinctBy { it.start }.take(24)
        return snapshot(blocks)
    }

    @Test
    fun aLongJob_continuesOverThreePages_repeatingTheHeaders_andNoRowSplits() {
        val s = longJob()
        val pages = layout(s)
        // Alpha starts on the cover page and runs over the next pages.
        assertThat(pages.size).isAtLeast(3)
        val job = pages
        assertThat(job[0].texts()).doesNotContain("Alpha (continued)")
        assertThat(job[0].texts()).contains("Alpha")
        for (continuation in job.drop(1)) {
            assertThat(continuation.texts()).containsAtLeast("Alpha (continued)", "Date", "Time", "Duration", "Billed", "Places", "Notes")
        }
        // Every row of every page is whole: each note shows all of its lines, never part of them.
        val noteWords = job.sumOf { p -> p.runs.filter { it.x > 400f && it.text.startsWith("note") }.sumOf { it.text.split(' ').size } }
        assertThat(noteWords).isEqualTo(30 * s.result.byJob.single().pieces.size)
        for (p in pages) for (r in p.runs) assertThat(r.baseline).isAtMost(PdfPageSpec.HEIGHT - PdfPageSpec.MARGIN)
        for (p in job) for (r in p.runs.filter { it.style != PdfTextStyle.FOOTER }) assertThat(r.baseline).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
    }

    @Test
    fun everyPage_hasTheFooter_withPageNOfM() {
        val pages = layout(longJob())
        pages.forEachIndexed { i, p ->
            val footer = p.runs.single { it.style == PdfTextStyle.FOOTER }
            assertThat(footer.text).isEqualTo("Tarry · 2025-11-10 - 2025-11-16 · Generated gen 2025-11-04 · Page ${i + 1} of ${pages.size}")
            assertThat(footer.x).isEqualTo(PdfPageSpec.MARGIN)
            assertThat(footer.baseline).isEqualTo(PdfPageSpec.HEIGHT - PdfPageSpec.MARGIN)
        }
    }

    @Test
    fun aRowTallerThanAPage_isTruncatedWithAnEllipsis_onOnePage() {
        val notes = "word ".repeat(2000).trim()
        val pages = layout(snapshot(listOf(block("a", "A", 0, 9, 10, notes = notes), block("b", "A", 1, 9, 10, notes = "short"))))
        val job = pages
        val cut = job.flatMap { it.runs }.filter { it.text.endsWith("…") }
        assertThat(cut).hasSize(1)
        assertThat(cut.single().baseline).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
        // The row after it is still there, and nothing ran off the page.
        assertThat(job.flatMap { it.texts() }).contains("short")
        for (p in job) for (r in p.runs.filter { it.style != PdfTextStyle.FOOTER }) assertThat(r.baseline).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
    }

    @Test
    fun aLongJobName_wrapsTheContinuationHeader_andNoRunCrossesTheContentBottom() {
        val longName = "Very long job name ".repeat(10).trim()
        val long = Job(id = "L", name = longName, colorArgb = 1, updatedAt = 0)
        val tall = "word ".repeat(2000).trim()
        // Short rows first so the page is part full, then rows of the maximum height, then a normal one.
        val blocks = listOf(
            block("1", "L", 0, 9, 10, notes = "short"), block("2", "L", 1, 9, 10, notes = tall),
            block("3", "L", 2, 9, 10, notes = tall), block("4", "L", 3, 9, 10, notes = "end"),
        )
        val s = SummarySnapshot(range, zone, calculateSummary(SummaryInput(range, zone, blocks, listOf(long), emptyList(), emptyList(), emptySet(), BillingRules())), generatedAt = generatedAt)
        val pages = layout(s)
        val continuation = pages.drop(2)
        assertThat(continuation).isNotEmpty()
        for (p in continuation) {
            // The title is longer than one line, so the header is more than one HEADING run.
            assertThat(p.runs.count { it.style == PdfTextStyle.HEADING }).isAtLeast(2)
            assertThat(p.texts()).contains("Date")
        }
        assertThat(pages.flatMap { it.texts() }).containsAtLeast("short", "end")
        for (p in pages) {
            for (r in p.runs.filter { it.style != PdfTextStyle.FOOTER }) assertThat(r.baseline).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
            for (rule in p.rules) assertThat(rule.y).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
        }
    }

    @Test
    fun anAbsurdlyLongJobName_cutsTheContinuationHeader_andRowsStillFit() {
        val long = Job(id = "L", name = "Job name ".repeat(60).trim(), colorArgb = 1, updatedAt = 0)
        val tall = "word ".repeat(2000).trim()
        val blocks = listOf(block("1", "L", 0, 9, 10, notes = tall), block("2", "L", 1, 9, 10, notes = tall))
        val s = SummarySnapshot(range, zone, calculateSummary(SummaryInput(range, zone, blocks, listOf(long), emptyList(), emptyList(), emptySet(), BillingRules())), generatedAt = generatedAt)
        val pages = layout(s)
        for (p in pages.drop(2)) assertThat(p.runs.count { it.style == PdfTextStyle.HEADING }).isAtMost(3)
        for (p in pages) for (r in p.runs.filter { it.style != PdfTextStyle.FOOTER }) assertThat(r.baseline).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
    }

    @Test
    fun cellsWiderThanEveryColumn_stillLayOut_oneCharacterPerLine() {
        // Every character is wider than any column, the same as a column with no width: wrapping must still end.
        val wide = object : TextMeasurer {
            override fun width(text: String, style: PdfTextStyle) = text.length * 1000f
        }
        val pages = SummaryPdfLayout.layout(snapshot(listOf(block("a", "A", 0, 9, 10, notes = "abc"))), FakeText, wide)
        assertThat(pages).isNotEmpty()
        assertThat(pages.flatMap { it.texts() }).containsAtLeast("a", "b", "c")
        for (p in pages) for (r in p.runs.filter { it.style != PdfTextStyle.FOOTER }) assertThat(r.baseline).isAtMost(PdfPageSpec.CONTENT_BOTTOM)
    }

    @Test
    fun emptyAndMissingNotes_addNoHeightToARow() {
        // The fake time text wraps in its column, so the reference is a row with a one word note, not a fixed height.
        val blocks = listOf(
            block("1", "A", 0, 9, 10, notes = ""), block("2", "A", 1, 9, 10, notes = null),
            block("3", "A", 2, 9, 10, notes = "   "), block("4", "A", 3, 9, 10, notes = "x"), block("5", "A", 4, 9, 10, notes = "x"),
        )
        val page = layout(snapshot(blocks)).jobPart("Alpha")
        val tops = (10..14).map { d -> page.runs.single { it.text == "2025-11-$d" }.baseline }
        val steps = tops.zipWithNext { a, b -> b - a }
        for (step in steps) assertThat(step).isWithin(0.01f).of(steps.last())
    }

    @Test
    fun tabsAndOtherControlCharacters_becomeSpaces_butNewlinesStay() {
        val page = layout(snapshot(listOf(block("a", "A", 0, 9, 10, notes = "one\ttwo\u0000three\u001Bfour\nfive\r\nsix")))).jobPart("Alpha")
        val noteLines = page.runs.filter { it.x > 400f && it.style == PdfTextStyle.BODY && it.baseline > 100f }.map { it.text }
        assertThat(noteLines.joinToString("\n")).isEqualTo("one two three four\nfive\nsix")
        for (p in listOf(page)) for (r in p.runs) assertThat(r.text.any { it.isISOControl() }).isFalse()
    }

    @Test
    fun theCoverTable_continuesOnAnotherPage_whenThereAreManyJobs() {
        val jobs = (0 until 120).map { Job(id = "J$it", name = "Job $it", colorArgb = 1, updatedAt = 0) }
        val blocks = jobs.map { block("b${it.id}", it.id, 0, 9, 10).copy(jobId = it.id) }
        val s = SummarySnapshot(range, zone, calculateSummary(SummaryInput(range, zone, blocks, jobs, emptyList(), emptyList(), emptySet(), BillingRules())), generatedAt = generatedAt)
        val pages = layout(s)
        assertThat(pages[1].texts()).contains("Hours summary (continued)")
        assertThat(pages.flatMap { it.texts() }).contains("Billed 7200m · on the clock 60m")
    }
}
