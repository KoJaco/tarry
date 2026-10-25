package me.tarry.app.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.Schedule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScheduleGridTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var schedule by mutableStateOf(Schedule.EMPTY)
    private val toggles = mutableListOf<Pair<DayOfWeek, Int>>()
    private val headerClicks = mutableListOf<DayOfWeek>()

    private lateinit var listState: LazyListState

    private fun show(initial: Schedule, highlight: DayOfWeek? = null) {
        schedule = initial
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = ScheduleGridInitialSlot)
            Box(Modifier.width(360.dp).height(600.dp)) {
                ScheduleGrid(
                    schedule = schedule,
                    onToggle = { day, slot ->
                        toggles += day to slot
                        schedule = toggle(schedule, day, slot)
                    },
                    onStroke = { schedule = it },
                    onDayHeaderClick = { headerClicks += it },
                    highlightDay = highlight,
                    listState = listState,
                )
            }
        }
    }

    private fun cell(text: String) = composeRule.onNodeWithContentDescription(text)

    /** Centre of a Monday-based column and visible slot (06:00 = slot 12 is at the top), in the grid's pixels. */
    private fun androidx.compose.ui.test.TouchInjectionScope.centre(dayIndex: Int, slot: Int): Offset {
        val cellWidth = (360.dp.toPx() - 28.dp.toPx()) / 7
        return Offset(28.dp.toPx() + cellWidth * (dayIndex + 0.5f), 48.dp.toPx() * (slot - ScheduleGridInitialSlot + 0.5f))
    }

    @Test
    fun tappingACellTogglesIt() {
        show(Schedule.EMPTY)

        cell("Monday 07:00 to 07:30").performClick()

        assertThat(toggles).containsExactly(DayOfWeek.MONDAY to 14)
        assertThat(schedule.isActive(DayOfWeek.MONDAY, 14)).isTrue()
    }

    @Test
    fun cellsSpeakTheirTimeRangeAndState() {
        show(Schedule.MON_FRI_7_TO_17)

        cell("Monday 07:00 to 07:30").assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        cell("Saturday 07:00 to 07:30").assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        cell("Monday 08:00 to 08:30").assertIsOn()
        cell("Monday 06:30 to 07:00").assertIsOff()
    }

    @Test
    fun longPressThenDragPaintsARunOfCells() {
        show(Schedule.EMPTY)

        composeRule.onNodeWithTag(ScheduleGridTag).performTouchInput {
            down(centre(0, 14))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(centre(0, 16))
            moveTo(centre(0, 18))
            up()
        }

        for (slot in 14..18) assertThat(schedule.isActive(DayOfWeek.MONDAY, slot)).isTrue()
        assertThat(schedule.isActive(DayOfWeek.MONDAY, 13)).isFalse()
        assertThat(schedule.isActive(DayOfWeek.MONDAY, 19)).isFalse()
        assertThat(toggles).isEmpty()
    }

    @Test
    fun aStrokeStartingOnAnActiveCellErases() {
        show(Schedule.MON_FRI_7_TO_17)

        composeRule.onNodeWithTag(ScheduleGridTag).performTouchInput {
            down(centre(0, 14))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(centre(0, 16))
            up()
        }

        for (slot in 14..16) assertThat(schedule.isActive(DayOfWeek.MONDAY, slot)).isFalse()
        assertThat(schedule.isActive(DayOfWeek.MONDAY, 17)).isTrue()
        assertThat(schedule.isActive(DayOfWeek.TUESDAY, 14)).isTrue()
    }

    @Test
    fun dragAcrossColumnsPaintsEachDay() {
        show(Schedule.EMPTY)

        composeRule.onNodeWithTag(ScheduleGridTag).performTouchInput {
            down(centre(0, 14))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(centre(2, 14))
            up()
        }

        for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)) {
            assertThat(schedule.isActive(day, 14)).isTrue()
        }
        assertThat(schedule.isActive(DayOfWeek.THURSDAY, 14)).isFalse()
    }

    @Test
    fun dayHeaderIsATallEnoughButtonThatReportsTheClick() {
        show(Schedule.EMPTY, highlight = DayOfWeek.WEDNESDAY)

        composeRule.onNodeWithContentDescription("Edit Tuesday").assertHeightIsAtLeast(48.dp).performClick()

        assertThat(headerClicks).containsExactly(DayOfWeek.TUESDAY)
        composeRule.onNodeWithContentDescription("Edit Wednesday")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Today"))
    }

    @Test
    fun aPlainSwipeScrollsAndPaintsNothing() {
        show(Schedule.EMPTY)

        composeRule.onNodeWithTag(ScheduleGridTag).performTouchInput { swipeUp() }

        assertThat(listState.firstVisibleItemIndex).isGreaterThan(ScheduleGridInitialSlot)
        assertThat(schedule).isEqualTo(Schedule.EMPTY)
        assertThat(toggles).isEmpty()
    }

    @Test
    fun holdingADragNearTheBottomEdgeScrollsTheGrid() {
        show(Schedule.EMPTY)
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(ScheduleGridTag).performTouchInput {
            down(centre(0, 14))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(centre(0, 14).x, 590.dp.toPx()))
        }
        composeRule.mainClock.advanceTimeBy(1_000)

        assertThat(listState.firstVisibleItemIndex).isGreaterThan(ScheduleGridInitialSlot)
        composeRule.onNodeWithTag(ScheduleGridTag).performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        assertThat(schedule.isActive(DayOfWeek.MONDAY, 30)).isTrue()
    }
}
