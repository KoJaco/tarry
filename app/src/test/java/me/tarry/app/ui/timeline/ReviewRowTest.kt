package me.tarry.app.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w600dp-h800dp")
class ReviewRowTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var showMap by mutableStateOf(false)
    private var zoom by mutableStateOf(ZoomLevel.Q15)

    private fun show(width: Dp, showToggle: Boolean = true) {
        composeRule.setContent {
            TarryTheme {
                ReviewRow(
                    reviewOnly = false, showToggle = showToggle, onReviewOnlyChange = {}, onAddJob = {},
                    showMap = showMap, onShowMapChange = { showMap = it }, zoomLevel = zoom, onZoomLevelChange = { zoom = it },
                    modifier = Modifier.width(width),
                )
            }
        }
    }

    @Test
    fun wideRowShowsTheLabelledToggleThenAddJobThenTheFilter() {
        show(560.dp)
        composeRule.onNodeWithText("Timeline").assertExists()
        composeRule.onNodeWithText("Map").assertExists()
        composeRule.onNodeWithText("Add job").assertExists()
        composeRule.onNodeWithText("Needs review").assertExists()

        val toggleRight = composeRule.onNodeWithTag("timeline_view_toggle").fetchSemanticsNode().boundsInRoot.right
        val addJob = composeRule.onNodeWithTag("timeline_add_job").fetchSemanticsNode().boundsInRoot
        val filterLeft = composeRule.onNodeWithTag("timeline_filter_review").fetchSemanticsNode().boundsInRoot.left
        assertThat(addJob.left).isAtLeast(toggleRight)
        assertThat(filterLeft).isAtLeast(addJob.right)
    }

    @Test
    fun narrowRowScrollsInsteadOfDroppingTheAddJobLabel() {
        show(200.dp)
        composeRule.onNodeWithTag("timeline_review_row").assert(hasScrollAction())
        composeRule.onNodeWithText("Add job").assertExists()
        composeRule.onNodeWithContentDescription("Add job").assertDoesNotExist()
        composeRule.onNodeWithText("Needs review").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun toggleOptionsAreAtLeast48dpTall() {
        show(560.dp)
        composeRule.onNodeWithText("Timeline").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Map").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("timeline_add_job").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun toggleSwitchesToTheMapAndHidesTheReviewFilter() {
        show(560.dp)
        composeRule.onNode(hasText("Timeline") and hasClickAction()).assertIsSelected()
        composeRule.onNode(hasText("Map") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertThat(showMap).isTrue()
        composeRule.onNode(hasText("Map") and hasClickAction()).assertIsSelected()
        composeRule.onNodeWithTag("timeline_filter_review").assertDoesNotExist()
    }

    @Test
    fun zoomPillSitsBetweenAddJobAndTheFilterAndIsAtLeast48dpTall() {
        show(560.dp)
        composeRule.onNodeWithTag("timeline_zoom").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("15 min", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Zoom, 15 minute steps").assertExists()
        val addJobRight = composeRule.onNodeWithTag("timeline_add_job").fetchSemanticsNode().boundsInRoot.right
        val zoomBounds = composeRule.onNodeWithTag("timeline_zoom").fetchSemanticsNode().boundsInRoot
        val filterLeft = composeRule.onNodeWithTag("timeline_filter_review").fetchSemanticsNode().boundsInRoot.left
        assertThat(zoomBounds.left).isAtLeast(addJobRight)
        assertThat(filterLeft).isAtLeast(zoomBounds.right)
    }

    @Test
    fun zoomMenuListsThreeStepsAndChoosingOneChangesTheLevel() {
        show(560.dp)
        composeRule.onNodeWithTag("timeline_zoom").performClick()
        composeRule.onNodeWithTag("timeline_zoom_15").assertExists()
        composeRule.onNodeWithTag("timeline_zoom_10").assertExists()
        composeRule.onNodeWithTag("timeline_zoom_5").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(zoom).isEqualTo(ZoomLevel.Q5)
        composeRule.onNodeWithText("5 min", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Zoom, 5 minute steps").assertExists()
    }

    @Test
    fun zoomPillIsHiddenOnTheMap() {
        show(560.dp)
        composeRule.onNode(hasText("Map") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("timeline_zoom").assertDoesNotExist()
    }
}
