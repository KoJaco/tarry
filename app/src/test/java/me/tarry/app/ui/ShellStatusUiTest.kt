package me.tarry.app.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import me.tarry.app.R
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.shell.TarryBottomBar
import me.tarry.app.ui.shell.UndoSnackbarEffect
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.core.data.repository.Snapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShellStatusUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun timelineItemHasNoBadgeOrNeedsReviewDescription() {
        composeRule.setContent {
            TarryTheme { TarryBottomBar(TrackingState.IDLE, true, false, {}, {}, {}) }
        }
        composeRule.onNode(hasText("Timeline") and isSelectable()).assertIsDisplayed()
        composeRule.onNodeWithTag("bottomBar.badge", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("need review", substring = true)).assertDoesNotExist()
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("needs review", substring = true)).assertDoesNotExist()
    }

    private val snapshot = Snapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun showSnackbar(events: MutableSharedFlow<UndoEvent>, onUndo: (UndoEvent) -> Unit) {
        composeRule.setContent {
            TarryTheme {
                val host = androidx.compose.runtime.remember { SnackbarHostState() }
                UndoSnackbarEffect(events, host, onUndo)
                SnackbarHost(host)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun snackbarShowsTheMessageWithUndo_andUndoCallsBack() {
        val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 1)
        val undone = mutableListOf<UndoEvent>()
        showSnackbar(events) { undone += it }
        val event = UndoEvent(R.string.nav_settings, snapshot)
        events.tryEmit(event)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertThat(undone).containsExactly(event)
    }

    @Test
    fun snackbarIsHeldForEightSecondsThenDisappearsWithoutUndoing() {
        val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 1)
        val undone = mutableListOf<UndoEvent>()
        showSnackbar(events) { undone += it }
        events.tryEmit(UndoEvent(R.string.nav_settings, snapshot))
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(7_000)
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
        assertThat(undone).isEmpty()
    }

    @Test
    fun aNewerEventReplacesTheCurrentSnackbar() {
        val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 1)
        showSnackbar(events) {}
        events.tryEmit(UndoEvent(R.string.nav_settings, snapshot))
        composeRule.waitForIdle()
        events.tryEmit(UndoEvent(R.string.nav_timeline, snapshot))
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("Timeline").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
    }
}
