package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import me.tarry.app.R
import me.tarry.app.ui.drawer.EditTarget
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w320dp-h1000dp")
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
    fun blocksAreWide_andAdjacentBlocksNeverTouch() {
        show(
            listOf(
                TimelineItem.Visit("a", at(9), at(10), "A"),
                TimelineItem.Visit("b", at(10), at(11), "B"),
            ),
        )
        val a = composeRule.onNodeWithTag("timeline_row_a").getUnclippedBoundsInRoot()
        val b = composeRule.onNodeWithTag("timeline_row_b").getUnclippedBoundsInRoot()
        // 20 dp gutter + 8 dp card padding + 44 dp time column + 4 dp inset (a little more if a label needs the room).
        assertThat(a.left.value).isAtMost(80f)
        assertThat((b.top - a.bottom).value).isAtLeast(4f - 0.01f)
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
    fun gapInsideAVisitIsABandNotASeparateNode() {
        show(
            listOf(
                TimelineItem.Visit("v", at(9), at(11), "Marina"),
                TimelineItem.Gap("g", at(10), at(10, 2), GapCause.SERVICE_KILLED),
            ),
        )
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher("one inner band") { it.config[InnerGapHatchKey] == 1 },
        )
        composeRule.onNodeWithTag("timeline_row_v").assert(
            SemanticsMatcher("not tracked in description") {
                it.config[SemanticsProperties.ContentDescription].single().contains("includes 2 minutes not tracked")
            },
        )
        composeRule.onNodeWithTag("timeline_row_g").assertDoesNotExist()
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
    fun shortTripBetweenTwoVisitsRendersAClickableLabelledRowThatCoversNoVisit() {
        val targets = mutableListOf<EditTarget>()
        composeRule.setContent {
            TarryTheme {
                TimelineContent(
                    date,
                    listOf(
                        TimelineItem.Visit("a", at(9, 0), at(9, 40), "A"),
                        TimelineItem.Trip("t", at(9, 40), at(9, 47), 800f, TripMode.WALK),
                        TimelineItem.Visit("b", at(9, 47), at(10, 30), "B"),
                    ),
                    {}, {}, today = date, zone = zone, nowMs = at(20), onEdit = { targets += it },
                )
            }
        }
        // A 7 minute trip is about 8 dp of true height; it used to be folded away with no row at all.
        val row = composeRule.onNodeWithTag("timeline_row_t")
        row.assertExists().assertHasClickAction()
        composeRule.onNodeWithText("Walk · 800 m · 7 min").assertExists()
        row.assert(
            SemanticsMatcher("labelled") {
                val d = it.config[SemanticsProperties.ContentDescription].single()
                d.contains("Walk") && d.contains("9:40") && d.contains("9:47")
            },
        )
        val t = row.getUnclippedBoundsInRoot()
        assertThat(t.height.value).isAtLeast(48f - 0.01f)
        for (visit in listOf("a", "b")) {
            val v = composeRule.onNodeWithTag("timeline_row_$visit").getUnclippedBoundsInRoot()
            val overlaps = t.top < v.bottom - 0.01.dp && v.top < t.bottom - 0.01.dp
            assertThat(overlaps).isFalse()
        }
        row.performClick()
        assertThat(targets).containsExactly(EditTarget.Trip("t"))
    }

    @Test
    fun everyShortTripOfARealDayKeepsItsOwnRowBetweenItsNeighbours() {
        // Shape of the dogfood day: 10 min walk between two Home visits, 10 min drive to the yard.
        val items = listOf(
            TimelineItem.Visit("h1", at(7, 0), at(7, 21), "Home"),
            TimelineItem.Trip("walk", at(7, 21), at(7, 31), 800f, TripMode.WALK),
            TimelineItem.Visit("h2", at(7, 31), at(7, 51), "Home"),
            TimelineItem.Trip("drive", at(7, 51), at(8, 1), 5300f, TripMode.VEHICLE),
            TimelineItem.Visit("yard", at(8, 1), at(8, 40), "Boatyard"),
        )
        show(items)
        val tags = listOf("h1", "walk", "h2", "drive", "yard").map { "timeline_row_$it" }
        val bounds = tags.map { composeRule.onNodeWithTag(it).assertExists().getUnclippedBoundsInRoot() }
        // Reading order top to bottom with no overlap between any two neighbours.
        bounds.zipWithNext().forEach { (upper, lower) -> assertThat(lower.top.value).isAtLeast(upper.bottom.value - 0.01f) }
        composeRule.onNodeWithTag("timeline_row_walk").assertHasClickAction()
        composeRule.onNodeWithTag("timeline_row_drive").assertHasClickAction()
    }

    @Test
    fun shortTripRowKeepsA48dpTouchTargetAndNoOverlapAtFontScale2() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TarryTheme {
                    TimelineContent(
                        date,
                        listOf(
                            TimelineItem.Visit("a", at(9, 0), at(9, 40), "A"),
                            TimelineItem.Trip("t", at(9, 40), at(9, 47), 800f, TripMode.UNKNOWN),
                            TimelineItem.Visit("b", at(9, 47), at(10, 30), "B"),
                        ),
                        {}, {}, today = date, zone = zone, nowMs = at(20),
                    )
                }
            }
        }
        val t = composeRule.onNodeWithTag("timeline_row_t").assertHasClickAction().getUnclippedBoundsInRoot()
        assertThat(t.height.value).isAtLeast(48f - 0.01f)
        val a = composeRule.onNodeWithTag("timeline_row_a").getUnclippedBoundsInRoot()
        val b = composeRule.onNodeWithTag("timeline_row_b").getUnclippedBoundsInRoot()
        assertThat(t.top.value).isAtLeast(a.bottom.value - 0.01f)
        assertThat(b.top.value).isAtLeast(t.bottom.value - 0.01f)
    }

    @Test
    fun unknownTripModeUsesTheNeutralRouteIconNotTheCar() {
        assertThat(TripMode.UNKNOWN.iconRes()).isEqualTo(R.drawable.ic_route)
        assertThat(TripMode.UNKNOWN.iconRes()).isNotEqualTo(R.drawable.ic_car)
        assertThat(TripMode.VEHICLE.iconRes()).isEqualTo(R.drawable.ic_car)
        assertThat(TripMode.WALK.iconRes()).isEqualTo(R.drawable.ic_walk)
        // And the row says "Trip" for it.
        show(
            listOf(
                TimelineItem.Visit("a", at(9, 0), at(9, 40), "A"),
                TimelineItem.Trip("t", at(9, 40), at(9, 49), 1900f, TripMode.UNKNOWN),
                TimelineItem.Visit("b", at(9, 49), at(10, 30), "B"),
            ),
        )
        composeRule.onNodeWithText("Trip · 1.9 km · 9 min").assertExists()
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

    @Test
    fun innerGapDotSitsOnTheLeftBorderAndNeverUnderTheTitle() {
        show(
            listOf(
                TimelineItem.Visit("v", at(9), at(11), "Marina"),
                TimelineItem.Gap("g", at(9, 5), at(9, 40), GapCause.SERVICE_KILLED),
            ),
        )
        val block = composeRule.onNodeWithTag("timeline_row_v").getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText("Marina", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val rects = composeRule.onNodeWithTag("timeline_row_v").fetchSemanticsNode().config[InnerGapRectsKey]
        assertThat(rects).hasSize(1)
        rects.forEach {
            assertThat(it.center.x).isWithin(1f).of(0.75f)
            assertThat(it.width).isWithin(0.01f).of(5f)
            assertThat(it.height).isWithin(0.01f).of(5f)
            assertThat(it.right).isLessThan((title.left - block.left).value)
        }
    }

    @Test
    fun includesLineShowsInATallBlockButNotInACompactOne() {
        show(
            listOf(
                TimelineItem.Visit("v", at(9), at(11), "Marina"),
                TimelineItem.Gap("g", at(9, 5), at(9, 8), GapCause.SERVICE_KILLED),
            ),
        )
        composeRule.onNodeWithText("Includes 3 minutes not tracked").assertIsDisplayed()
    }

    @Test
    fun includesLineIsAbsentInACompactBlock() {
        show(
            listOf(
                TimelineItem.Visit("v", at(9), at(9, 25), "Kiosk"),
                TimelineItem.Gap("g", at(9, 10), at(9, 13), GapCause.SERVICE_KILLED),
            ),
        )
        composeRule.onAllNodesWithText("Includes 3 minutes not tracked", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag("timeline_row_v").assert(SemanticsMatcher("marker only") { it.config[InnerGapHatchKey] == 1 })
    }

    @Test
    fun innerGapIsNotABandOrANode() {
        // A gap 10 minutes into a short-ish visit lies under the title and time line.
        show(
            listOf(
                TimelineItem.Visit("v", at(9), at(11), "Marina"),
                TimelineItem.Gap("g", at(9, 5), at(9, 40), GapCause.SERVICE_KILLED),
            ),
        )
        val block = composeRule.onNodeWithTag("timeline_row_v").getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText("Marina").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertThat(title.top).isAtLeast(block.top)
        assertThat(title.bottom).isAtMost(block.bottom)
        // The band is background drawing: no node of its own and no label text overlapping the title.
        composeRule.onNodeWithTag("timeline_row_g").assertDoesNotExist()
        composeRule.onAllNodesWithText("Not tracked · App was closed").assertCountEquals(0)
        composeRule.onNode(hasText("Needs review") and hasAnyAncestor(hasTestTag("timeline_row_v")), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_row_v").assert(SemanticsMatcher("one inner band") { it.config[InnerGapHatchKey] == 1 })
    }

    @Test
    fun visitContentIsTopAlignedInATallBlock() {
        show(listOf(TimelineItem.Visit("v", at(14, 17), at(16, 43), "Marina")))
        val block = composeRule.onNodeWithTag("timeline_row_v").getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText("Marina").getUnclippedBoundsInRoot()
        // Block padding is 6 dp; allow 2 dp for the text's own leading.
        assertThat((title.top - block.top).value).isAtMost(6f + 2f)
    }
}
