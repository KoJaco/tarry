package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.access.AccessRow
import me.tarry.app.ui.access.TrialEndCard
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings access row and the Timeline trial card (phase 8b.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccessRowsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun settings(access: AccessRow?, onOpen: () -> Unit = {}) =
        composeRule.setContent { TarryTheme { SettingsContent(Settings(), access = access, onOpenPaywall = onOpen) } }

    private fun assertRow(access: AccessRow, text: String) {
        settings(access)
        composeRule.onNodeWithContentDescription("Tarry").assertExists()
        composeRule.onNodeWithText(text).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun trialRowCountsDays() = assertRow(AccessRow.Trial(4), "Free trial · 4 days left")

    @Test
    fun trialRowIsSingularForOneDay() = assertRow(AccessRow.Trial(1), "Free trial · 1 day left")

    @Test
    fun trialEndedRow() = assertRow(AccessRow.TrialEnded, "Trial ended · Unlock Tarry")

    @Test
    fun pendingRow() = assertRow(AccessRow.Pending, "Payment pending")

    @Test
    fun ownedRow() = assertRow(AccessRow.Owned, "Unlocked")

    @Test
    fun revokedRow() = assertRow(AccessRow.Revoked, "Purchase refunded · Unlock Tarry")

    @Test
    fun theRowOpensThePaywall() {
        var opened = 0
        settings(AccessRow.TrialEnded) { opened++ }
        composeRule.onNodeWithText("Trial ended · Unlock Tarry").performScrollTo().performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun theRowIsHiddenWhileTheTrialHasNotStarted() {
        settings(null)
        composeRule.onNodeWithText("Free trial", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Unlocked").assertDoesNotExist()
        composeRule.onNodeWithText("Unlock Tarry", substring = true).assertDoesNotExist()
    }

    @Test
    fun theTrialCardOffersUnlockAndDismiss() {
        var unlocked = 0
        var dismissed = 0
        composeRule.setContent { TarryTheme(darkTheme = true) { TrialEndCard({ unlocked++ }, { dismissed++ }) } }
        composeRule.onNodeWithText("Trial ends tomorrow").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock Tarry to keep tracking after your free trial.").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithContentDescription("Dismiss").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(unlocked).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun theTimelineShowsTheCardOnlyWhenAsked() {
        val shown = true
        composeRule.setContent {
            TarryTheme {
                me.tarry.app.ui.timeline.TimelineContent(
                    date = java.time.LocalDate.of(2026, 1, 1),
                    items = emptyList(),
                    onPreviousDay = {},
                    onNextDay = {},
                    showTrialCard = shown,
                )
            }
        }
        composeRule.onNodeWithTag("trial_card").assertExists()
    }

    @Test
    fun theTimelineHidesTheCardByDefault() {
        composeRule.setContent {
            TarryTheme {
                me.tarry.app.ui.timeline.TimelineContent(
                    date = java.time.LocalDate.of(2026, 1, 1),
                    items = emptyList(),
                    onPreviousDay = {},
                    onNextDay = {},
                )
            }
        }
        composeRule.onNodeWithTag("trial_card").assertDoesNotExist()
    }
}
