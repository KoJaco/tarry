package me.tarry.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.tarry.app.ui.schedule.ScheduleSummary
import me.tarry.app.ui.schedule.WeekScheduleSummary
import me.tarry.app.ui.schedule.summarize
import me.tarry.core.data.model.Schedule
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val selected = mutableListOf<Int>()
    private val themes = mutableListOf<ThemeMode>()

    private fun show(radius: Int = 100, theme: ThemeMode = ThemeMode.SYSTEM) = composeRule.setContent {
        SettingsContent(
            Settings(stayGeofenceRadiusM = radius, theme = theme),
            onStayRadiusSelected = { selected += it },
            onThemeSelected = { themes += it },
        )
    }

    @Test
    fun stopRadiusRowShowsCurrentValueAndIsATallEnoughTarget() {
        show(radius = 150)
        composeRule.onNodeWithText("Stop radius").assertExists()
        composeRule.onNodeWithText("150 m").assertExists()
        composeRule.onNodeWithText("Stop radius").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun labelsGroupHasJobsAndTagsRowsThatOpenTheirScreens() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            SettingsContent(
                Settings(),
                onOpenJobs = { opened += "jobs" },
                onOpenTags = { opened += "tags" },
                onOpenPlaces = { opened += "places" },
            )
        }

        composeRule.onNodeWithText("Labels", ignoreCase = true).assertExists()
        composeRule.onNodeWithText("Jobs").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("Tags").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("Places").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

        assertThat(opened).containsExactly("jobs", "tags", "places").inOrder()
    }

    @Test
    fun scheduleRowIsFirstInTrackingAndShowsThisWeeksSchedule() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            SettingsContent(
                Settings(),
                schedule = WeekScheduleSummary(summarize(Schedule.MON_FRI_7_TO_17), hasOverride = false),
                onOpenSchedule = { opened += "schedule" },
            )
        }

        composeRule.onNodeWithText("Mon–Fri 07:00–17:00").assertExists()
        composeRule.onNodeWithText("Schedule").assertHeightIsAtLeast(48.dp).performClick()

        assertThat(opened).containsExactly("schedule")
    }

    @Test
    fun scheduleRowSaysWhenThisWeekHasItsOwnSchedule() {
        composeRule.setContent {
            SettingsContent(
                Settings(),
                schedule = WeekScheduleSummary(summarize(Schedule.MON_FRI_7_TO_17), hasOverride = true),
            )
        }
        composeRule.onNodeWithText("This week: Mon–Fri 07:00–17:00 (own schedule)").assertExists()
    }

    @Test
    fun scheduleRowShowsOffAndCustom() {
        var summary by mutableStateOf(WeekScheduleSummary(ScheduleSummary.Off, false))
        composeRule.setContent { SettingsContent(Settings(), schedule = summary) }
        composeRule.onNode(hasText("Schedule") and hasText("Off")).assertExists()

        summary = WeekScheduleSummary(ScheduleSummary.Custom, false)
        composeRule.onNode(hasText("Schedule") and hasText("Custom")).assertExists()
    }

    private val radioOption =SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun option(text: String) = hasText(text) and radioOption

    @Test
    fun tappingTheRowListsTheFiveOptionsAndTheExplanation() {
        show()
        composeRule.onNodeWithText("Stop radius").performClick()
        composeRule.onAllNodes(radioOption).assertCountEquals(5)
        for (m in listOf(50, 75, 100, 150, 200)) composeRule.onNode(option("$m m")).assertExists()
        composeRule.onNode(option("100 m")).assertIsSelected()
        composeRule.onNodeWithText("How far you must move", substring = true).assertExists()
    }

    @Test
    fun everyRadiusHasAUseCaseAndTheWarningShowsOnceUnderTheExplanation() {
        show()
        composeRule.onNodeWithText("Stop radius").performClick()
        composeRule.onNodeWithText("Boats at a berth", substring = true).assertExists()
        composeRule.onNodeWithText("Most work: shops", substring = true).assertExists()
        composeRule.onNodeWithText("Farms, quarries", substring = true).assertExists()
        val warning = "Below 100 m: Android geofences are less reliable"
        composeRule.onAllNodesWithText(warning, substring = true).assertCountEquals(1)
        // Not inside any radio option: it belongs to the sheet, not to 50 or 75 m.
        composeRule.onAllNodes(radioOption and hasText(warning, substring = true)).assertCountEquals(0)
    }

    @Test
    fun selectingAnOptionCallsBackAndClosesTheDialog() {
        show()
        composeRule.onNodeWithText("Stop radius").performClick()
        composeRule.onNode(option("200 m")).performScrollTo().performClick()

        assertThat(selected).containsExactly(200)
        composeRule.onNodeWithText("How far you must move", substring = true).assertDoesNotExist()
    }

    @Test
    fun themeRowShowsCurrentValueAndListsThreeOptions() {
        show(theme = ThemeMode.LIGHT)
        composeRule.onNodeWithText("Theme").performScrollTo().performClick()
        composeRule.onAllNodes(radioOption).assertCountEquals(3)
        composeRule.onNode(option("System")).assertExists()
        composeRule.onNode(option("Light")).assertIsSelected()
        composeRule.onNode(option("Dark")).assertExists()
    }

    @Test
    fun selectingDarkCallsBackAndClosesTheSheet() {
        show()
        composeRule.onNodeWithText("Theme").performScrollTo().performClick()
        composeRule.onNode(option("Dark")).performClick()

        assertThat(themes).containsExactly(ThemeMode.DARK)
        composeRule.onAllNodes(radioOption).assertCountEquals(0)
    }
}
