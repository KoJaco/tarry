package me.tarry.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.access.AccessState
import me.tarry.app.access.OfferState
import me.tarry.app.ui.access.PaywallContent
import me.tarry.app.ui.access.PaywallUi
import me.tarry.app.ui.access.RestoreResult
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real purchase screen in each of its states (phase 8b.2). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xxhdpi")
class PaywallUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val price = OfferState.Available("A$29.00")
    private var unlocks = 0
    private var restores = 0
    private var retries = 0
    private var closes = 0

    private fun show(ui: PaywallUi, dark: Boolean = false, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TarryTheme(darkTheme = dark) { Frame(ui) }
            }
        }
    }

    @Composable
    private fun Frame(ui: PaywallUi) {
        Box(Modifier.fillMaxSize()) {
            PaywallContent(ui, { unlocks++ }, { restores++ }, { retries++ }, { closes++ })
        }
    }

    private val trial = PaywallUi(AccessState.Trial(1L), price, daysLeft = 3)

    private fun assertTrialCopy() {
        composeRule.onNodeWithText("Unlock Tarry").assertIsDisplayed()
        composeRule.onNodeWithText("One price. No subscription.").assertIsDisplayed()
        composeRule.onNodeWithText("Free trial · 3 days left").assertIsDisplayed()
        composeRule.onNodeWithText("Unlimited tracking and history").assertIsDisplayed()
        composeRule.onNodeWithText("Jobs, summary and exports").assertIsDisplayed()
        composeRule.onNodeWithText("Backups and restore").assertIsDisplayed()
        composeRule.onNodeWithText("All future updates to the core app").assertIsDisplayed()
        composeRule.onNodeWithText("A$29.00 once. Yours for life, on your Google account.").assertIsDisplayed()
        composeRule.onNodeWithText("Google Play handles your purchase. Your data stays on this phone.").assertIsDisplayed()
        composeRule.onNodeWithText("You can still view, edit, export, back up and delete everything.").assertDoesNotExist()
    }

    @Test
    fun trialInLight() {
        show(trial)
        assertTrialCopy()
    }

    @Test
    fun trialInDark() {
        show(trial, dark = true)
        assertTrialCopy()
    }

    @Test
    fun oneDayLeftIsSingular() {
        show(PaywallUi(AccessState.Trial(1L), price, daysLeft = 1))
        composeRule.onNodeWithText("Free trial · 1 day left").assertIsDisplayed()
    }

    @Test
    fun theUnlockButtonNamesThePriceAndCallsBack() {
        show(trial)
        composeRule.onNodeWithText("Unlock for A$29.00").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertThat(unlocks).isEqualTo(1)
    }

    @Test
    fun theCloseButtonIsLabelledAndLargeEnough() {
        show(trial)
        composeRule.onNodeWithContentDescription("Close").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(closes).isEqualTo(1)
    }

    @Test
    fun trialEndedSaysSoAndKeepsTheDataPromise() {
        show(PaywallUi(AccessState.TrialEnded, price))
        composeRule.onNodeWithText("Your free trial has ended").assertIsDisplayed()
        composeRule.onNodeWithText("You can still view, edit, export, back up and delete everything.").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock for A$29.00").assertIsDisplayed()
    }

    @Test
    fun revokedSaysRefundedOrCancelled() {
        show(PaywallUi(AccessState.Revoked(1L), price))
        composeRule.onNodeWithText("Your purchase was refunded or cancelled").assertIsDisplayed()
        composeRule.onNodeWithText("You can still view, edit, export, back up and delete everything.").assertIsDisplayed()
    }

    @Test
    fun pendingHidesThePrimaryButton() {
        show(PaywallUi(AccessState.Pending(null), price), dark = true)
        composeRule.onNodeWithText("Payment pending. Tracking unlocks when Google Play confirms it.").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock for A$29.00").assertDoesNotExist()
        composeRule.onNodeWithText("Unlock").assertDoesNotExist()
    }

    @Test
    fun ownedThanksAndDoneCloses() {
        show(PaywallUi(AccessState.Owned, price))
        composeRule.onNodeWithText("Tarry is unlocked. Thank you.").assertIsDisplayed()
        composeRule.onNodeWithText("Restore purchase").assertDoesNotExist()
        composeRule.onNodeWithText("Done").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(closes).isEqualTo(1)
    }

    @Test
    fun unavailableOffersTryAgainAndDisablesThePrimary() {
        show(PaywallUi(AccessState.TrialEnded, OfferState.Unavailable))
        composeRule.onNodeWithText("Can't reach Google Play right now").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertIsNotEnabled()
        composeRule.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun loadingDisablesThePrimary() {
        show(PaywallUi(AccessState.Trial(1L), OfferState.Loading, daysLeft = 2))
        composeRule.onNodeWithText("Checking the price").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertIsNotEnabled()
    }

    @Test
    fun restoreCallsBackAndShowsItsResult() {
        show(trial.copy(restore = RestoreResult.NotFound))
        composeRule.onNodeWithText("No purchase found on this Google account").assertIsDisplayed()
        composeRule.onNodeWithText("Restore purchase").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(restores).isEqualTo(1)
    }

    @Test
    fun restoreFoundMessage() {
        show(trial.copy(restore = RestoreResult.Found))
        composeRule.onNodeWithText("Purchase restored").assertIsDisplayed()
    }

    @Test
    fun aFailedLaunchShowsTheError() {
        show(trial.copy(launchFailed = true))
        composeRule.onNodeWithText("Something went wrong. Try again.").assertIsDisplayed()
    }

    @Test
    fun theUnavailableRestoreResultSaysGooglePlayCannotBeReached() {
        show(trial.copy(restore = RestoreResult.Unavailable))
        composeRule.onNodeWithText("Can't reach Google Play right now").assertIsDisplayed()
        composeRule.onNodeWithText("No purchase found on this Google account").assertDoesNotExist()
    }

    @Test
    fun theUnlockButtonIsOffWhileAPurchaseIsStarting() {
        show(trial.copy(launching = true))
        composeRule.onNodeWithText("Unlock for A$29.00").assertIsNotEnabled()
    }

    @Test
    fun thePrimaryButtonIsNotClippedAtFontScaleTwoInLight() {
        show(PaywallUi(AccessState.TrialEnded, price), fontScale = 2f)
        composeRule.onNodeWithText("Unlock for A$29.00").assertIsDisplayed().assertNotTruncated()
    }

    @Test
    fun thePrimaryButtonIsNotClippedAtFontScaleTwoInDark() {
        show(PaywallUi(AccessState.Trial(1L), price, daysLeft = 3), dark = true, fontScale = 2f)
        composeRule.onNodeWithText("Unlock for A$29.00").assertIsDisplayed().assertNotTruncated()
        composeRule.onNodeWithText("Restore purchase").assertIsDisplayed()
    }
}
