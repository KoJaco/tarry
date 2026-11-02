package me.tarry.app.ui.summary

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.summary.BillingRules
import me.tarry.app.summary.RangePreset
import me.tarry.app.summary.SummaryGrouping
import me.tarry.app.summary.SummaryInput
import me.tarry.app.summary.SummaryRange
import me.tarry.app.summary.SummaryUiState
import me.tarry.app.summary.calculateSummary
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.settings.RoundingMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h1600dp")
class SummaryContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2025, 11, 10)
    private val range = SummaryRange(day, day.plusDays(1))

    private fun at(date: LocalDate, h: Int, m: Int = 0) =
        ZonedDateTime.of(date, java.time.LocalTime.of(h, m), zone).toInstant().toEpochMilli()

    private val alpha = Job(id = "A", name = "Alpha", colorArgb = 0, updatedAt = 0)
    private val bravo = Job(id = "B", name = "Bravo", colorArgb = 0, archived = true, updatedAt = 0)

    private fun block(id: String, job: String, date: LocalDate, from: Int, to: Int, billable: Boolean = true, notes: String? = null) =
        JobBlock(id = id, jobId = job, start = at(date, from), end = at(date, to), zoneId = zone.id, billable = billable, notes = notes, updatedAt = 0)

    private val blocks = listOf(
        block("a1", "A", day, 9, 11, notes = "Rewired the board"),
        block("a2", "A", day.plusDays(1), 9, 10),
        block("b1", "B", day, 13, 14, billable = false),
    )

    private fun result(
        blocks: List<JobBlock> = this.blocks,
        visits: List<Visit> = emptyList(),
        trips: List<Trip> = emptyList(),
        rules: BillingRules = BillingRules(),
    ) = calculateSummary(SummaryInput(range, zone, blocks, listOf(alpha, bravo), visits, trips, emptySet(), rules))

    private fun state(
        result: me.tarry.app.summary.SummaryResult? = result(),
        preset: RangePreset = RangePreset.THIS_WEEK,
        grouping: SummaryGrouping = SummaryGrouping.BY_JOB,
    ) = SummaryUiState(preset, range, grouping, zone, result)

    private fun show(
        state: SummaryUiState?,
        onPreset: (RangePreset) -> Unit = {},
        onGrouping: (SummaryGrouping) -> Unit = {},
        onOpenDay: (LocalDate) -> Unit = {},
        onCustomRange: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            TarryTheme {
                SummaryContent(state, onPreset, onCustomRange, onGrouping, onOpenDay)
            }
        }
    }

    private fun visit(from: Int, to: Int) = Visit(
        id = "v$from", placeId = "p", start = at(day, from), end = at(day, to), zoneId = zone.id,
        origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0,
    )

    private fun trip(from: Int, to: Int) = Trip(
        id = "t$from", start = at(day, from), end = at(day, to), zoneId = zone.id, distanceM = 1000f, mode = TripMode.VEHICLE, updatedAt = 0,
    )

    // ---- chips and grouping --------------------------------------------------------------------------------------

    @Test
    fun rangeChips_listThePresets_markTheCurrentOne_andReportTaps() {
        val picked = mutableListOf<RangePreset>()
        show(state(), onPreset = { picked += it })
        for (label in listOf("Today", "Yesterday", "This week", "Last week", "Custom")) {
            composeRule.onNode(hasText(label) and isSelectable()).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNode(hasText("This week") and isSelectable()).assertIsSelected()
        composeRule.onNode(hasText("Today") and isSelectable()).assertIsNotSelected()
        composeRule.onNode(hasText("Yesterday") and isSelectable()).performClick()
        composeRule.onNode(hasText("Last week") and isSelectable()).performClick()
        assertThat(picked).containsExactly(RangePreset.YESTERDAY, RangePreset.LAST_WEEK).inOrder()
    }

    @Test
    fun customChip_opensTheDateRangePicker_notThePresetCallback() {
        val picked = mutableListOf<RangePreset>()
        show(state(), onPreset = { picked += it })
        composeRule.onNode(hasText("Custom") and isSelectable()).performClick()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
        assertThat(picked).isEmpty()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun customPicker_confirmReportsTheInitialRange() {
        var custom: Pair<LocalDate, LocalDate>? = null
        show(state(), onCustomRange = { a, b -> custom = a to b })
        composeRule.onNode(hasText("Custom") and isSelectable()).performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertThat(custom).isEqualTo(range.from to range.to)
    }

    @Test
    fun groupingToggle_reportsTheChoice() {
        val picked = mutableListOf<SummaryGrouping>()
        show(state(), onGrouping = { picked += it })
        composeRule.onNode(hasText("By job") and isSelectable()).assertIsSelected()
        composeRule.onNode(hasText("By day") and isSelectable()).performClick()
        assertThat(picked).containsExactly(SummaryGrouping.BY_DAY)
    }

    @Test
    fun rangeCaption_showsTheDates() {
        show(state())
        composeRule.onNodeWithTag("summary_range_text").assertIsDisplayed()
    }

    // ---- rows ---------------------------------------------------------------------------------------------------

    @Test
    fun jobRows_showNameBlocksTotalAndBilled() {
        show(state())
        // Alpha: 2 blocks, 2 h + 1 h = 3 h, all billable. Bravo (archived): 1 block, 1 h not billable, billed 0.
        val alphaRow = composeRule.onNodeWithTag(SummaryTags.row("A"))
        alphaRow.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasText("Alpha") and hasText("2 blocks · 3 h") and hasText("3 h")).assertExists()
        composeRule.onNodeWithTag(SummaryTags.row("B")).assertIsDisplayed()
        composeRule.onNode(hasText("Bravo (archived)") and hasText("1 block · 1 h") and hasText("0 min")).assertExists()
    }

    @Test
    fun jobRows_areSortedByBilledTimeDescending() {
        show(state())
        val a = composeRule.onNodeWithTag(SummaryTags.row("A")).fetchSemanticsNode().boundsInRoot.top
        val b = composeRule.onNodeWithTag(SummaryTags.row("B")).fetchSemanticsNode().boundsInRoot.top
        assertThat(a).isLessThan(b)
    }

    @Test
    fun rows_haveSpokenDurations() {
        show(state())
        val description = composeRule.onNodeWithTag(SummaryTags.row("A")).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertThat(description).isEqualTo("Alpha, 2 blocks, total 3 hours, billed 3 hours")
    }

    @Test
    fun rows_withMinutes_areSpokenInHoursAndMinutes() {
        val r = result(blocks = listOf(block("x", "A", day, 9, 11).copy(end = at(day, 11, 15))))
        show(state(r))
        val description = composeRule.onNodeWithTag(SummaryTags.row("A")).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertThat(description).isEqualTo("Alpha, 1 block, total 2 hours 15 minutes, billed 2 hours 15 minutes")
    }

    @Test
    fun billedTimeUsesTheRules() {
        // 9:00 to 10:10 is 1 h 10 min on the clock, and rounds up to 2 h billed.
        val r = result(
            blocks = listOf(block("x", "A", day, 9, 10).copy(end = at(day, 10, 10))),
            rules = BillingRules(incrementMin = 60, mode = RoundingMode.UP),
        )
        show(state(r))
        composeRule.onNode(hasText("Billed 2 h · on the clock 1 h 10 min")).assertIsDisplayed()
    }

    @Test
    fun totals_showBilledAndOnTheClock_overlapCountsOnce() {
        val overlapping = listOf(block("a", "A", day, 9, 11), block("b", "B", day, 10, 12))
        show(state(result(blocks = overlapping)))
        composeRule.onNode(hasText("Billed 4 h · on the clock 3 h")).assertIsDisplayed()
    }

    @Test
    fun totals_areSpoken() {
        show(state())
        val description = composeRule.onNodeWithTag(SummaryTags.Totals).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertThat(description).isEqualTo("Billed 3 hours, on the clock 4 hours")
    }

    @Test
    fun byDay_showsOneSectionPerDay() {
        show(state(grouping = SummaryGrouping.BY_DAY))
        // Alpha appears under both days, Bravo under the first only.
        composeRule.onAllNodesWithTag(SummaryTags.row("A")).assertCountEquals(2)
        composeRule.onNodeWithTag(SummaryTags.row("B")).assertExists()
        composeRule.onNode(hasText("By day") and isSelectable()).assertIsSelected()
    }

    // ---- empty state and unlabelled ------------------------------------------------------------------------------

    @Test
    fun emptyState_showsTheMessage_andNoTotals() {
        show(state(result(blocks = emptyList())))
        composeRule.onNodeWithText("No job blocks in this range.").assertIsDisplayed()
        composeRule.onNodeWithTag(SummaryTags.Totals).assertDoesNotExist()
        composeRule.onNodeWithTag(SummaryTags.Unlabelled).assertDoesNotExist()
    }

    @Test
    fun emptyState_stillShowsTheUnlabelledCard() {
        show(state(result(blocks = emptyList(), visits = listOf(visit(9, 11)), trips = listOf(trip(11, 12)))))
        composeRule.onNodeWithText("No job blocks in this range.").assertIsDisplayed()
        composeRule.onNodeWithTag(SummaryTags.Unlabelled).assertIsDisplayed()
        composeRule.onNode(hasText("On site") and hasText("2 h")).assertExists()
        composeRule.onNode(hasText("Travel") and hasText("1 h")).assertExists()
    }

    @Test
    fun unlabelledCard_isHiddenAtZero_andShownWithPartialCoverage() {
        // Visit 9-11 fully covered by block a1 (9-11): nothing unlabelled.
        show(state(result(visits = listOf(visit(9, 11)))))
        composeRule.onNodeWithTag(SummaryTags.Unlabelled).assertDoesNotExist()
    }

    @Test
    fun unlabelledCard_showsOnlyTheUncoveredPart() {
        show(state(result(visits = listOf(visit(9, 12)), trips = listOf(trip(15, 16)))))
        composeRule.onNodeWithTag(SummaryTags.Unlabelled).assertIsDisplayed()
        composeRule.onNode(hasText("On site") and hasText("1 h")).assertExists()
        composeRule.onNode(hasText("Travel") and hasText("1 h")).assertExists()
        val description = composeRule.onNodeWithTag(SummaryTags.Unlabelled).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertThat(description).isEqualTo("Unlabelled: on site 1 hour, travel 1 hour")
    }

    @Test
    fun loading_showsNoRowsOrCards() {
        show(state(result = null))
        composeRule.onNodeWithTag(SummaryTags.Totals).assertDoesNotExist()
        composeRule.onNodeWithTag(SummaryTags.Empty).assertDoesNotExist()
    }

    // ---- drill-down ---------------------------------------------------------------------------------------------

    @Test
    fun jobRowTap_listsTheJobsBlocks_withDurationBilledAndNotes() {
        show(state())
        composeRule.onNodeWithTag(SummaryTags.row("A")).performClick()
        composeRule.onNodeWithTag(SummaryTags.Sheet).assertIsDisplayed()
        composeRule.onNodeWithTag(SummaryTags.piece("a1", day)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(SummaryTags.piece("a2", day.plusDays(1))).assertIsDisplayed()
        composeRule.onNodeWithText("Rewired the board").assertIsDisplayed()
        val description = composeRule.onNodeWithTag(SummaryTags.piece("a1", day)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertThat(description).contains("2 hours")
        assertThat(description).contains("billed 2 hours")
        assertThat(description).contains("notes: Rewired the board")
    }

    @Test
    fun drillDown_marksNonBillableBlocks() {
        show(state())
        composeRule.onNodeWithTag(SummaryTags.row("B")).performClick()
        composeRule.onNodeWithText("Not billable").assertIsDisplayed()
    }

    @Test
    fun blockTap_opensThatDayOnTheTimeline() {
        val opened = mutableListOf<LocalDate>()
        show(state(), onOpenDay = { opened += it })
        composeRule.onNodeWithTag(SummaryTags.row("A")).performClick()
        composeRule.onNodeWithTag(SummaryTags.piece("a2", day.plusDays(1))).performClick()
        assertThat(opened).containsExactly(day.plusDays(1))
        composeRule.onNodeWithTag(SummaryTags.Sheet).assertDoesNotExist()
    }

    @Test
    fun perDayMode_drillDownShowsTheDaysBilledTimeOnce() {
        val twoShort = listOf(block("s1", "A", day, 9, 10), block("s2", "A", day, 11, 12))
        val r = result(blocks = twoShort, rules = BillingRules(incrementMin = 60, mode = RoundingMode.UP, minimumMin = 60, perDay = true))
        show(state(r))
        composeRule.onNodeWithTag(SummaryTags.row("A")).performClick()
        composeRule.onNodeWithText("Billed for the day").assertIsDisplayed()
    }

    @Test
    fun drillDown_followsLiveData() {
        val current = mutableStateOf(state())
        composeRule.setContent { TarryTheme { SummaryContent(current.value, {}, { _, _ -> }, {}, {}) } }
        composeRule.onNodeWithTag(SummaryTags.row("A")).performClick()
        composeRule.onNodeWithTag(SummaryTags.piece("a2", day.plusDays(1))).assertExists()
        composeRule.runOnUiThread { current.value = state(result(blocks = blocks.filter { it.id != "a2" })) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SummaryTags.piece("a2", day.plusDays(1))).assertDoesNotExist()
        composeRule.onNodeWithTag(SummaryTags.piece("a1", day)).assertExists()
    }
}
