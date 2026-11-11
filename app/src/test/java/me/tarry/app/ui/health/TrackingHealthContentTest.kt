package me.tarry.app.ui.health

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.Preconditions
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.permissions.PermissionSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackingHealthContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fixes = mutableListOf<HealthCheckId>()
    private val calls = mutableListOf<String>()

    private val allOk = buildHealthChecks(
        PermissionSnapshot(
            fineLocation = true, backgroundLocation = true, activityRecognition = true, notifications = true,
            exactAlarms = true, batteryExempt = true,
        ),
        Preconditions(),
    )

    private val someBroken = buildHealthChecks(
        PermissionSnapshot(
            fineLocation = true, backgroundLocation = false, activityRecognition = true, notifications = true,
            exactAlarms = false, batteryExempt = false,
        ),
        Preconditions(locationEnabled = false),
    )

    private fun show(state: TrackingHealthUiState, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TrackingHealthContent(
                    state = state,
                    actions = TrackingHealthActions(
                        onFix = { fixes += it },
                        onTestTracking = { calls += "test" },
                        onFixPermission = { calls += "fixPermission" },
                    ),
                )
            }
        }
    }

    @Test
    fun everyCheckShowsItsTitleAndAWordForItsState() {
        show(TrackingHealthUiState(checks = someBroken))
        for (title in listOf("Precise location", "Location services", "Battery use", "Exact alarms")) {
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        // Three allowed, two not allowed, one optimised; "Off" is the location-services state and the status card.
        composeRule.onAllNodesWithText("Allowed").assertCountEquals(3)
        composeRule.onAllNodesWithText("Not allowed").assertCountEquals(2)
        composeRule.onAllNodesWithText("Optimised").assertCountEquals(1)
        composeRule.onAllNodesWithText("Off").assertCountEquals(2)
    }

    @Test
    fun theChecksStillReadAtFontScale2() {
        show(TrackingHealthUiState(checks = someBroken), fontScale = 2f)
        composeRule.onNodeWithText("Location all the time").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_test_button").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aNotOkRowIsAButtonThatOpensItsFixAndAnOkRowIsNotClickable() {
        show(TrackingHealthUiState(checks = someBroken))
        val background = composeRule.onNode(hasText("Location all the time") and hasClickAction())
        background.performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertThat(background.fetchSemanticsNode().config[SemanticsProperties.Role]).isEqualTo(Role.Button)
        background.performClick()
        assertThat(fixes).containsExactly(HealthCheckId.BACKGROUND_LOCATION)

        composeRule.onNode(hasText("Precise location") and hasClickAction()).assertDoesNotExist()
        composeRule.onNodeWithText("Precise location").assertHasNoClickAction()
    }

    @Test
    fun aNotOkRowIsDescribedWithItsStateAndTheFix() {
        show(TrackingHealthUiState(checks = someBroken))
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Exact alarms, Not allowed. Tap to fix.")).assertExists()
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Precise location, Allowed")).assertExists()
    }

    @Test
    fun theAttentionStatusCardUsesTheSharedCauseSentence() {
        show(TrackingHealthUiState(TrackingState.ATTENTION, AttentionCause.LOCATION_OFF, someBroken))
        composeRule.onNodeWithTag("health_status").assertIsDisplayed()
        composeRule.onNodeWithText("Needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Location is turned off on this phone.").assertIsDisplayed()
    }

    @Test
    fun theStatusCardNamesWorkingOffAndPaused() {
        show(TrackingHealthUiState(TrackingState.TRACKING, checks = allOk))
        composeRule.onNodeWithText("Working").assertIsDisplayed()
    }

    @Test
    fun whyTrackingCanStopListsTheFourReasons() {
        show(TrackingHealthUiState(checks = allOk))
        composeRule.onNodeWithTag("health_why").performScrollTo().assertIsDisplayed()
            .assertTextContains("Battery saver", substring = true)
            .assertTextContains("force stopping", substring = true)
            .assertTextContains("permission", substring = true)
            .assertTextContains("location services", substring = true)
    }

    @Test
    fun testTrackingRunsOnTapAndIsDisabledWithProgressTextWhileRunning() {
        show(TrackingHealthUiState(checks = allOk))
        composeRule.onNodeWithTag("health_test_button").performScrollTo().assertIsEnabled().performClick()
        assertThat(calls).containsExactly("test")
    }

    @Test
    fun theButtonIsDisabledAndSaysItIsLookingWhileRunning() {
        show(TrackingHealthUiState(checks = allOk, test = TestFixState.Running))
        composeRule.onNodeWithTag("health_test_button").performScrollTo().assertIsNotEnabled().assertTextContains("Looking for a fix", substring = true)
        composeRule.onNodeWithTag("health_test_result").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aFixShowsAccuracyAgeAndThatTrackingCanStart() {
        show(TrackingHealthUiState(checks = allOk, test = TestFixState.Success(12, 3, null)))
        composeRule.onNodeWithTag("health_test_result").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Got a fix: ${Char(0x00B1)}12 m, 3 s old").assertIsDisplayed()
        composeRule.onNodeWithText("Tracking can start.").assertIsDisplayed()
    }

    @Test
    fun aFixWithAFailingPreconditionSaysWhatStopsTracking() {
        show(TrackingHealthUiState(checks = someBroken, test = TestFixState.Success(8, 1, AttentionCause.NO_BACKGROUND_LOCATION)))
        composeRule.onNodeWithText("Location access is not set to “Allow all the time”.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tracking can start.").assertDoesNotExist()
    }

    @Test
    fun aTimeoutSaysSoWithTheSecondsAndWhatToDo() {
        show(TrackingHealthUiState(checks = allOk, test = TestFixState.Timeout(30)))
        composeRule.onNodeWithText("No fix in 30 s. Go outside or near a window and try again.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aDeniedResultOffersTheFix() {
        show(TrackingHealthUiState(checks = someBroken, test = TestFixState.Denied))
        composeRule.onNodeWithText("Location permission is off.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_test_fix").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertThat(calls).containsExactly("fixPermission")
    }

    @Test
    fun theResultCardIsALivePoliteRegion() {
        show(TrackingHealthUiState(checks = allOk, test = TestFixState.Timeout(30)))
        val config = composeRule.onNodeWithTag("health_test_result").fetchSemanticsNode().config
        assertThat(config[SemanticsProperties.LiveRegion]).isEqualTo(androidx.compose.ui.semantics.LiveRegionMode.Polite)
    }

    @Test
    fun noResultCardBeforeTheFirstTest() {
        show(TrackingHealthUiState(checks = allOk))
        composeRule.onNodeWithTag("health_test_result").assertDoesNotExist()
    }

    @Test
    fun aResultThatArrivesIsBroughtIntoViewAtFontScale2() {
        var ui by androidx.compose.runtime.mutableStateOf(TrackingHealthUiState(checks = someBroken))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TrackingHealthContent(
                    state = ui,
                    actions = TrackingHealthActions(onFix = {}, onTestTracking = {}, onFixPermission = {}),
                )
            }
        }
        composeRule.onNodeWithTag("health_test_button").performScrollTo()
        composeRule.runOnUiThread { ui = ui.copy(test = TestFixState.Timeout(30)) }
        composeRule.waitForIdle()
        // No performScrollTo: the screen scrolled by itself.
        composeRule.onNodeWithTag("health_test_result").assertIsDisplayed()
    }

    @Test
    fun theResultCardHasAnOkIconForAFixAndAnAttentionIconOtherwise() {
        show(TrackingHealthUiState(checks = allOk, test = TestFixState.Success(12, 3, null)))
        composeRule.onNodeWithTag("health_test_icon_ok").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("health_test_icon_attention").assertDoesNotExist()
    }

    @Test
    fun timeoutAndDeniedShowTheAttentionIcon() {
        show(TrackingHealthUiState(checks = allOk, test = TestFixState.Timeout(30)))
        composeRule.onNodeWithTag("health_test_icon_attention").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theStatusBodyStartsWhereTheStatusTitleStarts() {
        show(TrackingHealthUiState(TrackingState.TRACKING, null, allOk))
        val title = composeRule.onNodeWithText("Working").fetchSemanticsNode().boundsInRoot.left
        val body = composeRule.onNodeWithText("Tarry is recording your location now.", substring = true).fetchSemanticsNode().boundsInRoot.left
        assertThat(body).isWithin(1f).of(title)
    }
}
