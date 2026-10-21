package me.tarry.app.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.navigation.TimelineRoute
import me.tarry.app.ui.navigation.navigateTopLevel
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.app.ui.shell.TrackingToggle
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import me.tarry.app.ui.timeline.TimelineContent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TarryAppShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val timelineTab get() = composeRule.onNode(hasText("Timeline") and isSelectable())
    private val settingsTab get() = composeRule.onNode(hasText("Settings") and isSelectable())

    private fun setShell(state: TrackingState = TrackingState.IDLE, onToggle: () -> Unit = {}) {
        val backStack = mutableStateListOf<NavKey>(TimelineRoute)
        composeRule.setContent {
            TarryAppContent(
                trackingState = state,
                backStack = backStack,
                onNavigate = { backStack.navigateTopLevel(it) },
                onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                onToggleClick = onToggle,
                settingsContent = { SettingsContent(Settings()) },
                timelineContent = { TimelineContent(LocalDate.of(2026, 1, 1), emptyList(), {}, {}) },
            )
        }
    }

    @Test
    fun bottomBar_showsTimelineToggleAndSettings() {
        setShell()
        timelineTab.assertIsDisplayed()
        settingsTab.assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tracking: off").assertIsDisplayed()
    }

    @Test
    fun opensOnTimeline() {
        setShell()
        composeRule.onNodeWithText("Nothing yet today").assertIsDisplayed()
        composeRule.onNodeWithText("Rounding increment").assertDoesNotExist()
        timelineTab.assertIsSelected()
    }

    @Test
    fun tappingSettings_showsSettings_thenTimelineReturns() {
        setShell()
        settingsTab.performClick()
        composeRule.onNodeWithText("Stop radius").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing yet today").assertDoesNotExist()
        settingsTab.assertIsSelected()

        timelineTab.performClick()
        composeRule.onNodeWithText("Nothing yet today").assertIsDisplayed()
        composeRule.onNodeWithText("Rounding increment").assertDoesNotExist()
        timelineTab.assertIsSelected()
    }

    @Test
    fun tappingToggle_invokesCallback_withoutNavigating() {
        var clicks = 0
        setShell(onToggle = { clicks++ })
        composeRule.onNodeWithContentDescription("Tracking: off").performClick()
        composeRule.runOnIdle { assertEqualsInt(1, clicks) }
        composeRule.onNodeWithText("Nothing yet today").assertIsDisplayed()
    }

    @Test
    fun toggle_contentDescription_idle() = assertToggleDescription(TrackingState.IDLE, "Tracking: off")

    @Test
    fun toggle_contentDescription_paused() = assertToggleDescription(TrackingState.PAUSED, "Tracking: paused")

    @Test
    fun toggle_contentDescription_attention() =
        assertToggleDescription(TrackingState.ATTENTION, "Tracking: needs attention")

    private fun assertToggleDescription(state: TrackingState, label: String) {
        composeRule.setContent { TrackingToggle(state = state, onClick = {}) }
        composeRule.onNode(hasContentDescription(label)).assertIsDisplayed()
    }

    @Test
    fun toggle_trackingState_hasDescription_withInfinitePulse() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { TrackingToggle(state = TrackingState.TRACKING, onClick = {}) }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNode(hasContentDescription("Tracking: on")).assertIsDisplayed()
    }

    @Test
    fun toggle_touchTarget_isAtLeast48dp() {
        composeRule.setContent { TrackingToggle(state = TrackingState.IDLE, onClick = {}) }
        composeRule.onNodeWithContentDescription("Tracking: off")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    private fun assertEqualsInt(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected, actual)
}
