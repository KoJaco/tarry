package me.tarry.app.ui.schedule

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.Schedule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScheduleEditorContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = ScheduleEditorActions(
        onModeChange = { calls += "mode:$it" },
        onShiftWeek = { calls += "week:$it" },
        onToggleCell = { day, slot -> calls += "toggle:$day:$slot" },
        onStroke = { calls += "stroke" },
        onWorkWeekPreset = { calls += "preset" },
        onClearAll = { calls += "clear" },
        onSetDayHours = { day, s, e -> calls += "hours:$day:$s:$e" },
        onCopyDay = { day, to -> calls += "copy:$day:${to.sorted()}" },
        onClearDay = { calls += "clearDay:$it" },
        onSave = { calls += "save" },
        onDiscard = { calls += "discard" },
        onUseEveryWeek = { calls += "useEveryWeek" },
        onConfirmPending = { calls += "confirmPending" },
        onCancelPending = { calls += "cancelPending" },
    )

    // 2026-10-26 is a Monday.
    private val currentWeek = LocalDate.of(2026, 10, 26)

    private fun state(
        mode: ScheduleMode = ScheduleMode.TEMPLATE,
        viewedWeek: LocalDate = currentWeek,
        saved: Schedule = Schedule.MON_FRI_7_TO_17,
        draft: Schedule = saved,
        hasOverride: Boolean = false,
        currentWeekHasOverride: Boolean = hasOverride && viewedWeek == currentWeek,
        pending: PendingNavigation? = null,
    ) = ScheduleUiState(
        loaded = true, mode = mode, viewedWeek = viewedWeek, currentWeek = currentWeek, today = currentWeek.plusDays(2),
        saved = saved, draft = draft, hasOverride = hasOverride, currentWeekHasOverride = currentWeekHasOverride, pending = pending,
    )

    private fun show(state: ScheduleUiState) = composeRule.setContent {
        ScheduleEditorContent(state, actions, onClose = { calls += "close" })
    }

    @Test
    fun saveAndDiscardShowOnlyWhenThereAreEdits() {
        show(state())
        composeRule.onAllNodesWithText("Save").assertCountEquals(0)
        composeRule.onAllNodesWithText("Discard").assertCountEquals(0)
    }

    @Test
    fun saveAndDiscardInvokeTheirActionsWhenDirty() {
        show(state(draft = Schedule.EMPTY))

        composeRule.onNodeWithText("Discard").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Save").assertIsDisplayed().performClick()

        assertThat(calls).containsExactly("discard", "save").inOrder()
    }

    @Test
    fun modeSwitchReportsTheChosenMode() {
        show(state())
        composeRule.onNodeWithText("Every week").assertIsSelected()

        composeRule.onNodeWithText("This week only").performClick()

        assertThat(calls).containsExactly("mode:WEEK")
    }

    @Test
    fun weekNavShowsTheRangeAndDisablesPreviousAtTheCurrentWeek() {
        show(state(mode = ScheduleMode.WEEK))

        composeRule.onNodeWithText("Oct 26 – Nov 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Previous week").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Next week").assertIsEnabled().performClick()

        assertThat(calls).containsExactly("week:1")
    }

    @Test
    fun previousWeekIsEnabledAfterTheCurrentWeek() {
        show(state(mode = ScheduleMode.WEEK, viewedWeek = currentWeek.plusWeeks(1)))

        composeRule.onNodeWithContentDescription("Previous week").assertIsEnabled().performClick()

        assertThat(calls).containsExactly("week:-1")
    }

    @Test
    fun overrideBannerAsksForConfirmationBeforeUsingTheEveryWeekSchedule() {
        show(state(mode = ScheduleMode.WEEK, hasOverride = true))
        composeRule.onNodeWithText("This week uses its own schedule").assertIsDisplayed()

        composeRule.onNodeWithText("Use every-week schedule").performClick()
        assertThat(calls).isEmpty()
        composeRule.onNodeWithText("Use the every-week schedule?").assertIsDisplayed()
        // The banner button and the dialog's confirm button share the label.
        composeRule.onAllNodesWithText("Use every-week schedule").assertCountEquals(2)
        composeRule.onAllNodesWithText("Use every-week schedule")[1].performClick()

        assertThat(calls).containsExactly("useEveryWeek")
    }

    @Test
    fun noBannerWithoutAnOverride() {
        show(state(mode = ScheduleMode.WEEK, hasOverride = false))
        composeRule.onAllNodesWithText("This week uses its own schedule").assertCountEquals(0)
    }

    @Test
    fun templateModeNotesThatThisWeekHasItsOwnSchedule() {
        show(state(mode = ScheduleMode.TEMPLATE, currentWeekHasOverride = true))
        composeRule.onNodeWithText("This week has its own schedule", substring = true).assertIsDisplayed()
    }

    @Test
    fun presetsCallBack() {
        show(state())

        composeRule.onNodeWithText("Mon–Fri 07:00–17:00").performClick()
        composeRule.onNodeWithText("Clear").performClick()

        assertThat(calls).containsExactly("preset", "clear").inOrder()
    }

    @Test
    fun pendingNavigationAsksToDiscardAndConfirms() {
        show(state(draft = Schedule.EMPTY, pending = PendingNavigation.ToMode(ScheduleMode.WEEK)))
        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()

        // Behind the dialog the bar's own Discard is first, the dialog's confirm second.
        composeRule.onAllNodesWithText("Discard")[1].performClick()

        assertThat(calls).containsExactly("confirmPending")
    }

    @Test
    fun dayHeaderOpensTheSheetAndSetHoursReportsTheDay() {
        show(state())

        composeRule.onNodeWithContentDescription("Edit Tuesday").performClick()
        composeRule.onNodeWithText("Tuesday").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Start later").performClick()
        // Section header (upper case) and button share the words; the button is the exact-case one.
        composeRule.onNodeWithText("Set hours").performClick()

        assertThat(calls).containsExactly("hours:TUESDAY:15:34")
    }

    @Test
    fun copyToOffersWeekdaysShortcutAndOtherDays() {
        show(state())

        composeRule.onNodeWithContentDescription("Edit Monday").performClick()
        composeRule.onNodeWithText("Weekdays").performScrollTo().performClick()
        composeRule.onNodeWithText("Copy").performScrollTo().performClick()

        val weekdays = listOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        assertThat(calls).containsExactly("copy:MONDAY:$weekdays")
    }

    @Test
    fun clearDayFromTheSheet() {
        show(state())

        composeRule.onNodeWithContentDescription("Edit Friday").performClick()
        composeRule.onNodeWithText("Clear day").performScrollTo().performClick()

        assertThat(calls).containsExactly("clearDay:FRIDAY")
    }
}
