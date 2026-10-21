package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.PauseKind
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.shell.TrackingSheetActions
import me.tarry.app.ui.shell.TrackingSheetContent
import me.tarry.core.data.model.ManualOverrideEndMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackingSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = TrackingSheetActions(
        onStart = { mode, minutes -> calls += "start:$mode:$minutes" },
        onPause = { calls += "pause:$it" },
        onResume = { calls += "resume" },
        onStop = { calls += "stop" },
        onOpenLocationSettings = { calls += "location" },
        onOpenPermissionsScreen = { calls += "permissions" },
        onOpenAppSettings = { calls += "app" },
    )

    private fun show(state: TrackingState, details: TrackingDetails = TrackingDetails()) {
        composeRule.setContent {
            TrackingSheetContent(state, details, listOf(60, 120, 240), actions, onDone = { calls += "done" })
        }
    }

    @Test
    fun idleOffersStartOptions() {
        show(TrackingState.IDLE)
        composeRule.onNodeWithText("Until I stop").assertIsDisplayed()
        composeRule.onNodeWithText("Until end of day").assertIsDisplayed()
        composeRule.onNodeWithText("For 4 hours").assertIsDisplayed()

        composeRule.onNodeWithText("For 2 hours").performClick()
        assertThat(calls).containsExactly("start:${ManualOverrideEndMode.DURATION}:120", "done").inOrder()
    }

    @Test
    fun trackingOffersPausesAndStop() {
        show(TrackingState.TRACKING)
        composeRule.onNodeWithText("Pause 30 min").assertIsDisplayed()
        composeRule.onNodeWithText("Pause until next window").assertIsDisplayed()
        composeRule.onNodeWithText("Pause 1 h").performClick()
        composeRule.onNodeWithText("Stop").performClick()
        assertThat(calls).containsExactly("pause:${PauseKind.PAUSE_1H}", "done", "stop", "done").inOrder()
    }

    @Test
    fun pausedOffersResume() {
        show(TrackingState.PAUSED)
        composeRule.onNodeWithText("Paused until you resume").assertIsDisplayed()
        composeRule.onNodeWithText("Resume now").performClick()
        assertThat(calls).containsExactly("resume", "done").inOrder()
    }

    @Test
    fun attentionShowsCauseAndFixAction() {
        show(TrackingState.ATTENTION, TrackingDetails(attentionCause = AttentionCause.LOCATION_OFF))
        composeRule.onNodeWithText("Location is turned off on this phone.").assertIsDisplayed()
        composeRule.onNodeWithText("Open location settings").performClick()
        assertThat(calls).containsExactly("location", "done").inOrder()
    }

    @Test
    fun permissionCauseUsesThePermissionsCallback() {
        show(TrackingState.ATTENTION, TrackingDetails(attentionCause = AttentionCause.NO_FINE_LOCATION))
        composeRule.onNodeWithText("Fix permissions").performClick()
        assertThat(calls).containsExactly("permissions", "done").inOrder()
    }

    @Test
    fun rowsMeetTheTouchTargetMinimum() {
        show(TrackingState.TRACKING)
        composeRule.onNodeWithText("Stop").assertHeightIsAtLeast(48.dp)
    }
}
