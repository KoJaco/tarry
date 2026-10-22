package me.tarry.app.ui.timeline

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.tarry.core.data.repository.AnnotationOp
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
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private val targets = mutableListOf<EditTarget>()

    private fun at(hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val review = TimelineItem.Visit("review", at(9), at(10), "Marina")
    private val labelled = TimelineItem.Visit("labelled", at(11), at(12), "Yard", jobName = "Antifoul", jobColorArgb = null, jobId = "j")
    private val trip = TimelineItem.Trip("trip", at(10), at(11), 4000f, TripMode.VEHICLE)
    private val gap = TimelineItem.Gap("gap", at(12, 15), at(13), GapCause.NO_FIX)

    private data class Resize(val id: String, val start: Long, val end: Long)

    private val resizes = mutableListOf<Resize>()

    private fun show(items: List<TimelineItem>) {
        composeRule.setContent {
            TarryTheme {
                TimelineContent(
                    date, items, {}, {}, today = date, zone = zone, nowMs = at(20), onEdit = { targets += it },
                    onResizeVisit = { id, s, e -> resizes += Resize(id, s, e) },
                )
            }
        }
    }

    /** Multi-select harness: the same wiring as the shell (content + bar), with the bulk call recorded. */
    private var selection by mutableStateOf(emptySet<String>())
    private val bulkCalls = mutableListOf<Pair<List<String>, AnnotationOp>>()

    private fun showSelectable(items: List<TimelineItem>) {
        composeRule.setContent {
            TarryTheme {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        TimelineContent(
                            date, items, {}, {}, today = date, zone = zone, nowMs = at(20), onEdit = { targets += it },
                            onResizeVisit = { id, s, e -> resizes += Resize(id, s, e) },
                            selection = selection,
                            onStartSelection = { selection = linkedSetOf(it) },
                            onToggleSelected = { selection = if (it in selection) selection - it else selection + it },
                            onClearSelection = { selection = emptySet() },
                        )
                    }
                    if (selection.isNotEmpty()) {
                        SelectionBar(
                            count = selection.size, jobs = emptyList(), tagGroups = emptyList(),
                            onSetJob = {}, onNewJob = {}, onAddTags = {},
                            onSetBillable = { bulkCalls += selection.toList() to AnnotationOp.SetBillable(it); selection = emptySet() },
                            onIgnorePlace = { bulkCalls += selection.toList() to AnnotationOp.IgnorePlace(); selection = emptySet() },
                            onCancel = { selection = emptySet() },
                        )
                    }
                }
            }
        }
    }

    private fun isSelected(id: String) = tag("timeline_row_$id").fetchSemanticsNode().config[SemanticsProperties.Selected]

    private fun longPress(id: String) {
        tag("timeline_row_$id").performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    private fun handle(edge: ResizeEdge, id: String) = tag(handleTag(edge, id))

    /** A single tap is confirmed only after the double-tap timeout, so the test clock moves past it. */
    private fun select(id: String) {
        tag("timeline_row_$id").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
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
    fun doubleTappingAVisitOpensTheDrawerTarget_andATripOpensOnASingleTap() {
        show(listOf(review, trip, labelled))
        tag("timeline_row_review").performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        tag("timeline_row_trip").performClick()
        composeRule.waitForIdle()
        assertThat(targets).containsExactly(EditTarget.Visit("review"), EditTarget.Trip("trip")).inOrder()
    }

    @Test
    fun visitBlockIsFocusableAndEnterOpensTheDrawerTarget() {
        show(listOf(review, trip))
        tag("timeline_row_review").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
        tag("timeline_row_review").requestFocus()
        tag("timeline_row_review").assertIsFocused()
        tag("timeline_row_review").performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        assertThat(targets).containsExactly(EditTarget.Visit("review"))
    }

    @Test
    fun singleTapSelectsAVisitAndShowsHandlesWithoutOpeningTheDrawer() {
        show(listOf(review, trip, labelled))
        handle(ResizeEdge.Start, "review").assertDoesNotExist()

        select("review")

        handle(ResizeEdge.Start, "review").assertExists()
        handle(ResizeEdge.End, "review").assertExists()
        handle(ResizeEdge.End, "labelled").assertDoesNotExist()
        assertThat(targets).isEmpty()
        assertThat(tag("timeline_row_review").fetchSemanticsNode().config[SemanticsProperties.Selected]).isTrue()

        select("labelled")
        handle(ResizeEdge.End, "review").assertDoesNotExist()
        handle(ResizeEdge.End, "labelled").assertExists()
    }

    @Test
    fun handlesAreAtLeast48dpTall() {
        show(listOf(review))
        select("review")
        handle(ResizeEdge.End, "review").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun tappingElsewhereDeselects() {
        show(listOf(review, trip))
        select("review")
        tag("timeline_date").performClick()
        composeRule.waitForIdle()
        handle(ResizeEdge.Start, "review").assertDoesNotExist()
    }

    @Test
    fun scrollingDeselects() {
        show(listOf(review))
        select("review")
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        handle(ResizeEdge.Start, "review").assertDoesNotExist()
    }

    @Test
    fun draggingTheEndHandleSnapsTo5MinutesShowsALabelAndCommitsOnce() {
        show(listOf(review, trip, labelled))
        select("review")

        handle(ResizeEdge.End, "review").performTouchInput {
            down(center)
            moveBy(Offset(0f, 31f))
        }
        // 31 dp at 72 dp/h is 25.8 min, which snaps to 10:25. Nothing is committed while the finger is down.
        tag(ResizeLabelTag).assertTextContains("10:25", substring = true)
        assertThat(resizes).isEmpty()
        handle(ResizeEdge.End, "review").performTouchInput { up() }
        composeRule.waitForIdle()

        assertThat(resizes).containsExactly(Resize("review", at(9), at(10, 25)))
        tag(ResizeLabelTag).assertDoesNotExist()
    }

    @Test
    fun draggingTheStartHandleUpMovesTheStart() {
        show(listOf(review, labelled))
        select("review")
        handle(ResizeEdge.Start, "review").performTouchInput {
            down(center)
            moveBy(Offset(0f, -36f))
            up()
        }
        composeRule.waitForIdle()
        assertThat(resizes).containsExactly(Resize("review", at(8, 30), at(10)))
    }

    @Test
    fun theVisitCannotBeDraggedBelowTheMinimumOrIntoMoreThanANeighbourAllows() {
        show(listOf(review, labelled))
        select("review")
        handle(ResizeEdge.Start, "review").performTouchInput {
            down(center)
            moveBy(Offset(0f, 200f))
            up()
        }
        composeRule.waitForIdle()
        assertThat(resizes.single()).isEqualTo(Resize("review", at(9, 55), at(10)))

        resizes.clear()
        // Dragging the end through the next visit stops 5 min before its end.
        handle(ResizeEdge.End, "review").performTouchInput {
            down(center)
            moveBy(Offset(0f, 300f))
            up()
        }
        composeRule.waitForIdle()
        assertThat(resizes.single()).isEqualTo(Resize("review", at(9), at(11, 55)))
    }

    @Test
    fun aDragThatEndsWhereItStartedCommitsNothing() {
        show(listOf(review))
        select("review")
        handle(ResizeEdge.End, "review").performTouchInput {
            down(center)
            moveBy(Offset(0f, 20f))
            moveBy(Offset(0f, -20f))
            up()
        }
        composeRule.waitForIdle()
        assertThat(resizes).isEmpty()
    }

    @Test
    fun tripAndGapOpenOnASingleTap() {
        show(listOf(review, trip, gap))
        tag("timeline_row_trip").performClick()
        composeRule.waitForIdle()
        assertThat(targets).containsExactly(EditTarget.Trip("trip"))
        tag("timeline_row_gap").performClick()
        composeRule.onNodeWithText("could not get a location", substring = true).assertIsDisplayed()
    }

    @Test
    fun longPressEntersSelectionWithThatVisitWithoutOpeningOrResizing() {
        showSelectable(listOf(review, trip, labelled))
        assertThat(tag("timeline_row_review").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
            .isEqualTo("Select for bulk edit")

        longPress("review")

        assertThat(selection).containsExactly("review")
        assertThat(isSelected("review")).isTrue()
        assertThat(isSelected("labelled")).isFalse()
        tag(SelectionBarTag).assertIsDisplayed()
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        assertThat(targets).isEmpty()
        assertThat(resizes).isEmpty()
        handle(ResizeEdge.Start, "review").assertDoesNotExist()
        handle(ResizeEdge.End, "review").assertDoesNotExist()
    }

    @Test
    fun whileSelectingTapsToggleVisitsAndTripsAndGapsAreIgnored() {
        showSelectable(listOf(review, trip, labelled, gap))
        longPress("review")

        tag("timeline_row_labelled").performClick()
        composeRule.waitForIdle()
        assertThat(selection).containsExactly("review", "labelled")
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()

        tag("timeline_row_trip").performClick()
        tag("timeline_row_gap").performClick()
        composeRule.waitForIdle()
        assertThat(selection).containsExactly("review", "labelled")
        assertThat(targets).isEmpty()

        // A double tap does not open the drawer while selecting.
        tag("timeline_row_review").performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertThat(targets).isEmpty()

        // Toggling the last visit off leaves selection mode and brings the normal tap behaviour back.
        tag("timeline_row_labelled").performClick()
        composeRule.waitForIdle()
        assertThat(selection).containsExactly("review")
        tag("timeline_row_review").performClick()
        composeRule.waitForIdle()
        assertThat(selection).isEmpty()
        tag(SelectionBarTag).assertDoesNotExist()
    }

    @Test
    fun selectionBarActionIsOneBulkCallOverEverySelectedVisitThenExits() {
        showSelectable(listOf(review, trip, labelled))
        longPress("review")
        tag("timeline_row_labelled").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Billable").assertHasClickAction().performClick()
        composeRule.waitForIdle()

        assertThat(bulkCalls).hasSize(1)
        assertThat(bulkCalls.single().first).containsExactly("review", "labelled")
        assertThat(bulkCalls.single().second).isEqualTo(AnnotationOp.SetBillable(true))
        tag(SelectionBarTag).assertDoesNotExist()
        assertThat(isSelected("review")).isFalse()
    }

    @Test
    fun selectionBarOffersEveryActionAndCancelExits() {
        showSelectable(listOf(review))
        longPress("review")
        listOf("Set job", "Add tags", "Billable", "Not billable", "Ignore place", "Cancel").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertThat(selection).isEmpty()
        tag(SelectionBarTag).assertDoesNotExist()
    }

    @Test
    fun backExitsSelectionMode() {
        showSelectable(listOf(review))
        longPress("review")
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertThat(selection).isEmpty()
        tag(SelectionBarTag).assertDoesNotExist()
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
}
