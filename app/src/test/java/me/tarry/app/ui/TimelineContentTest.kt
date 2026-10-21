package me.tarry.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.timeline.HourLabelTag
import me.tarry.app.ui.timeline.TimelineContent
import me.tarry.app.ui.timeline.TimelineItem
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class TimelineContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private var previous = 0
    private var next = 0
    private val selected = mutableListOf<LocalDate>()

    private var is24Hour = false

    @Before
    fun twelveHourByDefault() {
        ShadowSettings.set24HourTimeFormat(false)
    }

    private fun at(hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun t(hour: Int, minute: Int): String = fmt(at(hour, minute))

    /** The same recipe as the app: the locale's best pattern for "hm" / "Hm". */
    private fun fmt(epochMs: Long): String {
        val locale = java.util.Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm")
        return DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }

    /** ICU 72+ uses a narrow no-break space before AM/PM; compare with plain spaces. */
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')

    /** [today] defaults to [date] (viewing today); pass a later day to view a past day with Next enabled. */
    private fun show(
        items: List<TimelineItem>?,
        today: LocalDate = date,
        fontScale: Float? = null,
        nowMs: Long? = at(20, 0),
    ) {
        ShadowSettings.set24HourTimeFormat(is24Hour)
        composeRule.setContent {
            val density = LocalDensity.current
            val provided = if (fontScale == null) density else Density(density.density, fontScale = fontScale)
            CompositionLocalProvider(LocalDensity provides provided) {
                TarryTheme {
                    TimelineContent(
                        date, items, { previous++ }, { next++ },
                        today = today, onSelectDate = { selected += it }, zone = zone, nowMs = nowMs,
                    )
                }
            }
        }
    }

    private fun description(tag: String, expected: String) {
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(expected)),
        )
    }

    @Test
    fun emptyStateIsShown() {
        show(emptyList())
        composeRule.onNodeWithText("Nothing yet today").assertExists()
    }

    @Test
    fun emptyPastDayAdaptsTheCopy() {
        show(emptyList(), today = date.plusDays(3))
        composeRule.onNodeWithText("Nothing recorded").assertExists()
        composeRule.onNodeWithText("Nothing yet today").assertDoesNotExist()
    }

    @Test
    fun visitBlockShowsPlaceAndRange() {
        show(listOf(TimelineItem.Visit("v", at(9, 12), at(11, 40), "12 Smith St")))
        composeRule.onNodeWithText("12 Smith St").assertExists()
        composeRule.onNodeWithText("${t(9, 12)}–${t(11, 40)}").assertExists()
        composeRule.onNodeWithText("Nothing yet today").assertDoesNotExist()
    }

    @Test
    fun shortVisitIsOneLineWithDuration() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(9, 45), null)))
        composeRule.onNodeWithText("Unknown place · 45 min").assertExists()
    }

    @Test
    fun tripRowShowsModeKilometresAndDuration() {
        show(listOf(TimelineItem.Trip("t", at(11, 40), at(12, 0), 1234f, TripMode.VEHICLE)))
        composeRule.onNodeWithText("Drive · 1.2 km · 20 min").assertExists()
    }

    @Test
    fun shortWalkShowsMetres() {
        show(listOf(TimelineItem.Trip("t", at(11, 40), at(11, 50), 850f, TripMode.WALK)))
        composeRule.onNodeWithText("Walk · 850 m · 10 min").assertExists()
    }

    @Test
    fun gapRowShowsCause() {
        show(listOf(TimelineItem.Gap("g", at(13, 0), at(13, 30), GapCause.NO_FIX)))
        composeRule.onNodeWithText("Not tracked · No location fix").assertExists()
    }

    @Test
    fun rowIsOneMergedNode() {
        show(listOf(TimelineItem.Gap("g", at(13, 0), at(13, 30), GapCause.SERVICE_KILLED)))
        composeRule.onNodeWithTag("timeline_row_g").assertExists()
        composeRule.onNodeWithTag("timeline_row_g").assertTextContains("Not tracked · App was closed")
    }

    @Test
    fun previousAndNextCallbacksFire() {
        show(emptyList(), today = date.plusDays(5))
        composeRule.onNodeWithTag("timeline_previous").performClick()
        composeRule.onNodeWithTag("timeline_next").performClick()
        composeRule.onNodeWithTag("timeline_next").performClick()
        assertThat(previous).isEqualTo(1)
        assertThat(next).isEqualTo(2)
    }

    @Test
    fun dayButtonsMeetTouchTarget() {
        show(emptyList())
        for (tag in listOf("timeline_previous", "timeline_next", "timeline_calendar")) {
            composeRule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun loadingShowsNeitherEmptyStateNorRows() {
        show(null)
        composeRule.onNodeWithTag("timeline_loading").assertExists()
        composeRule.onNodeWithText("Nothing yet today").assertDoesNotExist()
        composeRule.onNodeWithTag("timeline_summary").assertDoesNotExist()
    }

    @Test
    fun loadedEmptyListShowsEmptyState() {
        show(emptyList())
        composeRule.onNodeWithTag("timeline_loading").assertDoesNotExist()
        composeRule.onNodeWithText("Nothing yet today").assertExists()
    }

    @Test
    fun visitDescriptionHasTypePrefixRangeAndSpokenDuration() {
        show(listOf(TimelineItem.Visit("v", at(9, 12), at(11, 40), "12 Smith St")))
        description("timeline_row_v", "Visit at 12 Smith St, from ${t(9, 12)} to ${t(11, 40)}, 2 hours 28 minutes")
    }

    @Test
    fun visitDescriptionIncludesTheJobWhenThereIsOne() {
        show(listOf(TimelineItem.Visit("v", at(7, 2), at(9, 40), "Rose Bay Marina", jobName = "Antifoul", jobColorArgb = 0xFFE3EDFB.toInt())))
        description(
            "timeline_row_v",
            "Visit at Rose Bay Marina, from ${t(7, 2)} to ${t(9, 40)}, 2 hours 38 minutes, job Antifoul",
        )
    }

    @Test
    fun spokenDurationUsesSingularForms() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(10, 1), "A")))
        description("timeline_row_v", "Visit at A, from ${t(9, 0)} to ${t(10, 1)}, 1 hour 1 minute")
    }

    @Test
    fun coordinateLabelIsSpokenAsUnknownPlaceButShownAsCoordinates() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(10, 30), "-33.86514, 151.20990", labelIsCoordinates = true)))
        composeRule.onNodeWithText("-33.86514, 151.20990").assertExists()
        description("timeline_row_v", "Visit at Unknown place, from ${t(9, 0)} to ${t(10, 30)}, 1 hour 30 minutes")
    }

    @Test
    fun tripAndGapDescriptions() {
        show(
            listOf(
                TimelineItem.Gap("g", at(8, 0), at(8, 30), GapCause.NO_FIX),
                TimelineItem.Trip("t", at(11, 40), at(12, 0), 1234f, TripMode.UNKNOWN),
            ),
        )
        description("timeline_row_t", "Trip by Unknown mode, from ${t(11, 40)} to ${t(12, 0)}, 1.2 km")
        description("timeline_row_g", "Tracking gap, No location fix, from ${t(8, 0)} to ${t(8, 30)}")
    }

    @Test
    fun subMinuteVisitShowsLessThanOneMinute() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(9, 0) + 30_000, "A")))
        composeRule.onNodeWithText("A · <1 min").assertExists()
    }

    @Test
    fun dateIsAHeadingAndAPoliteLiveRegion() {
        show(emptyList())
        composeRule.onNodeWithTag("timeline_date")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun headerShowsWeekdayAboveTheDate() {
        show(emptyList())
        composeRule.onNodeWithText("Wednesday").assertExists()
        composeRule.onNodeWithTag("timeline_date").assertTextContains("October", substring = true).assertTextContains("21", substring = true)
    }

    @Test
    fun nextIsDisabledOnTodayAndDoesNotFire() {
        show(emptyList(), today = date)
        composeRule.onNodeWithTag("timeline_next").assertIsNotEnabled()
        composeRule.onNodeWithTag("timeline_next").performClick()
        assertThat(next).isEqualTo(0)
        composeRule.onNodeWithTag("timeline_previous").assertIsEnabled()
    }

    @Test
    fun nextIsEnabledOnAPastDay() {
        show(emptyList(), today = date.plusDays(1))
        composeRule.onNodeWithTag("timeline_next").assertIsEnabled()
    }

    @Test
    fun itemSpanningMidnightIsClampedAndMarked() {
        val start = date.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atTime(1, 0).atZone(zone).toInstant().toEpochMilli()
        show(listOf(TimelineItem.Visit("v", start, end, "Night shift")))

        val dayStart = fmt(at(0, 0))
        val dayEnd = fmt(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        composeRule.onNodeWithText("$dayStart–$dayEnd (from previous day) (continues)").assertExists()
        description(
            "timeline_row_v",
            "Visit at Night shift, from $dayStart to $dayEnd (from previous day) (continues), 24 hours",
        )
        composeRule.onNodeWithTag("timeline_summary_total").assertTextContains("24 h")
    }

    @Test
    fun summaryShowsTotalCaptionAndNeedsReviewCount() {
        show(
            listOf(
                TimelineItem.Visit("v1", at(9, 0), at(10, 30), "A"),
                TimelineItem.Visit("v2", at(11, 0), at(12, 0), "B", jobName = "Job", jobColorArgb = 0xFFE3EDFB.toInt()),
                TimelineItem.Trip("t", at(10, 30), at(11, 0), 5000f, TripMode.VEHICLE),
                TimelineItem.Gap("g", at(13, 0), at(13, 30), GapCause.NO_FIX),
            ),
        )
        composeRule.onNodeWithText("Tracked today").assertExists()
        composeRule.onNodeWithTag("timeline_summary_total").assertTextContains("2 h 30 min")
        composeRule.onNodeWithTag("timeline_summary_review").assertTextContains("1 visit needs review")
        composeRule.onNodeWithTag("timeline_summary_caption").assertTextContains("2 visits · 1 trip · 1 gap")
    }

    @Test
    fun summaryHasNoReviewChipWhenEveryVisitHasAJob() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(10, 0), "A", jobName = "Job", jobColorArgb = 0xFFE3EDFB.toInt())))
        composeRule.onNodeWithTag("timeline_summary_review").assertDoesNotExist()
    }

    @Test
    fun pastDaySummaryIsNotLabelledToday() {
        show(listOf(TimelineItem.Visit("v", at(9, 0), at(10, 0), "A")), today = date.plusDays(2))
        composeRule.onNodeWithText("Tracked today").assertDoesNotExist()
        composeRule.onNodeWithText("Tracked").assertExists()
    }

    @Test
    fun calendarButtonOpensTheDatePicker() {
        show(emptyList())
        composeRule.onNodeWithTag("timeline_calendar").performClick()
        composeRule.onNodeWithText("OK").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun weekStripSelectionChangesTheDay() {
        show(emptyList(), today = date.plusDays(5))
        composeRule.onNodeWithTag("timeline_week_${date.minusDays(1)}").performClick()
        assertThat(selected).containsExactly(date.minusDays(1))
    }

    @Test
    fun weekStripFutureDaysAreNotSelectable() {
        show(emptyList(), today = date)
        val tomorrow = composeRule.onNodeWithTag("timeline_week_${date.plusDays(1)}")
        tomorrow.assertIsNotEnabled()
        tomorrow.performClick()
        assertThat(selected).isEmpty()
        composeRule.onNodeWithTag("timeline_week_$date").assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun weekStripMarksSelectedAndToday() {
        show(emptyList(), today = date)
        composeRule.onNodeWithTag("timeline_week_$date").assert(
            SemanticsMatcher("describes today") {
                it.config[SemanticsProperties.ContentDescription].single().endsWith(", today")
            },
        )
    }

    @Test
    fun fontScaleTwoKeepsHeaderSummaryAndEmptyStateUntruncated() {
        show(
            listOf(TimelineItem.Visit("v", at(9, 0), at(11, 0), "Rose Bay Marina", jobName = "Antifoul", jobColorArgb = 0xFFE3EDFB.toInt())),
            fontScale = 2f,
        )
        composeRule.onNodeWithTag("timeline_date").assertIsDisplayed().assertNotTruncated()
        composeRule.onNodeWithText("Wednesday").assertIsDisplayed().assertNotTruncated()
        composeRule.onNodeWithText("Tracked today").assertNotTruncated()
        composeRule.onNodeWithTag("timeline_summary_total").assertNotTruncated()
        composeRule.onNodeWithTag("timeline_summary_caption").assertNotTruncated()
        // The header buttons wrap below the date.
        val dateBottom = composeRule.onNodeWithTag("timeline_date").fetchSemanticsNode().boundsInRoot.bottom
        val previousTop = composeRule.onNodeWithTag("timeline_previous").fetchSemanticsNode().boundsInRoot.top
        assertThat(previousTop).isAtLeast(dateBottom)
        // The week strip wraps onto two rows.
        val firstRow = composeRule.onNodeWithTag("timeline_week_${date.minusDays(2)}").fetchSemanticsNode().boundsInRoot.top
        val lastDay = composeRule.onNodeWithTag("timeline_week_${date.plusDays(3)}").fetchSemanticsNode().boundsInRoot.top
        assertThat(lastDay).isNotEqualTo(firstRow)
    }

    @Test
    fun fontScaleTwoKeepsTheEmptyStateUntruncated() {
        show(emptyList(), fontScale = 2f)
        composeRule.onNodeWithText("Nothing yet today").assertNotTruncated()
        composeRule.onNodeWithText("Visits and trips appear here as Tarry records your day.").assertNotTruncated()
    }

    private fun rangeTexts(tag: String): List<String> =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text.plain() }

    private fun hourLabels(): List<String> =
        composeRule.onAllNodesWithTag(HourLabelTag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].single().text.plain() }

    @Test
    fun twelveHourSettingShowsAmPmTimesLabelsAndSpeech() {
        is24Hour = false
        show(listOf(TimelineItem.Visit("v", at(21, 5), at(22, 40), "A")), nowMs = at(22, 15))
        assertThat(rangeTexts("timeline_row_v")).contains("9:05 PM–10:40 PM")
        assertThat(hourLabels()).containsAtLeast("9 PM", "10 PM")
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher("12-hour speech") {
                it.config[SemanticsProperties.ContentDescription].single().plain() ==
                    "Visit at A, from 9:05 PM to 10:40 PM, 1 hour 35 minutes"
            },
        )
        composeRule.onNodeWithTag("timeline_now_line").assert(
            SemanticsMatcher("12-hour now") { it.config[SemanticsProperties.ContentDescription].single().plain() == "Now, 10:15 PM" },
        )
    }

    @Test
    fun twentyFourHourSettingShowsTwentyFourHourTimesLabelsAndSpeech() {
        is24Hour = true
        show(listOf(TimelineItem.Visit("v", at(21, 5), at(22, 40), "A")), nowMs = at(22, 15))
        assertThat(rangeTexts("timeline_row_v").any { it.matches(Regex("21:05–22:40")) }).isTrue()
        assertThat(hourLabels()).containsAtLeast("21", "22")
        assertThat(hourLabels().none { it.contains("M") }).isTrue()
        description("timeline_row_v", "Visit at A, from 21:05 to 22:40, 1 hour 35 minutes")
        composeRule.onNodeWithTag("timeline_now_line").assert(
            SemanticsMatcher("24-hour now") { it.config[SemanticsProperties.ContentDescription].single() == "Now, 22:15" },
        )
    }

    @Test
    fun fontScaleTwoKeepsTheNowPillHourLabelsAndVisitTextUntruncated() {
        show(
            listOf(TimelineItem.Visit("v", at(9, 0), at(11, 0), "Rose Bay Marina", jobName = "Antifoul", jobColorArgb = 0xFFE3EDFB.toInt())),
            fontScale = 2f,
            nowMs = at(10, 30),
        )
        composeRule.onNodeWithTag("timeline_now_line").assertNotTruncated()
        composeRule.onNodeWithText("Rose Bay Marina", useUnmergedTree = true).assertNotTruncated()
        composeRule.onNode(hasText("Antifoul", substring = true), useUnmergedTree = true).assertNotTruncated()
        val labels = composeRule.onAllNodesWithTag(HourLabelTag, useUnmergedTree = true)
        assertThat(labels.fetchSemanticsNodes()).isNotEmpty()
        // The pill sits in the time column, left of the blocks, and the labels never overflow it.
        val pill = composeRule.onNodeWithTag("timeline_now_line").fetchSemanticsNode().boundsInRoot
        val block = composeRule.onNodeWithTag("timeline_row_v").fetchSemanticsNode().boundsInRoot
        assertThat(pill.right).isAtMost(block.left)
        labels.fetchSemanticsNodes().forEach { assertThat(it.boundsInRoot.right).isAtMost(block.left) }
    }
}
