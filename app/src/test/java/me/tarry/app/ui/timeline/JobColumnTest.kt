package me.tarry.app.ui.timeline

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.drawer.EditTarget
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w320dp-h1000dp")
class JobColumnTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private fun at(hour: Int, minute: Int = 0): Long = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val sky = JobPalette.slots.first { it.name == "Sky" }

    private val visits = listOf(TimelineItem.Visit("v", at(9), at(11), "Marina", needsReview = true))
    // A long visit, so the canvas reaches 13:00 and there is empty job column to draw on.
    private val longVisit = listOf(TimelineItem.Visit("v", at(9), at(13), "Marina", needsReview = true))
    private fun block(id: String, start: Long, end: Long, name: String = "Smith reno", billable: Boolean = true) =
        JobBlockItem(id, "job-$id", name, sky.key, start, end, billable, null)

    private val opened = mutableListOf<String>()
    private val confirmed = mutableListOf<JobSuggestion>()
    private val created = mutableListOf<Pair<Long, Long>>()
    private val visitOpened = mutableListOf<String>()

    private fun show(
        blocks: List<JobBlockItem>,
        suggestions: List<JobSuggestionItem> = emptyList(),
        items: List<TimelineItem> = visits,
    ) {
        composeRule.setContent {
            TarryTheme {
                TimelineContent(
                    date, items, {}, {}, today = date.plusDays(2), zone = zone, nowMs = at(20),
                    onEdit = { target -> (target as? EditTarget.Visit)?.let { visitOpened += it.id } },
                    jobBlocks = blocks, jobSuggestions = suggestions,
                    onOpenJob = { opened += it },
                    onConfirmSuggestion = { confirmed += it },
                    onCreateJob = { s, e -> created += s to e },
                )
            }
        }
    }

    /** Pixel y within the job column of [epochMs]; the canvas starts at 09:00 in these tests. */
    private fun TouchInjectionScope.yAt(epochMs: Long): Float = ((epochMs - at(9)) / 3_600_000f * 144f).dp.toPx()

    private fun description(tag: String): String =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    @Test
    fun blockShowsItsNameAndSpeaksItsRangeAndBilling() {
        show(listOf(block("a", at(9), at(11))))
        composeRule.onNodeWithTag("timeline_job_a").assertHeightIsAtLeast(48.dp)
        assertThat(description("timeline_job_a")).startsWith("Job Smith reno, from ")
        assertThat(description("timeline_job_a")).endsWith(", billable")
        val texts = composeRule.onNodeWithTag("timeline_job_a").fetchSemanticsNode().config[SemanticsProperties.Text]
        assertThat(texts.map { it.text }).contains("Smith reno")
    }

    @Test
    fun nonBillableBlockSaysSo() {
        show(listOf(block("a", at(9), at(11), billable = false)))
        assertThat(description("timeline_job_a")).endsWith(", not billable")
    }

    @Test
    fun blockIsFilledInTheJobColour() {
        show(listOf(block("a", at(9), at(11))))
        assertThat(composeRule.onNodeWithTag("timeline_job_a").fetchSemanticsNode().config[BlockColorKey]).isEqualTo(sky.light.container)
    }

    @Test
    fun tappingABlockOpensItsJob() {
        show(listOf(block("a", at(9), at(11))))
        composeRule.onNodeWithTag("timeline_job_a").performClick()
        assertThat(opened).containsExactly("a")
        assertThat(created).isEmpty()
    }

    @Test
    fun aShortBlockStillHasA48dpTouchTarget() {
        show(listOf(block("a", at(10), at(10, 5))))
        composeRule.onNodeWithTag("timeline_job_a").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun overlappingBlocksSitSideBySide() {
        show(listOf(block("a", at(9), at(11)), block("b", at(10), at(12), name = "Other")))
        val a = composeRule.onNodeWithTag("timeline_job_a").getUnclippedBoundsInRoot()
        val b = composeRule.onNodeWithTag("timeline_job_b").getUnclippedBoundsInRoot()
        assertThat(a.right.value).isAtMost(b.left.value + 0.01f)
    }

    @Test
    fun jobColumnIsRightOfTheVisitsColumn() {
        show(listOf(block("a", at(9), at(11))))
        val visit = composeRule.onNodeWithTag("timeline_row_v").getUnclippedBoundsInRoot()
        val job = composeRule.onNodeWithTag("timeline_job_a").getUnclippedBoundsInRoot()
        assertThat(visit.right.value).isLessThan(job.left.value)
    }

    @Test
    fun suggestionIsDashedAndConfirmsOnTap() {
        val s = JobSuggestion("v", "job-x", at(9), at(11))
        show(emptyList(), listOf(JobSuggestionItem(s, "Smith reno", sky.key)))
        val node = composeRule.onNodeWithTag("timeline_suggestion_v")
        assertThat(node.fetchSemanticsNode().config[BlockDashedKey]).isTrue()
        assertThat(description("timeline_suggestion_v")).startsWith("Suggested job Smith reno, from ")
        assertThat(node.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }).contains("Suggested: Smith reno")
        node.performClick()
        assertThat(confirmed).containsExactly(s)
    }

    @Test
    fun aSuggestionSharesTheColumnWithABlockInsteadOfCoveringIt() {
        val s = JobSuggestion("v", "job-x", at(9), at(11))
        show(listOf(block("a", at(9), at(11))), listOf(JobSuggestionItem(s, "Other", sky.key)))
        val a = composeRule.onNodeWithTag("timeline_job_a").getUnclippedBoundsInRoot()
        val sg = composeRule.onNodeWithTag("timeline_suggestion_v").getUnclippedBoundsInRoot()
        // Lanes are computed over blocks and suggestions together, whatever lane the block arrived with.
        assertThat(a.right.value).isAtMost(sg.left.value + 0.01f)
    }

    @Test
    fun longPressThenDragDrawsASnappedRange() {
        show(listOf(block("a", at(9), at(9, 30))), items = longVisit)
        composeRule.onNodeWithTag(JobColumnTag).performTouchInput {
            down(Offset(width / 2f, yAt(at(10, 7))))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(width / 2f, yAt(at(11, 40))))
            up()
        }
        assertThat(created).containsExactly(at(10) to at(11, 45))
        composeRule.onNodeWithTag("timeline_job_preview").assertDoesNotExist()
    }

    @Test
    fun longPressWithoutADragAsksForAnHourFromTheSnappedTime() {
        show(listOf(block("a", at(9), at(9, 30))), items = longVisit)
        composeRule.onNodeWithTag(JobColumnTag).performTouchInput {
            down(Offset(width / 2f, yAt(at(10, 20))))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        assertThat(created).containsExactly(at(10, 15) to at(11, 15))
    }

    @Test
    fun aPlainSwipeDoesNotCreateAJob() {
        show(listOf(block("a", at(9), at(9, 30))), items = longVisit)
        composeRule.onNodeWithTag(JobColumnTag).performTouchInput {
            swipeUp(startY = yAt(at(10, 30)), endY = yAt(at(10)))
        }
        assertThat(created).isEmpty()
    }

    @Test
    fun visitStillOpensOnDoubleTap() {
        show(listOf(block("a", at(9), at(11))))
        composeRule.onNodeWithTag("timeline_row_v").performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertThat(visitOpened).containsExactly("v")
    }

    @Test
    fun aJobWithNoVisitsIsStillShownInsteadOfTheEmptyState() {
        show(listOf(block("a", at(14), at(15))), items = emptyList())
        composeRule.onNodeWithTag("timeline_job_a").assertHeightIsAtLeast(48.dp)
    }
}
