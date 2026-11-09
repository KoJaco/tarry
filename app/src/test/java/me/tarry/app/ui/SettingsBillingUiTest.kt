package me.tarry.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.core.data.settings.RoundingMode
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsBillingUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val increments = mutableListOf<Int>()
    private val modes = mutableListOf<RoundingMode>()
    private val minimums = mutableListOf<Int>()
    private val perDay = mutableListOf<Boolean>()

    private val radioOption = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private fun option(text: String) = hasText(text) and radioOption

    private fun show(settings: Settings) = composeRule.setContent {
        SettingsContent(
            settings,
            onRoundingIncrementSelected = { increments += it },
            onRoundingModeSelected = { modes += it },
            onMinBillableSelected = { minimums += it },
            onRoundDailyTotalChange = { perDay += it },
        )
    }

    @Test
    fun roundingSheetListsFourOptionsWithTheCurrentOneSelectedAndCallsBack() {
        show(Settings(roundingIncrementMin = 15))
        composeRule.onNodeWithText("Rounding").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

        composeRule.onAllNodes(radioOption).assertCountEquals(4)
        composeRule.onNode(option("Off")).assertExists()
        composeRule.onNode(option("6 min")).assertExists()
        composeRule.onNode(option("15 min")).assertIsSelected()
        composeRule.onNode(option("30 min")).assertExists()
        composeRule.onNodeWithText("Billed time is rounded to this step.").assertExists()

        composeRule.onNode(option("30 min")).performClick()

        assertThat(increments).containsExactly(30)
        composeRule.onAllNodes(radioOption).assertCountEquals(0)
    }

    @Test
    fun roundToIsDisabledWithASubtitleWhileRoundingIsOff() {
        show(Settings(roundingIncrementMin = 0))

        composeRule.onNode(hasText("Round to") and hasText("Rounding is off")).performScrollTo().assertIsNotEnabled().performClick()
        composeRule.onAllNodes(radioOption).assertCountEquals(0)
        assertThat(modes).isEmpty()
    }

    @Test
    fun roundToOpensWhenRoundingIsOnAndShowsNoEnumNames() {
        show(Settings(roundingIncrementMin = 15, roundingMode = RoundingMode.UP))

        composeRule.onNode(hasText("Round to") and hasText("Always up")).performScrollTo().assertIsEnabled().performClick()
        composeRule.onAllNodes(radioOption).assertCountEquals(2)
        composeRule.onNode(option("Nearest")).assertExists()
        composeRule.onNode(option("Always up")).assertIsSelected()
        composeRule.onAllNodesWithText("UP", substring = false).assertCountEquals(0)
        composeRule.onAllNodesWithText("NEAREST", substring = false).assertCountEquals(0)

        composeRule.onNode(option("Nearest")).performClick()

        assertThat(modes).containsExactly(RoundingMode.NEAREST)
    }

    @Test
    fun minimumSheetOffersOffAndThreeStepsAndCallsBack() {
        show(Settings(minBillableMinutes = 15))
        composeRule.onNodeWithText("Minimum billable time").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

        composeRule.onAllNodes(radioOption).assertCountEquals(4)
        composeRule.onNode(option("Off")).assertExists()
        composeRule.onNode(option("15 min")).assertIsSelected()
        composeRule.onNode(option("30 min")).assertExists()
        composeRule.onNode(option("60 min")).assertExists()

        composeRule.onNode(option("Off")).performClick()

        assertThat(minimums).containsExactly(0)
    }

    @Test
    fun aStoredMinimumOutsideTheListStillShowsAndIsNotRewritten() {
        show(Settings(minBillableMinutes = 20))
        composeRule.onNode(hasText("Minimum billable time") and hasText("20 min")).performScrollTo().performClick()

        composeRule.onAllNodes(radioOption).assertCountEquals(5)
        composeRule.onNode(option("20 min")).assertIsSelected()
        assertThat(minimums).isEmpty()
    }

    @Test
    fun perDaySwitchCallsBack() {
        show(Settings(roundDailyTotalPerJob = false, roundingIncrementMin = 15))

        composeRule.onNodeWithText("Add up each job's blocks for the day before dropping seconds, rounding and the minimum.", substring = true)
            .performScrollTo().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Round per day, per job").performScrollTo().performClick()

        assertThat(perDay).containsExactly(true)
    }

    @Test
    fun perDaySwitchStaysEnabled_evenWhenRoundingAndMinimumAreBothOff() {
        show(Settings(roundDailyTotalPerJob = false, roundingIncrementMin = 0, minBillableMinutes = 0))

        composeRule.onNodeWithText("Round per day, per job").performScrollTo().assertIsEnabled().performClick()
        assertThat(perDay).containsExactly(true)
    }

    @Test
    fun perDaySwitchIsEnabled_whenOnlyTheMinimumOrOnlyRoundingIsOn() {
        show(Settings(roundingIncrementMin = 0, minBillableMinutes = 30))
        composeRule.onNodeWithText("Round per day, per job").performScrollTo().assertIsEnabled()
    }

    @Test
    fun perDaySwitchIsEnabled_whenOnlyRoundingIsOn() {
        show(Settings(roundingIncrementMin = 15, minBillableMinutes = 0))
        composeRule.onNodeWithText("Round per day, per job").performScrollTo().assertIsEnabled()
    }

    private fun top(text: String, exact: Boolean = true): Float =
        composeRule.onNode(hasText(text, substring = !exact)).fetchSemanticsNode().positionInRoot.y

    @Test
    fun sectionsRunBillingThenRemindersThenData_withTheRowsMovedAsPlanned() {
        show(Settings(roundingIncrementMin = 15, rawPingRetentionDays = 30))

        val order = listOf(
            "BILLING", "Rounding", "Round to", "Minimum billable time", "Round per day, per job",
            "REMINDERS", "Review reminder", "Review reminder time",
            "DATA", "Back up now", "Restore from backup", "Raw location retention",
        ).map { top(it) }
        assertThat(order).isInOrder()
    }

    @Test
    fun billingGroupHoldsNoReminderOrRetentionRows() {
        show(Settings(roundingIncrementMin = 15))
        // The Billing header and the Reminders header are separated only by the four billing rows.
        val rows = listOf("Rounding", "Round to", "Minimum billable time", "Round per day, per job").map { top(it) }
        assertThat(rows).isInOrder()
        assertThat(top("Review reminder")).isGreaterThan(top("REMINDERS"))
        assertThat(top("Raw location retention")).isGreaterThan(top("DATA"))
        assertThat(top("Raw location retention")).isGreaterThan(top("Restore from backup"))
    }

    @Test
    fun movedRowsShowTheirValues() {
        show(Settings(reviewReminderEnabled = true, reviewReminderMinuteOfDay = 20 * 60 + 5, rawPingRetentionDays = null))
        composeRule.onNode(hasText("Review reminder") and hasText("On")).assertExists()
        composeRule.onNode(hasText("Review reminder time") and hasText("20:05")).assertExists()
        composeRule.onNode(hasText("Raw location retention") and hasText("Forever")).assertExists()
        composeRule.onNode(hasText("Review reminder time")).assertHasNoClickAction()
    }
}
