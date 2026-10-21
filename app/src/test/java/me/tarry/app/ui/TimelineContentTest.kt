package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.timeline.TimelineContent
import me.tarry.app.ui.timeline.TimelineItem
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TimelineContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private var previous = 0
    private var next = 0

    private fun at(hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun show(items: List<TimelineItem>) {
        composeRule.setContent { TimelineContent(date, items, { previous++ }, { next++ }, zone = zone) }
    }

    @Test
    fun emptyStateIsShown() {
        show(emptyList())
        composeRule.onNodeWithText("No visits yet").assertExists()
    }

    @Test
    fun visitRowShowsRangeDurationAndPlace() {
        show(listOf(TimelineItem.Visit("v", at(9, 12), at(11, 40), "12 Smith St")))
        composeRule.onNodeWithText("09:12–11:40").assertExists()
        composeRule.onNodeWithText("2 h 28 min").assertExists()
        composeRule.onNodeWithText("12 Smith St").assertExists()
        composeRule.onNodeWithText("No visits yet").assertDoesNotExist()
    }

    @Test
    fun visitWithoutPlaceShowsUnknown() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(9, 45), null)))
        composeRule.onNodeWithText("45 min").assertExists()
        composeRule.onNodeWithText("Unknown place").assertExists()
    }

    @Test
    fun tripRowShowsKilometresAndMode() {
        show(listOf(TimelineItem.Trip("t", at(11, 40), at(12, 0), 1234f, TripMode.VEHICLE)))
        composeRule.onNodeWithText("11:40–12:00").assertExists()
        composeRule.onNodeWithText("1.2 km").assertExists()
        composeRule.onNodeWithText("Vehicle").assertExists()
    }

    @Test
    fun shortTripShowsMetres() {
        show(listOf(TimelineItem.Trip("t", at(11, 40), at(11, 50), 850f, TripMode.WALK)))
        composeRule.onNodeWithText("850 m").assertExists()
        composeRule.onNodeWithText("Walk").assertExists()
    }

    @Test
    fun gapRowShowsCause() {
        show(listOf(TimelineItem.Gap("g", at(13, 0), at(13, 30), GapCause.NO_FIX)))
        composeRule.onNodeWithText("13:00–13:30").assertExists()
        composeRule.onNodeWithText("No location fix").assertExists()
    }

    @Test
    fun rowIsOneMergedNode() {
        show(listOf(TimelineItem.Gap("g", at(13, 0), at(13, 30), GapCause.SERVICE_KILLED)))
        composeRule.onNodeWithTag("timeline_row_g").assertExists()
        composeRule.onNodeWithTag("timeline_row_g").assertTextContains("13:00–13:30").assertTextContains("App was closed")
    }

    @Test
    fun previousAndNextCallbacksFire() {
        show(emptyList())
        composeRule.onNodeWithTag("timeline_previous").performClick()
        composeRule.onNodeWithTag("timeline_next").performClick()
        composeRule.onNodeWithTag("timeline_next").performClick()
        assertThat(previous).isEqualTo(1)
        assertThat(next).isEqualTo(2)
    }

    @Test
    fun dayButtonsMeetTouchTarget() {
        show(emptyList())
        for (tag in listOf("timeline_previous", "timeline_next")) {
            composeRule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }
}
