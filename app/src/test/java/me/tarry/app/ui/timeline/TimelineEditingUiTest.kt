package me.tarry.app.ui.timeline

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.drawer.EditTarget
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w320dp-h1000dp")
class TimelineEditingUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private val targets = mutableListOf<EditTarget>()

    private fun at(hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val review = TimelineItem.Visit("review", at(9), at(10), "Marina")
    private val labelled = TimelineItem.Visit("labelled", at(11), at(12), "Yard", jobName = "Antifoul", jobColorArgb = null, jobId = "j")
    private val trip = TimelineItem.Trip("trip", at(10), at(11), 4000f, TripMode.VEHICLE)
    private val gap = TimelineItem.Gap("gap", at(12, 15), at(13), GapCause.NO_FIX)

    private fun show(items: List<TimelineItem>) {
        composeRule.setContent {
            TarryTheme {
                TimelineContent(date, items, {}, {}, today = date, zone = zone, nowMs = at(20), onEdit = { targets += it })
            }
        }
    }

    private fun tag(t: String) = composeRule.onNodeWithTag(t)

    private fun clickLabel(t: String): String? =
        tag(t).fetchSemanticsNode().config[SemanticsActions.OnClick].label

    private fun role(t: String): Role? = tag(t).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)

    private fun dimmed(t: String): Boolean = tag(t).fetchSemanticsNode().config[BlockDimmedKey]

    @Test
    fun blocksAreButtonsWithTheirOwnClickLabels() {
        show(listOf(review, trip, labelled, gap))
        assertThat(clickLabel("timeline_row_review")).isEqualTo("Edit visit")
        assertThat(clickLabel("timeline_row_trip")).isEqualTo("Edit trip")
        assertThat(clickLabel("timeline_row_gap")).isEqualTo("Show details")
        listOf("review", "trip", "gap").forEach { assertThat(role("timeline_row_$it")).isEqualTo(Role.Button) }
    }

    @Test
    fun tappingAVisitOrATripOpensTheDrawerTarget() {
        show(listOf(review, trip, labelled))
        tag("timeline_row_review").performClick()
        tag("timeline_row_trip").performClick()
        assertThat(targets).containsExactly(EditTarget.Visit("review"), EditTarget.Trip("trip")).inOrder()
    }

    @Test
    fun mergedDescriptionAndTraversalSurviveTheClickable() {
        show(listOf(review))
        val config = tag("timeline_row_review").fetchSemanticsNode().config
        assertThat(config[SemanticsProperties.ContentDescription].joinToString()).contains("Marina")
        assertThat(config.contains(SemanticsProperties.TraversalIndex)).isTrue()
    }

    @Test
    fun tappingAGapOpensTheSheet_andAddVisitHereClampsToTheNeighbouringVisit() {
        // The visit covers the first five minutes of the gap (less than half, so the gap stays a block).
        val before = TimelineItem.Visit("before", at(12), at(12, 20), "Yard")
        show(listOf(before, gap))
        tag("timeline_row_gap").performClick()
        composeRule.onNodeWithText("could not get a location", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Add visit here").assertIsEnabled().performClick()
        assertThat(targets).containsExactly(EditTarget.NewVisit(at(12, 20), at(13)))
    }

    @Test
    fun clusterBlockShowsItsTextAndItsSheetListsTheOutages() {
        val g1 = TimelineItem.Gap("g1", at(10, 5), at(10, 30), GapCause.SERVICE_KILLED)
        val g2 = TimelineItem.Gap("g2", at(10, 35), at(11, 0), GapCause.SERVICE_KILLED)
        val g3 = TimelineItem.Gap("g3", at(11, 5), at(11, 40), GapCause.NO_FIX)
        val g4 = TimelineItem.Gap("g4", at(11, 40), at(11, 45), GapCause.NO_FIX)
        show(listOf(review, g1, g2, g3, g4))
        val clusterTag = "timeline_row_cluster:g1"
        tag(clusterTag).assertIsDisplayed()
        composeRule.onNodeWithText("Patchy tracking", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("4 outages", substring = true).assertIsDisplayed()
        assertThat(tag(clusterTag).fetchSemanticsNode().config[ClusterOutagesKey]).isEqualTo(4)
        tag(clusterTag).performClick()
        composeRule.onNodeWithText("Outages").assertIsDisplayed()
        composeRule.onNodeWithText("Add visit here").performClick()
        assertThat(targets).containsExactly(EditTarget.NewVisit(at(10, 5), at(11, 45)))
    }

    @Test
    fun needsReviewFilterDimsEverythingElseButHidesNothing() {
        show(listOf(review, trip, labelled, gap))
        listOf("review", "trip", "labelled", "gap").forEach { assertThat(dimmed("timeline_row_$it")).isFalse() }
        tag("timeline_filter_review").performClick()
        assertThat(dimmed("timeline_row_review")).isFalse()
        listOf("trip", "labelled", "gap").forEach {
            tag("timeline_row_$it").assertIsDisplayed()
            assertThat(dimmed("timeline_row_$it")).isTrue()
        }
        tag("timeline_filter_review").performClick()
        assertThat(dimmed("timeline_row_labelled")).isFalse()
    }

    @Test
    fun visitAtAnIgnoredPlaceIsDimmedByTheFilter() {
        val ignored = TimelineItem.Visit("ignored", at(9), at(10), "Home", placeIgnored = true)
        show(listOf(ignored, review.copy(id = "other", start = at(11), end = at(12))))
        tag("timeline_filter_review").performClick()
        assertThat(dimmed("timeline_row_ignored")).isTrue()
        assertThat(dimmed("timeline_row_other")).isFalse()
    }

    @Test
    fun newVisitButtonOpensANewVisitOnTheFirstFreeHourAfterTheLastVisit() {
        show(listOf(review, labelled))
        composeRule.onNodeWithText("New visit").assertIsDisplayed().performClick()
        assertThat(targets).containsExactly(EditTarget.NewVisit(at(12), at(13)))
    }

    @Test
    fun newVisitButtonDefaultsTo9To10OnAnEmptyDay() {
        show(emptyList())
        composeRule.onNodeWithText("New visit").performClick()
        assertThat(targets).containsExactly(EditTarget.NewVisit(at(9), at(10)))
    }

    @Test
    fun addVisitIsDisabledWhenVisitsCoverTheWholeGap() {
        val cover = TimelineItem.Visit("cover", at(12), at(14), "Yard")
        composeRule.setContent {
            TarryTheme {
                GapSheetContent(gap, emptyList(), freeSlotForGap(gap.start, gap.end, listOf(cover)), zone, { _, _ -> })
            }
        }
        composeRule.onNodeWithText("Add visit here").assertIsNotEnabled()
        composeRule.onNodeWithText("Visits already cover this time.").assertIsDisplayed()
    }

    @Test
    fun needsReviewToggleReportsItsStateAsASwitch() {
        show(listOf(review, labelled))
        tag("timeline_filter_review").assertIsOff().assertHasClickAction()
        assertThat(tag("timeline_filter_review").fetchSemanticsNode().config[SemanticsProperties.Role]).isEqualTo(Role.Switch)
        composeRule.onNodeWithText("Needs review").assertIsDisplayed()
        tag("timeline_filter_review").performClick()
        tag("timeline_filter_review").assertIsOn()
        tag("timeline_filter_review").performClick()
        tag("timeline_filter_review").assertIsOff()
    }

    @Test
    fun newVisitButtonHasALabelAndNoPlusIsLeftInTheHeader() {
        show(listOf(review))
        composeRule.onNodeWithText("New visit").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add visit").assertDoesNotExist()
        tag("timeline_add_visit").assertDoesNotExist()
    }

}
