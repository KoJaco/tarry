package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.shell.CustomDurationActions
import me.tarry.app.ui.shell.CustomDurationSheetContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class CustomDurationSheetContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = CustomDurationActions(
        onStart = { calls += "start:$it" },
        onPause = { calls += "pause:$it" },
    )

    private fun show(
        state: TrackingState,
        details: TrackingDetails = TrackingDetails(),
        stepMin: Int = 15,
        maxMin: Int = 720,
    ) {
        composeRule.setContent {
            TarryTheme { CustomDurationSheetContent(
                state = state,
                details = details,
                stepMin = stepMin,
                maxMin = maxMin,
                actions = actions,
                onDone = { calls += "done" },
                nowProvider = { ZonedDateTime.of(2026, 3, 10, 10, 0, 0, 0, ZoneId.of("Australia/Brisbane")) },
            ) }
        }
    }

    @Test
    fun idleTitleIsStartTrackingFor() {
        show(TrackingState.IDLE)
        composeRule.onNodeWithText("Start tracking for…").assertIsDisplayed()
    }

    @Test
    fun trackingTitleIsPauseFor() {
        show(TrackingState.TRACKING)
        composeRule.onNodeWithText("Pause for…").assertIsDisplayed()
    }

    @Test
    fun pausedReplacesThePauseAndShowsWhenItEnds() {
        show(TrackingState.PAUSED, TrackingDetails())
        composeRule.onNodeWithText("Pause until…").assertIsDisplayed()
        composeRule.onNodeWithText("Paused until you resume").assertIsDisplayed()
    }

    @Test
    fun pausedWithAnEndTimeShowsIt() {
        show(TrackingState.PAUSED, TrackingDetails(pausedUntil = Instant.parse("2026-03-10T01:00:00Z")))
        composeRule.onNodeWithText("Pause until…").assertIsDisplayed()
        composeRule.onNodeWithText("Resumes at", substring = true).assertIsDisplayed()
    }

    @Test
    fun stepperStartsAtAnHourAndMovesInSteps() {
        show(TrackingState.IDLE)
        composeRule.onNodeWithText("1 h").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Longer").performClick()
        composeRule.onNodeWithText("1 h 15 min").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Shorter").performClick()
        composeRule.onNodeWithContentDescription("Shorter").performClick()
        composeRule.onNodeWithText("45 min").assertIsDisplayed()
    }

    @Test
    fun stepperButtonsAreTouchTargets() {
        show(TrackingState.IDLE)
        composeRule.onNodeWithContentDescription("Longer").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Shorter").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun decreaseStopsAtOneStep() {
        show(TrackingState.IDLE)
        repeat(3) { composeRule.onNodeWithContentDescription("Shorter").performClick() }
        composeRule.onNodeWithText("15 min").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Shorter").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Longer").assertIsEnabled()
    }

    @Test
    fun increaseStopsAtTheMaximum() {
        show(TrackingState.IDLE, maxMin = 90)
        repeat(2) { composeRule.onNodeWithContentDescription("Longer").performClick() }
        composeRule.onNodeWithText("1 h 30 min").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Longer").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Shorter").assertIsEnabled()
    }

    @Test
    fun confirmStartsForTheChosenMinutes() {
        show(TrackingState.IDLE)
        composeRule.onNodeWithContentDescription("Longer").performClick()
        composeRule.onNodeWithText("Start for 1 h 15 min").performClick()
        assertThat(calls).containsExactly("start:75", "done").inOrder()
    }

    @Test
    fun confirmPausesForTheChosenMinutes() {
        show(TrackingState.TRACKING)
        composeRule.onNodeWithContentDescription("Shorter").performClick()
        composeRule.onNodeWithText("Pause for 45 min").performClick()
        assertThat(calls).containsExactly("pause:45", "done").inOrder()
    }

    @Test
    fun pausedConfirmReplacesThePause() {
        show(TrackingState.PAUSED)
        composeRule.onNodeWithText("Pause for 1 h").performClick()
        assertThat(calls).containsExactly("pause:60", "done").inOrder()
    }

    @Test
    fun untilATimeConvertsThePickedTimeToMinutes() {
        show(TrackingState.IDLE)
        // The picker opens on now plus the chosen length (11:15 here), so confirming it as is means 75 minutes.
        composeRule.onNodeWithContentDescription("Longer").performClick()
        composeRule.onNodeWithText("Until a time…").performClick()
        assertThat(calls).isEmpty()
        composeRule.onNodeWithText("Until what time?").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        assertThat(calls).containsExactly("start:75", "done").inOrder()
    }

    @Test
    fun untilATimeWhileTrackingPauses() {
        show(TrackingState.TRACKING)
        composeRule.onNodeWithText("Until a time…").performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertThat(calls).containsExactly("pause:60", "done").inOrder()
    }

    @Test
    fun cancellingThePickerDoesNothing() {
        show(TrackingState.IDLE)
        composeRule.onNodeWithText("Until a time…").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(calls).isEmpty()
        composeRule.onNodeWithText("Start tracking for…").assertIsDisplayed()
    }
}
