package me.tarry.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import me.tarry.app.access.DebugAccess
import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.access.PaywallScreen
import me.tarry.app.ui.navigation.PaywallRoute
import me.tarry.app.ui.navigation.TimelineRoute
import me.tarry.app.ui.sessions.SessionsActions
import me.tarry.app.ui.sessions.SessionsContent
import me.tarry.app.ui.shell.TarryBottomBar
import me.tarry.app.ui.shell.TrackingToggle
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The trial-ended pill, the placeholder paywall and the debug access switch (phase 8b.1). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xxhdpi")
class AccessUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun pill(dark: Boolean, cause: AttentionCause? = AttentionCause.TRIAL_ENDED) {
        composeRule.setContent {
            TarryTheme(darkTheme = dark) {
                TrackingToggle(state = TrackingState.ATTENTION, onClick = {}, onLongClick = {}, attentionCause = cause)
            }
        }
    }

    private fun assertUnlockPill(dark: Boolean) {
        pill(dark)
        composeRule.onNodeWithText("Unlock").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tracking: trial ended, unlock Tarry").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun trialEndedPillReadsUnlockWithItsDescriptionInLight() = assertUnlockPill(dark = false)

    @Test
    fun trialEndedPillReadsUnlockWithItsDescriptionInDark() = assertUnlockPill(dark = true)

    @Test
    fun otherAttentionCausesKeepTheirPill() {
        pill(dark = false, cause = AttentionCause.LOCATION_OFF)
        composeRule.onNodeWithText("Attention").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Tracking: needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertDoesNotExist()
    }

    @Test
    fun theUnlockPillIsOneLineAtFontScaleTwoInTheBar() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TarryTheme {
                    Box(Modifier.width(360.dp)) {
                        TarryBottomBar(TrackingState.ATTENTION, true, false, {}, {}, {}, attentionCause = AttentionCause.TRIAL_ENDED)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertThat(composeRule.onNodeWithText("Unlock").assertNotTruncated().textLayout().lineCount).isEqualTo(1)
    }

    @Test
    fun theTrialEndedPillTapsThrough() {
        var clicks = 0
        composeRule.setContent {
            TarryTheme {
                TrackingToggle(
                    state = TrackingState.ATTENTION, onClick = { clicks++ }, onLongClick = {},
                    attentionCause = AttentionCause.TRIAL_ENDED,
                )
            }
        }
        composeRule.onNodeWithText("Unlock").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun placeholderPaywallShowsTheTitleThePlaceholderLineAndTheDataPromise() {
        var closed = 0
        composeRule.setContent { TarryTheme { PaywallScreen(onClose = { closed++ }) } }
        composeRule.onNodeWithText("Unlock Tarry").assertIsDisplayed()
        composeRule.onNodeWithText("Purchase arrives in the next update").assertIsDisplayed()
        composeRule.onNodeWithText("You can still view, edit, export, back up and delete everything.").assertIsDisplayed()
        composeRule.onNodeWithText("Close").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun placeholderPaywallFitsAtFontScaleTwoInDark() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TarryTheme(darkTheme = true) { PaywallScreen(onClose = {}) }
            }
        }
        composeRule.onNodeWithText("Unlock Tarry").assertIsDisplayed()
        composeRule.onNodeWithText("You can still view, edit, export, back up and delete everything.").assertIsDisplayed()
    }

    @Test
    fun paywallRouteIsFullScreenWithoutTheBottomBar() {
        val backStack = mutableStateListOf<NavKey>(TimelineRoute, PaywallRoute)
        composeRule.setContent {
            TarryTheme {
                TarryAppContent(
                    trackingState = TrackingState.ATTENTION,
                    attentionCause = AttentionCause.TRIAL_ENDED,
                    backStack = backStack,
                    onNavigate = {},
                    onBack = {},
                    onToggleClick = {},
                    settingsContent = {},
                    paywallContent = { PaywallScreen(onClose = {}) },
                )
            }
        }
        composeRule.onNodeWithText("Purchase arrives in the next update").assertIsDisplayed()
        composeRule.onNodeWithText("Unlock").assertDoesNotExist()
    }

    @Test
    fun theDebugAccessRowShowsTheValueAndCyclesOnTap() {
        var taps = 0
        val actions = SessionsActions({}, {}, {}, onCycleDebugAccess = { taps++ })
        composeRule.setContent {
            TarryTheme { SessionsContent(emptyList(), true, null, actions, debugAccess = DebugAccess.TRIAL_1_DAY) }
        }
        composeRule.onNodeWithTag("sessions_access_debug").assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("Access state: Trial (1 day left)").assertIsDisplayed()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun theDebugAccessRowIsHiddenWithoutAValue() {
        composeRule.setContent { TarryTheme { SessionsContent(emptyList(), false, null, SessionsActions({}, {}, {})) } }
        composeRule.onNodeWithTag("sessions_access_debug").assertDoesNotExist()
    }
}
