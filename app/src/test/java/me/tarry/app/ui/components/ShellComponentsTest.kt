package me.tarry.app.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.google.common.truth.Truth.assertThat
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.shell.BottomBarTags
import me.tarry.app.ui.shell.PulseRingScale
import me.tarry.app.ui.shell.PulseRingTag
import me.tarry.app.ui.shell.TarryBottomBar
import me.tarry.app.ui.shell.TrackingToggle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShellComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun ringScale(): Float =
        composeRule.onNodeWithTag(PulseRingTag, useUnmergedTree = true)
            .fetchSemanticsNode().config[PulseRingScale]

    private fun assertToggle(state: TrackingState, text: String, description: String) {
        composeRule.setContent { TrackingToggle(state = state, onClick = {}) }
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun toggle_tracking_textAndDescription() = assertToggle(TrackingState.TRACKING, "Tracking", "Tracking: Tracking")

    @Test
    fun toggle_idle_textAndDescription() = assertToggle(TrackingState.IDLE, "Idle", "Tracking: Idle")

    @Test
    fun toggle_paused_textAndDescription() = assertToggle(TrackingState.PAUSED, "Paused", "Tracking: Paused")

    @Test
    fun toggle_attention_textAndDescription() = assertToggle(TrackingState.ATTENTION, "Attention", "Tracking: Attention")

    @Test
    fun toggle_pulseRing_onlyWhileTracking() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { TrackingToggle(state = TrackingState.IDLE, onClick = {}) }
        composeRule.onNodeWithTag(PulseRingTag, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun toggle_pulseRing_animates_whenMotionAllowed() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalMotionPreferences provides MotionPreferences { false }) {
                TrackingToggle(state = TrackingState.TRACKING, onClick = {})
            }
        }
        composeRule.mainClock.advanceTimeBy(100)
        val early = ringScale()
        composeRule.mainClock.advanceTimeBy(800)
        val later = ringScale()
        assertThat(early).isAtLeast(1f)
        assertThat(later).isGreaterThan(early)
        assertThat(later).isAtMost(1.35f)
    }

    @Test
    fun toggle_pulseRing_isStaticAt115_whenReducedMotion() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalMotionPreferences provides MotionPreferences { true }) {
                TrackingToggle(state = TrackingState.TRACKING, onClick = {})
            }
        }
        assertThat(ringScale()).isEqualTo(1.15f)
        composeRule.mainClock.advanceTimeBy(900)
        assertThat(ringScale()).isEqualTo(1.15f)
    }

    @Test
    fun bottomBar_threeSlotsHaveEqualWidths_andAtLeast56dpTall() {
        composeRule.setContent {
            TarryBottomBar(TrackingState.IDLE, true, false, {}, {}, {})
        }
        val tags = listOf(BottomBarTags.TimelineSlot, BottomBarTags.ToggleSlot, BottomBarTags.SettingsSlot)
        val bounds = tags.map { composeRule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        assertThat(bounds[1].width.value).isEqualTo(bounds[0].width.value)
        assertThat(bounds[2].width.value).isEqualTo(bounds[0].width.value)
        bounds.forEach { assertThat(it.height.value).isAtLeast(56f) }
        // Order: Timeline | toggle | Settings.
        assertThat(bounds[0].left).isLessThan(bounds[1].left)
        assertThat(bounds[1].left).isLessThan(bounds[2].left)
    }

    @Test
    fun bottomBar_navPills_selectedSemantics() {
        composeRule.setContent {
            TarryBottomBar(TrackingState.IDLE, timelineSelected = false, settingsSelected = true, {}, {}, {})
        }
        composeRule.onNode(hasText("Timeline") and isSelectable()).assertIsNotSelected()
        composeRule.onNode(hasText("Settings") and isSelectable()).assertIsSelected()
    }

}
