package me.tarry.app.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
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
            .fetchSemanticsNode().config[PulseRingScale].invoke()

    private fun assertToggle(state: TrackingState, text: String, description: String) {
        composeRule.setContent { TrackingToggle(state = state, onClick = {}) }
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun toggle_tracking_textAndDescription() = assertToggle(TrackingState.TRACKING, "Tracking", "Tracking: on")

    @Test
    fun toggle_idle_textAndDescription() = assertToggle(TrackingState.IDLE, "Off", "Tracking: off")

    @Test
    fun toggle_paused_textAndDescription() = assertToggle(TrackingState.PAUSED, "Paused", "Tracking: paused")

    @Test
    fun toggle_attention_textAndDescription() = assertToggle(TrackingState.ATTENTION, "Needs attention", "Tracking: needs attention")


    @Test
    fun toggle_tapAndLongPress_bothInvokeOnClick() {
        var clicks = 0
        composeRule.setContent { TrackingToggle(state = TrackingState.IDLE, onClick = { clicks++ }) }
        composeRule.onNodeWithContentDescription("Tracking: off").performClick()
        assertThat(clicks).isEqualTo(1)
        composeRule.onNodeWithContentDescription("Tracking: off").performTouchInput { longClick() }
        assertThat(clicks).isEqualTo(2)
    }
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

    @Test
    fun bottomBar_navTap_performsHaptic_andInvokesClick() {
        val performed = mutableListOf<HapticFeedbackType>()
        val fake = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                performed += hapticFeedbackType
            }
        }
        var clicks = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides fake) {
                TarryBottomBar(TrackingState.IDLE, true, false, {}, { clicks++ }, {})
            }
        }
        composeRule.onNode(hasText("Settings") and isSelectable()).performClick()
        assertThat(clicks).isEqualTo(1)
        assertThat(performed).containsExactly(HapticFeedbackType.SegmentTick)
    }

    private fun indicatorCentreX(): Float =
        composeRule.onNodeWithTag(BottomBarTags.Indicator).getUnclippedBoundsInRoot().let { (it.left + it.width / 2).value }

    private fun slotRange(tag: String): ClosedFloatingPointRange<Float> =
        composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.left.value..it.right.value }

    private fun setBar(reduced: Boolean, selection: androidx.compose.runtime.MutableState<Boolean>) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMotionPreferences provides MotionPreferences { reduced }) {
                TarryBottomBar(TrackingState.IDLE, selection.value, !selection.value, {}, {}, {})
            }
        }
    }

    @Test
    fun bottomBar_indicator_sitsInSelectedSlot_andIsIconSized() {
        val timeline = mutableStateOf(true)
        setBar(reduced = false, selection = timeline)
        composeRule.waitForIdle()
        assertThat(slotRange(BottomBarTags.TimelineSlot).contains(indicatorCentreX())).isTrue()
        val bounds = composeRule.onNodeWithTag(BottomBarTags.Indicator).getUnclippedBoundsInRoot()
        assertThat(bounds.width.value).isEqualTo(64f)
        assertThat(bounds.height.value).isEqualTo(32f)

        composeRule.runOnUiThread { timeline.value = false }
        composeRule.waitForIdle()
        assertThat(slotRange(BottomBarTags.SettingsSlot).contains(indicatorCentreX())).isTrue()
        assertThat(slotRange(BottomBarTags.TimelineSlot).contains(indicatorCentreX())).isFalse()
    }

    /** Flips the selection to Settings with the clock paused and returns the first indicator x that differs from [start]. */
    private fun firstMovedCentreX(timeline: androidx.compose.runtime.MutableState<Boolean>, start: Float): Float {
        composeRule.runOnUiThread { timeline.value = false }
        repeat(30) {
            composeRule.mainClock.advanceTimeBy(16)
            val x = indicatorCentreX()
            if (x != start) return x
        }
        error("indicator never moved")
    }

    @Test
    fun bottomBar_indicator_glidesWhenMotionAllowed() {
        val timeline = mutableStateOf(true)
        setBar(reduced = false, selection = timeline)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        val start = indicatorCentreX()
        val first = firstMovedCentreX(timeline, start)
        // Mid-glide: moving right, but still short of the Settings slot.
        assertThat(first).isGreaterThan(start)
        assertThat(slotRange(BottomBarTags.SettingsSlot).contains(first)).isFalse()
        // Still gliding well into the 350 ms animation (200 ms in), then settled after it ends.
        composeRule.mainClock.advanceTimeBy(184)
        val midway = indicatorCentreX()
        composeRule.mainClock.advanceTimeBy(400)
        assertThat(midway).isLessThan(indicatorCentreX())
        assertThat(slotRange(BottomBarTags.SettingsSlot).contains(indicatorCentreX())).isTrue()
    }

    @Test
    fun bottomBar_indicator_jumpsWhenReducedMotion() {
        val timeline = mutableStateOf(true)
        setBar(reduced = true, selection = timeline)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        val start = indicatorCentreX()
        // The very first change is already the final position: no in-between frames.
        val first = firstMovedCentreX(timeline, start)
        assertThat(slotRange(BottomBarTags.SettingsSlot).contains(first)).isTrue()
        val settled = first
        composeRule.mainClock.advanceTimeBy(400)
        assertThat(indicatorCentreX()).isEqualTo(settled)
    }

    @Test
    fun bottomBar_indicator_hiddenWhenNeitherSelected() {
        composeRule.setContent {
            TarryBottomBar(TrackingState.IDLE, timelineSelected = false, settingsSelected = false, {}, {}, {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BottomBarTags.Indicator).assertDoesNotExist()
    }

}
