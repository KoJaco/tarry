package me.tarry.app.ui.timeline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.DarkTarryColors
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.LightTarryColors
import me.tarry.app.ui.theme.TarryTheme
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
class DayCanvasTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)

    private fun at(hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val sky = JobPalette.slots.first { it.name == "Sky" }

    private fun show(items: List<TimelineItem>, today: LocalDate = date, dark: Boolean = false, nowMs: Long = at(20)) {
        composeRule.setContent {
            TarryTheme(darkTheme = dark) {
                TimelineContent(date, items, {}, {}, today = today, zone = zone, nowMs = nowMs)
            }
        }
    }

    private fun blockColor(tag: String): Color =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[BlockColorKey]

    @Test
    fun jobColourIsAppliedToTheBlock() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "Marina", jobName = "Antifoul", jobColorArgb = sky.key)))
        assertThat(blockColor("timeline_row_v")).isEqualTo(sky.light.container)
        composeRule.onNodeWithTag("timeline_row_v")
            .assert(SemanticsMatcher("not dashed") { it.config[BlockDashedKey] == false })
    }

    @Test
    fun jobColourFollowsTheDarkTheme() {
        show(
            listOf(TimelineItem.Visit("v", at(9), at(11), "Marina", jobName = "Antifoul", jobColorArgb = sky.key)),
            dark = true,
        )
        assertThat(blockColor("timeline_row_v")).isEqualTo(sky.dark.container)
    }

    @Test
    fun visitWithoutJobIsACardWithADashedOutlineAndNeedsReviewChip() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "Marina")))
        assertThat(blockColor("timeline_row_v")).isEqualTo(LightTarryColors.card)
        composeRule.onNodeWithTag("timeline_row_v")
            .assert(SemanticsMatcher("dashed") { it.config[BlockDashedKey] })
            .assert(
                SemanticsMatcher("has needs review chip") { node ->
                    node.config[SemanticsProperties.Text].any { it.text == "Needs review" }
                },
            )
    }

    @Test
    fun noJobBlockUsesTheDarkCardColour() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "Marina")), dark = true)
        assertThat(blockColor("timeline_row_v")).isEqualTo(DarkTarryColors.card)
    }

    @Test
    fun jobBlockShowsTheJobNextToTheRangeAndNoReviewChip() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "Marina", jobName = "Antifoul", jobColorArgb = sky.key)))
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher("job line") { node ->
                node.config[SemanticsProperties.Text].any { it.text.endsWith("· Antifoul") } &&
                    node.config[SemanticsProperties.Text].none { it.text == "Needs review" }
            },
        )
    }

    @Test
    fun gapIsHatched() {
        show(listOf(TimelineItem.Gap("g", at(13), at(14), GapCause.SERVICE_KILLED)))
        composeRule.onNodeWithTag("timeline_row_g")
            .assert(SemanticsMatcher("hatched") { it.config[GapHatchKey] })
        composeRule.onNodeWithText("Not tracked · App was closed").assertExists()
    }

    @Test
    fun visitsAreNotHatched() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "A")))
        composeRule.onNodeWithTag("timeline_row_v")
            .assert(SemanticsMatcher("not hatched") { GapHatchKey !in it.config })
    }

    @Test
    fun nowLineShowsOnTodayOnly() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "A")), today = date)
        composeRule.onNodeWithTag("timeline_now_line").assertExists()
    }

    @Test
    fun nowLineIsAbsentOnAPastDay() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "A")), today = date.plusDays(1))
        composeRule.onNodeWithTag("timeline_now_line").assertDoesNotExist()
    }

    @Test
    fun nowLineIsDescribedWithTheTime() {
        show(listOf(TimelineItem.Visit("v", at(9), at(11), "A")), today = date, nowMs = at(10, 30))
        composeRule.onNodeWithTag("timeline_now_line").assert(
            SemanticsMatcher("now description") {
                it.config[SemanticsProperties.ContentDescription].single().startsWith("Now, ")
            },
        )
    }

    @Test
    fun shortTripIsFoldedIntoTheNextVisitDescription() {
        show(
            listOf(
                TimelineItem.Visit("a", at(9, 0), at(9, 20), "A"),
                TimelineItem.Trip("t", at(9, 20), at(9, 25), 500f, TripMode.VEHICLE),
                TimelineItem.Visit("b", at(9, 25), at(10, 30), "B"),
            ),
        )
        composeRule.onNodeWithTag("timeline_row_t").assertDoesNotExist()
        composeRule.onNodeWithTag("timeline_row_b").assert(
            SemanticsMatcher("folded trip appended") { node ->
                val d = node.config[SemanticsProperties.ContentDescription].single()
                d.startsWith("Visit at B, ") && d.contains(". Also: Trip by Vehicle, ") && d.endsWith("500 m")
            },
        )
        composeRule.onNodeWithTag("timeline_row_a").assert(
            SemanticsMatcher("no extras") { !it.config[SemanticsProperties.ContentDescription].single().contains("Also") },
        )
    }

    @Test
    fun readingOrderIsTimeOrder() {
        show(
            listOf(
                TimelineItem.Visit("a", at(8), at(9), "A"),
                TimelineItem.Trip("t", at(9), at(9, 30), 4000f, TripMode.VEHICLE),
                TimelineItem.Gap("g", at(9, 30), at(10), GapCause.NO_FIX),
                TimelineItem.Visit("b", at(10), at(11), "B"),
            ),
            nowMs = at(9, 45),
        )
        fun index(tag: String) =
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        val order = listOf("timeline_row_a", "timeline_row_t", "timeline_row_g", "timeline_now_line", "timeline_row_b")
            .map { index(it) }
        assertThat(order).isInOrder()
        assertThat(order.toSet()).hasSize(order.size)
    }

    @Test
    fun tripRowUsesDriveAndWalkLabels() {
        show(
            listOf(
                TimelineItem.Visit("a", at(8), at(9), "A"),
                TimelineItem.Trip("t1", at(9), at(9, 30), 12400f, TripMode.VEHICLE),
                TimelineItem.Visit("b", at(9, 30), at(10), "B"),
                TimelineItem.Trip("t2", at(10), at(10, 30), 800f, TripMode.WALK),
                TimelineItem.Visit("c", at(10, 30), at(11), "C"),
            ),
        )
        composeRule.onNodeWithText("Drive · 12.4 km · 30 min").assertExists()
        composeRule.onNodeWithText("Walk · 800 m · 30 min").assertExists()
    }

    @Test
    fun shortVisitIsCompactWithOneLine() {
        show(listOf(TimelineItem.Visit("v", at(9), at(9, 12), "Kiosk")))
        composeRule.onNodeWithText("Kiosk · 12 min").assertExists()
    }

    private fun colourOf(id: String, jobId: String?, jobName: String = "Same name") {
        composeRule.setContent {
            TarryTheme {
                TimelineContent(
                    date, listOf(TimelineItem.Visit(id, at(9), at(11), "P", jobName = jobName, jobColorArgb = 0x12345678, jobId = jobId)),
                    {}, {}, today = date, zone = zone, nowMs = at(20),
                )
            }
        }
    }

    @Test
    fun fallbackColourIsKeyedOnTheJobIdNotTheName() {
        // "a" and "b" hash to different slots, so two jobs with the same name get their own colours.
        colourOf("v", jobId = "a")
        val first = blockColor("timeline_row_v")
        assertThat(first).isEqualTo(JobPalette.fallbackFor("a").light.container)
        assertThat(JobPalette.fallbackFor("a")).isNotEqualTo(JobPalette.fallbackFor("b"))
    }

    @Test
    fun fallbackColourForOtherIdIsThatIdsSlot() {
        colourOf("v", jobId = "b")
        assertThat(blockColor("timeline_row_v")).isEqualTo(JobPalette.fallbackFor("b").light.container)
    }

    @Test
    fun fallbackColourIsStableForTheSameId() {
        colourOf("v", jobId = "a", jobName = "Renamed")
        assertThat(blockColor("timeline_row_v")).isEqualTo(JobPalette.fallbackFor("a").light.container)
    }
}
