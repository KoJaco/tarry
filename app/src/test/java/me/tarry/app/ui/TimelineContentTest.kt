package me.tarry.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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

    private fun t(hour: Int, minute: Int): String = fmt(at(hour, minute))

    private fun fmt(epochMs: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private fun show(items: List<TimelineItem>?, nextEnabled: Boolean = true) {
        composeRule.setContent { TimelineContent(date, items, { previous++ }, { next++ }, nextEnabled = nextEnabled, zone = zone) }
    }

    @Test
    fun emptyStateIsShown() {
        show(emptyList())
        composeRule.onNodeWithText("No visits yet").assertExists()
    }

    @Test
    fun visitRowShowsRangeDurationAndPlace() {
        show(listOf(TimelineItem.Visit("v", at(9, 12), at(11, 40), "12 Smith St")))
        composeRule.onNodeWithText("${t(9, 12)}–${t(11, 40)}").assertExists()
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
        composeRule.onNodeWithText("${t(11, 40)}–${t(12, 0)}").assertExists()
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
        composeRule.onNodeWithText("${t(13, 0)}–${t(13, 30)}").assertExists()
        composeRule.onNodeWithText("No location fix").assertExists()
    }

    @Test
    fun rowIsOneMergedNode() {
        show(listOf(TimelineItem.Gap("g", at(13, 0), at(13, 30), GapCause.SERVICE_KILLED)))
        composeRule.onNodeWithTag("timeline_row_g").assertExists()
        composeRule.onNodeWithTag("timeline_row_g").assertTextContains("${t(13, 0)}–${t(13, 30)}").assertTextContains("App was closed")
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

    @Test
    fun loadingShowsNeitherEmptyStateNorRows() {
        show(null)
        composeRule.onNodeWithTag("timeline_loading").assertExists()
        composeRule.onNodeWithText("No visits yet").assertDoesNotExist()
    }

    @Test
    fun loadedEmptyListShowsEmptyState() {
        show(emptyList())
        composeRule.onNodeWithTag("timeline_loading").assertDoesNotExist()
        composeRule.onNodeWithText("No visits yet").assertExists()
    }

    @Test
    fun visitDescriptionHasTypePrefixRangeAndSpokenDuration() {
        show(listOf(TimelineItem.Visit("v", at(9, 12), at(11, 40), "12 Smith St")))
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Visit at 12 Smith St, from ${t(9, 12)} to ${t(11, 40)}, 2 hours 28 minutes"),
            ),
        )
    }

    @Test
    fun spokenDurationUsesSingularForms() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(10, 1), "A")))
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Visit at A, from ${t(9, 0)} to ${t(10, 1)}, 1 hour 1 minute"),
            ),
        )
    }

    @Test
    fun coordinateLabelIsSpokenAsUnknownPlaceButShownAsCoordinates() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(9, 45), "-33.86514, 151.20990", labelIsCoordinates = true)))
        composeRule.onNodeWithText("-33.86514, 151.20990").assertExists()
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Visit at Unknown place, from ${t(9, 0)} to ${t(9, 45)}, 45 minutes"),
            ),
        )
    }

    @Test
    fun tripAndGapDescriptions() {
        show(
            listOf(
                TimelineItem.Gap("g", at(8, 0), at(8, 30), GapCause.NO_FIX),
                TimelineItem.Trip("t", at(11, 40), at(12, 0), 1234f, TripMode.UNKNOWN),
            ),
        )
        composeRule.onNodeWithTag("timeline_row_t").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Trip by Unknown mode, from ${t(11, 40)} to ${t(12, 0)}, 1.2 km"),
            ),
        )
        composeRule.onNodeWithTag("timeline_row_g").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Tracking gap, No location fix, from ${t(8, 0)} to ${t(8, 30)}"),
            ),
        )
        composeRule.onNodeWithText("Unknown mode").assertExists()
    }

    @Test
    fun subMinuteVisitShowsLessThanOneMinute() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(9, 0) + 30_000, "A")))
        composeRule.onNodeWithText("<1 min").assertExists()
    }

    @Test
    fun dateIsAHeadingAndAPoliteLiveRegion() {
        show(emptyList())
        composeRule.onNodeWithTag("timeline_date")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun nextIsDisabledWhenNotEnabledAndDoesNotFire() {
        show(emptyList(), nextEnabled = false)
        composeRule.onNodeWithTag("timeline_next").assertIsNotEnabled()
        composeRule.onNodeWithTag("timeline_next").performClick()
        assertThat(next).isEqualTo(0)
        composeRule.onNodeWithTag("timeline_previous").assertIsEnabled()
    }

    @Test
    fun itemSpanningMidnightIsClampedAndMarked() {
        val start = date.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
        show(listOf(TimelineItem.Visit("v", start, end, "Night shift")))

        val dayStart = fmt(at(0, 0))
        val dayEnd = fmt(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        composeRule.onNodeWithText("$dayStart–$dayEnd (from previous day) (continues)").assertExists()
        composeRule.onNodeWithText("24 h").assertExists()
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Visit at Night shift, from $dayStart to $dayEnd (from previous day) (continues), 24 hours"),
            ),
        )
    }
}
