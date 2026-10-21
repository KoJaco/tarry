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
import me.tarry.app.ui.shell.BottomBarTags
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

    private fun showBar(badge: Int) {
        composeRule.setContent {
            TarryTheme { TarryBottomBar(TrackingState.IDLE, true, false, {}, {}, {}, timelineBadge = badge) }
        }
    }

    private fun badge() = composeRule.onNodeWithTag(BottomBarTags.Badge, useUnmergedTree = true)

    @Test
    fun badgeShowsCountAndNamesItInTheTimelineItemDescription() {
        showBar(3)
        badge().assertIsDisplayed()
        composeRule.onNode(hasText("Timeline") and isSelectable()).assertIsDisplayed()
        composeRule.onNodeWithTag(BottomBarTags.TimelineSlot, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Timeline, 3 visits need review")).assertIsDisplayed()
    }

    @Test
    fun badgeUsesTheSingularForOne() {
        showBar(1)
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Timeline, 1 visit needs review")).assertIsDisplayed()
    }

    @Test
    fun badgeIsHiddenAtZero() {
        showBar(0)
        badge().assertDoesNotExist()
        composeRule.onNode(hasText("Timeline") and isSelectable()).assertIsDisplayed()
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Timeline, 0 visits need review", substring = true)).assertDoesNotExist()
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
