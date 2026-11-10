package me.tarry.app.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.permissions.PermissionSnapshot
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.PermissionsActions
import me.tarry.app.ui.permissions.PermissionsUiState
import me.tarry.app.ui.permissions.buildPermissionsUiState
import me.tarry.core.data.model.Schedule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnboardingContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val granted = mutableListOf<PermissionStep>()
    private val calls = mutableListOf<String>()
    private var saved: Schedule? = null
    private var disclosure = false

    private fun actionsFor(advance: () -> Unit = { calls += "next" }, back: () -> Unit = { calls += "back" }) = OnboardingActions(
        onNext = advance,
        onBack = back,
        onToggleCell = { _, _ -> calls += "toggle" },
        onStroke = { calls += "stroke" },
        onSaveHours = { saved = Schedule.MON_FRI_7_TO_17; calls += "save" },
        onNotNow = { calls += "notNow" },
        onOpenTarry = { calls += "open" },
    )

    private val permissionActions = PermissionsActions(
        onGrant = { granted += it },
        onSkip = {},
        onConfirmBackgroundDisclosure = { calls += "confirmDisclosure" },
        onDismissBackgroundDisclosure = { calls += "dismissDisclosure" },
        onDone = {},
    )

    private fun permissionsState(showDisclosure: Boolean = false): PermissionsUiState =
        buildPermissionsUiState(PermissionSnapshot(), emptySet(), showDisclosure)

    private var flow by mutableStateOf(OnboardingUiState(onboardingPages(emptyList())))

    /** Renders the flow from [flow], so the pages can be walked through by tapping (next and back move its index). */
    private fun setFlow(
        steps: List<PermissionStep> = listOf(PermissionStep.LOCATION, PermissionStep.BACKGROUND_LOCATION),
        outcome: DoneOutcome? = DoneOutcome.TrackingNow,
        fontScale: Float = 1f,
        startIndex: Int = 0,
    ) {
        flow = OnboardingUiState(onboardingPages(steps), index = startIndex, outcome = outcome)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                OnboardingContent(
                    state = flow,
                    permissions = permissionsState(disclosure),
                    permissionActions = permissionActions,
                    actions = actionsFor(
                        advance = { flow = flow.copy(index = flow.index + 1) },
                        back = { flow = flow.copy(index = flow.index - 1) },
                    ),
                )
            }
        }
    }

    private fun primary() = composeRule.onNodeWithTag("onboarding_primary")

    private fun assertTitle(text: String) = composeRule.onNodeWithTag("onboarding_title").assertTextContains(text)

    @Test
    fun welcomeShowsTheHeadlineThePrivacyPromiseAndGetStarted() {
        setFlow()
        assertTitle("Your work day, already written down.")
        composeRule.onNodeWithTag("onboarding_privacy").assertIsDisplayed()
        composeRule.onNodeWithText("No account. No cloud. Your location stays on your phone unless you export it.").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_step").assertTextContains("Step 1 of 5")
        primary().assertTextContains("Get started").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theFlowWalksWelcomePermissionsScheduleAndDoneInOrder() {
        setFlow()
        primary().performClick()
        assertTitle("Precise location")
        composeRule.onNodeWithTag("onboarding_step").assertTextContains("Step 2 of 5")

        composeRule.onNodeWithTag("onboarding_not_now").assertIsDisplayed() // location cannot be skipped
        composeRule.onNodeWithTag("onboarding_skip").assertDoesNotExist()
    }

    @Test
    fun allowAsksForTheStepOnThePage() {
        setFlow(startIndex = 1)
        primary().assertTextContains("Allow").performClick()
        assertThat(granted).containsExactly(PermissionStep.LOCATION)
    }

    @Test
    fun skipAdvancesPastASkippableStepAndIsDescribedByItsName() {
        setFlow(startIndex = 2)
        assertTitle("Location all the time")
        composeRule.onNodeWithTag("onboarding_skip").performClick()
        // The next page is the schedule.
        assertTitle("When do you work?")
        composeRule.onNodeWithTag("onboarding_not_now").assertDoesNotExist()
    }

    @Test
    fun notNowOnTheLocationPageFinishesOnboarding() {
        setFlow(startIndex = 1)
        composeRule.onNodeWithTag("onboarding_not_now").performClick()
        assertThat(calls).containsExactly("notNow")
    }

    @Test
    fun anIntentStepOffersOpenSettings() {
        setFlow(steps = listOf(PermissionStep.EXACT_ALARMS), startIndex = 1)
        primary().assertTextContains("Open settings").performClick()
        assertThat(granted).containsExactly(PermissionStep.EXACT_ALARMS)
    }

    @Test
    fun theBackgroundDisclosureShowsOverThePageWhenRequested() {
        disclosure = true
        setFlow(startIndex = 2)
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun theSchedulePageSavesTheUntouchedPresetThroughTheSaver() {
        setFlow(steps = emptyList(), startIndex = 1)
        assertTitle("When do you work?")
        composeRule.onNodeWithTag("onboarding_grid").assertIsDisplayed()
        primary().assertTextContains("Save hours").assertIsEnabled().performClick()
        assertThat(calls).containsExactly("save")
        assertThat(saved).isEqualTo(Schedule.MON_FRI_7_TO_17)
    }

    @Test
    fun theSaveButtonIsDisabledWhileSaving() {
        composeRule.setContent {
            OnboardingContent(
                state = OnboardingUiState(onboardingPages(emptyList()), index = 1, saving = true),
                permissions = permissionsState(),
                permissionActions = permissionActions,
                actions = actionsFor(),
            )
        }
        primary().assertIsNotEnabled()
    }

    @Test
    fun aFailedSaveIsSaidOnThePage() {
        composeRule.setContent {
            OnboardingContent(
                state = OnboardingUiState(onboardingPages(emptyList()), index = 1, saveFailed = true),
                permissions = permissionsState(),
                permissionActions = permissionActions,
                actions = actionsFor(),
            )
        }
        composeRule.onNodeWithTag("onboarding_save_failed").assertIsDisplayed()
    }

    @Test
    fun doneSaysTrackingNowInsideAWindow() {
        setFlow(steps = emptyList(), outcome = DoneOutcome.TrackingNow, startIndex = 2)
        assertTitle("Tracking now")
        primary().assertTextContains("Open Tarry").performClick()
        assertThat(calls).containsExactly("open")
    }

    @Test
    fun doneNamesTheNextWindowOutsideOne() {
        // 2026-10-26 07:00 in Sydney is a Monday.
        val monday7am = java.time.ZonedDateTime.of(2026, 10, 26, 7, 0, 0, 0, java.time.ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli()
        setFlow(steps = emptyList(), outcome = DoneOutcome.Next(monday7am, "Australia/Sydney"), startIndex = 2)
        composeRule.onNodeWithTag("onboarding_title").assertTextContains("Next: Mon", substring = true)
    }

    @Test
    fun doneWithNoHoursSaysSo() {
        setFlow(steps = emptyList(), outcome = DoneOutcome.NoHours, startIndex = 2)
        assertTitle("No hours set")
    }

    @Test
    fun backGoesToThePreviousPageAndNotPastTheFirst() {
        setFlow(startIndex = 0)
        primary().performClick()
        assertTitle("Precise location")

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertTitle("Your work day, already written down.")
    }

    @Test
    fun theNextWindowLabelUsesTheZoneOfTheStart() {
        val utcMidnightMonday = java.time.ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val label = nextWindowLabel(utcMidnightMonday, java.time.ZoneId.of("Australia/Sydney"), java.util.Locale.ENGLISH) { "11:00 am" }
        val nbsp = Char(0x00A0)
        assertThat(label).isEqualTo("Mon${nbsp}11:00${nbsp}am")
        val label2 = nextWindowLabel(utcMidnightMonday, java.time.ZoneId.of("America/Los_Angeles"), java.util.Locale.ENGLISH) { "5:00 pm" }
        assertThat(label2.startsWith("Sun")).isTrue()
    }

    @Test
    fun everyPageKeepsItsPrimaryButtonReachableAtFontScale2() {
        setFlow(fontScale = 2f)
        for (index in 0 until flow.pages.size) {
            composeRule.runOnUiThread { flow = flow.copy(index = index) }
            composeRule.waitForIdle()
            primary().assertIsDisplayed()
        }
    }
}
