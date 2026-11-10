package me.tarry.app.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

    private fun show(width: Dp, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
            ) {
                TarryTheme {
                    ReviewRow(
                        onAddJob = {},
                        showMap = showMap, onShowMapChange = { showMap = it },
                        modifier = Modifier.width(width),
                    )
                }
            }
        }
    }

    @Test
    fun wideRowShowsTheLabelledToggleThenAddJob() {
        show(560.dp)
        composeRule.onNodeWithText("Timeline").assertExists()
        composeRule.onNodeWithText("Map").assertExists()
        composeRule.onNodeWithText("Add job").assertExists()

        val toggleRight = composeRule.onNodeWithTag("timeline_view_toggle").fetchSemanticsNode().boundsInRoot.right
        val addJob = composeRule.onNodeWithTag("timeline_add_job").fetchSemanticsNode().boundsInRoot
        assertThat(addJob.left).isAtLeast(toggleRight)
    }

    @Test
    fun addJobStaysBesideTheToggleBelowFontScale1_5() {
        show(560.dp, fontScale = 1.0f)
        val toggle = composeRule.onNodeWithTag("timeline_view_toggle").fetchSemanticsNode().boundsInRoot
        val addJob = composeRule.onNodeWithTag("timeline_add_job").fetchSemanticsNode().boundsInRoot
        assertThat(addJob.top).isLessThan(toggle.bottom)
    }

    @Test
    fun addJobSitsOnItsOwnLeftAlignedLineFromFontScale1_5() {
        show(411.dp, fontScale = 2.0f)
        val row = composeRule.onNodeWithTag("timeline_review_row").fetchSemanticsNode().boundsInRoot
        val toggle = composeRule.onNodeWithTag("timeline_view_toggle").fetchSemanticsNode().boundsInRoot
        val addJob = composeRule.onNodeWithTag("timeline_add_job").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithText("Add job").assertExists()
        assertThat(addJob.top).isAtLeast(toggle.bottom)
        assertThat(addJob.left).isEqualTo(row.left)
        assertThat(addJob.right).isAtMost(row.right)
    }

    @Test
    fun addJobStretchesToTheRowEnd() {
        show(411.dp)
        val row = composeRule.onNodeWithTag("timeline_review_row").fetchSemanticsNode().boundsInRoot
        val addJob = composeRule.onNodeWithTag("timeline_add_job").fetchSemanticsNode().boundsInRoot
        assertThat(addJob.right).isWithin(1f).of(row.right)
    }

    @Test
    fun stackedAddJobSpansTheRow() {
        show(411.dp, fontScale = 2.0f)
        val row = composeRule.onNodeWithTag("timeline_review_row").fetchSemanticsNode().boundsInRoot
        val addJob = composeRule.onNodeWithTag("timeline_add_job").fetchSemanticsNode().boundsInRoot
        assertThat(addJob.right).isWithin(1f).of(row.right)
    }

    @Test
    fun narrowRowScrollsInsteadOfDroppingTheAddJobLabel() {
        show(200.dp)
        composeRule.onNodeWithTag("timeline_review_row").assert(hasScrollAction())
        composeRule.onNodeWithText("Add job").assertExists()
        composeRule.onNodeWithContentDescription("Add job").assertDoesNotExist()
    }

    @Test
    fun toggleOptionsAreAtLeast48dpTall() {
        show(560.dp)
        composeRule.onNodeWithText("Timeline").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Map").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("timeline_add_job").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun toggleSwitchesToTheMap() {
        show(560.dp)
        composeRule.onNode(hasText("Timeline") and hasClickAction()).assertIsSelected()
        composeRule.onNode(hasText("Map") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertThat(showMap).isTrue()
        composeRule.onNode(hasText("Map") and hasClickAction()).assertIsSelected()
    }

    @Test
    fun rowNoLongerCarriesTheReviewFilter() {
        show(560.dp)
        composeRule.onNodeWithTag("timeline_filter_review").assertDoesNotExist()
        composeRule.onNodeWithText("Needs review").assertDoesNotExist()
    }

    @Test
    fun rowHasNoZoomControl() {
        show(560.dp)
        composeRule.onNodeWithTag("timeline_zoom_in").assertDoesNotExist()
        composeRule.onNodeWithTag("timeline_zoom_out").assertDoesNotExist()
    }
}
