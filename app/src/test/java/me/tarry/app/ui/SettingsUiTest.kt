package me.tarry.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val selected = mutableListOf<Int>()

    private fun show(radius: Int = 100) = composeRule.setContent {
        SettingsContent(Settings(stayGeofenceRadiusM = radius), onStayRadiusSelected = { selected += it })
    }

    @Test
    fun stopRadiusRowShowsCurrentValueAndIsATallEnoughTarget() {
        show(radius = 150)
        composeRule.onNodeWithText("Stop radius").assertExists()
        composeRule.onNodeWithText("150 m").assertExists()
        composeRule.onNodeWithText("Stop radius").assertHeightIsAtLeast(48.dp)
    }

    private val radioOption = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun option(text: String) = hasText(text) and radioOption

    @Test
    fun tappingTheRowListsTheFourOptionsAndTheExplanation() {
        show()
        composeRule.onNodeWithText("Stop radius").performClick()
        composeRule.onAllNodes(radioOption).assertCountEquals(4)
        for (m in listOf(100, 150, 200, 300)) composeRule.onNode(option("$m m")).assertExists()
        composeRule.onNode(option("100 m")).assertIsSelected()
        composeRule.onNodeWithText("How far you must move", substring = true).assertExists()
    }

    @Test
    fun selectingAnOptionCallsBackAndClosesTheDialog() {
        show()
        composeRule.onNodeWithText("Stop radius").performClick()
        composeRule.onNode(option("200 m")).performClick()

        assertThat(selected).containsExactly(200)
        composeRule.onNodeWithText("How far you must move", substring = true).assertDoesNotExist()
    }
}
