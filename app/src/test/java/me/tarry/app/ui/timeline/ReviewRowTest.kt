package me.tarry.app.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
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

    private fun show(width: Dp, showToggle: Boolean = true) {
        composeRule.setContent {
            TarryTheme {
                ReviewRow(
                    reviewOnly = false, showToggle = showToggle, onReviewOnlyChange = {}, onAddJob = {},
                    showMap = showMap, onShowMapChange = { showMap = it }, modifier = Modifier.width(width),
                )
            }
        }
    }

    @Test
    fun wideRowKeepsTheAddJobLabel() {
        show(560.dp)
        composeRule.onNodeWithText("Add job").assertExists()
        composeRule.onNodeWithText("Needs review").assertExists()
    }

    @Test
    fun narrowRowShowsAddJobAsAnIconWithItsLabelAsDescription() {
        // Narrower than the labelled row (Robolectric measures text narrower than a device, so 320 dp would still fit).
        show(200.dp)
        composeRule.onNodeWithText("Add job").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Add job")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Needs review").assertExists()
    }

    @Test
    fun toggleSwitchesToTheMapAndHidesTheReviewFilter() {
        show(560.dp)
        composeRule.onNodeWithContentDescription("Day").assertIsSelected()
        composeRule.onNodeWithContentDescription("Map").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(showMap).isTrue()
        composeRule.onNodeWithContentDescription("Map").assertIsSelected()
        composeRule.onNodeWithTag("timeline_filter_review").assertDoesNotExist()
    }
}
