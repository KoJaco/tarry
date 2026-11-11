package me.tarry.app.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertWidthIsEqualTo
import me.tarry.app.ui.components.LocalMotionPreferences
import me.tarry.app.ui.components.MotionPreferences
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.map.RoutePoint
import me.tarry.app.map.ScrubberModel
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class DayScrubberTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 10, 21)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val items = listOf(
        TimelineItem.Visit("a", at(8), at(10), "Office", needsReview = true),
        TimelineItem.Trip("t", at(10), at(11), 900f, TripMode.VEHICLE),
        TimelineItem.Visit("b", at(11), at(13), "Site"),
        TimelineItem.Gap("g", at(13), at(14), GapCause.NO_FIX),
        TimelineItem.Visit("c", at(14), at(16), "Yard"),
    )
    private var time by mutableLongStateOf(at(8))
    private val opened = mutableListOf<String>()

    private val addJobFor = mutableListOf<String>()
    private val openedTrips = mutableListOf<String>()
    private val siteJob = JobBlockItem("jb", "job", "Fitout", 0xFF3366CC.toInt(), at(11), at(13), billable = true, notes = null)

    private var minimised by mutableStateOf(false)
    private val minimisedChanges = mutableListOf<Boolean>()

    private fun show(dark: Boolean = false, fontScale: Float = 1f, rows: List<TimelineItem> = items, reducedMotion: Boolean = false) {
        val (start, end) = dayBounds(day, zone)
        val model = ScrubberModel(rows.toScrubItems(), start, end, listOf(RoutePoint(at(8), -33.9, 151.2, 5f)))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                CompositionLocalProvider(LocalMotionPreferences provides MotionPreferences { reducedMotion }) {
                TarryTheme(darkTheme = dark) {
                    DayScrubber(
                        model, mapOf("b" to siteJob), time, { time = it }, { opened += it },
                        date = day, today = day, zone = zone, onJump = { _, _ -> },
                        items = rows, onOpenTrip = { openedTrips += it }, onAddJob = { addJobFor += it.id },
                        minimised = minimised, onMinimisedChange = { minimised = it; minimisedChanges += it },
                    )
                }
                }
            }
        }
    }

    private fun cardHeightPx(): Float =
        composeRule.onNodeWithTag("map_scrub_card").fetchSemanticsNode().size.height.toFloat()

    private fun stripDescription(): String =
        composeRule.onNodeWithTag("map_scrubber").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")

    @Test
    fun theStripIsSixtyFourDpTallAndAllOfItIsDraggable() {
        show()
        composeRule.onNodeWithTag("map_scrubber").assertHeightIsEqualTo(64.dp)
        // Down in the hour-label band (bottom edge), then along: still a drag.
        composeRule.onNodeWithTag("map_scrubber").performTouchInput {
            down(Offset(width * 0.05f, height - 2f))
            moveBy(Offset(width * 0.45f, 0f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { up() }
        assertThat(time).isEqualTo(at(11))
    }

    @Test
    fun theCardShowsAVisitThatNeedsReviewWithAddJob() {
        show()
        composeRule.onNodeWithText("Office", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Needs review", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("map_scrub_add_job").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(addJobFor).containsExactly("a")
        assertThat(opened).isEmpty() // the button took the tap, not the card behind it
    }

    @Test
    fun theCardShowsAVisitsJobChip() {
        time = at(11)
        show()
        composeRule.onNodeWithText("Site", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Fitout · Billable", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("map_scrub_add_job").assertDoesNotExist()
    }

    @Test
    fun theCardShowsATripWithItsNeighbours() {
        time = at(10, 30)
        show()
        composeRule.onNodeWithText("Vehicle · 900 m · 1 h", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Near Office", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("→ Site", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theCardShowsAGapWithItsCause() {
        time = at(13, 30)
        show()
        composeRule.onNodeWithText("No location · 1 h", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("No location fix", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("map_scrub_open").assertDoesNotExist()
    }

    @Test
    fun theCardIsTheSameHeightForEveryKindOfItem() {
        show()
        val visit = cardHeightPx()
        composeRule.runOnIdle { time = at(10, 30) }
        composeRule.waitForIdle()
        val trip = cardHeightPx()
        composeRule.runOnIdle { time = at(13, 30) }
        composeRule.waitForIdle()
        val gap = cardHeightPx()
        composeRule.runOnIdle { time = at(11) }
        composeRule.waitForIdle()
        val job = cardHeightPx()
        assertThat(listOf(trip, gap, job)).containsExactly(visit, visit, visit)
    }

    @Test
    fun theCardAndItsOpenButtonOpenTheItem() {
        time = at(11)
        show()
        composeRule.onNodeWithTag("map_scrub_open").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertThat(opened).containsExactly("b")
        composeRule.onNodeWithTag("map_scrub_card").performTouchInput { click(Offset(width * 0.3f, 10f)) }
        assertThat(opened).containsExactly("b", "b")
        composeRule.runOnIdle { time = at(10, 30) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map_scrub_open").performClick()
        assertThat(openedTrips).containsExactly("t")
    }

    @Test
    fun theDescriptionChangesWhenTheThumbSnapsNotWhileDragging() {
        show()
        val before = stripDescription()
        assertThat(before).contains("Day from")
        assertThat(before).contains("3 visits, 1 trip")
        assertThat(before).contains("Office")
        composeRule.onNodeWithTag("map_scrubber").performTouchInput {
            down(centerLeft.copy(x = width * 0.05f))
            moveBy(Offset(width * 0.45f, 0f))
        }
        composeRule.waitForIdle()
        assertThat(stripDescription()).isEqualTo(before)
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { up() }
        composeRule.waitForIdle()
        assertThat(stripDescription()).contains("Site")
        assertThat(stripDescription()).doesNotContain("At 8:00")
    }


    private fun checkLayout(dark: Boolean, scale: Float) {
        show(dark, scale)
        val first = cardHeightPx()
        composeRule.onNodeWithTag("map_scrub_add_job").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("map_scrubber").assertHeightIsAtLeast(64.dp)
        composeRule.onNodeWithTag("map_scrub_time").assertHeightIsAtLeast(48.dp)
        composeRule.runOnIdle { time = at(13, 30) }
        composeRule.waitForIdle()
        assertThat(cardHeightPx()).isEqualTo(first)
    }

    @Test fun lightNormalText() = checkLayout(dark = false, scale = 1f)

    @Test fun lightLargeText() = checkLayout(dark = false, scale = 2f)

    @Test fun darkNormalText() = checkLayout(dark = true, scale = 1f)

    @Test fun darkLargeText() = checkLayout(dark = true, scale = 2f)

    @Test
    fun stepButtonsWalkTheVisitStartsAndDisableAtTheEnds() {
        show()
        composeRule.onNodeWithTag("map_scrub_previous").assertIsNotEnabled()
        composeRule.onNodeWithTag("map_scrub_next").assertIsEnabled().performClick()
        assertThat(time).isEqualTo(at(11))
        composeRule.onNodeWithTag("map_scrub_next").performClick()
        assertThat(time).isEqualTo(at(14))
        composeRule.onNodeWithTag("map_scrub_next").assertIsNotEnabled()
        composeRule.onNodeWithTag("map_scrub_previous").performClick()
        assertThat(time).isEqualTo(at(11))
    }

    @Test
    fun setProgressSnapsToTheNearestVisitStart() {
        show()
        // 8:00–16:00 strip: 0.45 is 11:36, nearest visit start 11:00.
        composeRule.onNodeWithTag("map_scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0.45f) }
        assertThat(time).isEqualTo(at(11))
    }

    @Test
    fun tappingAVisitBlockOpensIt_andATripBlockOnlyMoves() {
        show()
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { click(centerLeft.copy(x = width * 0.9f)) }
        assertThat(opened).containsExactly("c")
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { click(centerLeft.copy(x = width * (2.5f / 8f))) }
        assertThat(opened).containsExactly("c")
        assertThat(time).isEqualTo(at(11)) // 10:30 in the trip snaps to the nearer start (ties go earlier: 8:00 is 2.5 h away)
    }

    @Test
    fun controlsMeetTouchTargetsAndTheTimeLabelOpensTheJump() {
        show()
        listOf("map_scrub_previous", "map_scrub_next", "map_scrub_time", "map_scrubber").forEach {
            composeRule.onNodeWithTag(it).assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithTag("map_scrub_time").assertWidthIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("OK").assertExists()
    }

    @Test
    fun aNewModelMidDragDoesNotCancelTheDragOrItsSnap() {
        // Today the model is rebuilt whenever a ping arrives; the gesture must survive it and still snap on release.
        val (start, end) = dayBounds(day, zone)
        var model by mutableStateOf(ScrubberModel(items.toScrubItems(), start, end, emptyList()))
        composeRule.setContent {
            TarryTheme {
                DayScrubber(
                    model, emptyMap(), time, { time = it }, { opened += it },
                    date = day, today = day, zone = zone, onJump = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("map_scrubber").performTouchInput {
            down(centerLeft.copy(x = width * 0.05f))
            moveBy(Offset(width * 0.2f, 0f))
        }
        model = ScrubberModel(items.toScrubItems(), start, end, listOf(RoutePoint(at(9), -33.9, 151.2, 5f)))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map_scrubber").performTouchInput {
            moveBy(Offset(width * 0.1f, 0f)) // now ~35 % of 8:00–16:00 ≈ 10:48
            up()
        }
        assertThat(time).isEqualTo(at(11))
    }

    @Test
    fun theStripReportsItsTimeToTalkBack() {
        show()
        val node = composeRule.onNodeWithTag("map_scrubber").fetchSemanticsNode()
        assertThat(node.config[SemanticsProperties.StateDescription]).isNotEmpty()
        assertThat(node.config[SemanticsProperties.ProgressBarRangeInfo].current).isEqualTo(0f)
    }

    private fun boundsOf(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun checkPillBand(scale: Float) {
        show(fontScale = scale)
        val pill = boundsOf("map_scrub_time")
        assertThat(pill.height).isAtLeast(composeRule.density.run { 48.dp.toPx() } - 1f)
        assertThat(pill.overlaps(boundsOf("map_scrub_add_job"))).isFalse()
        assertThat(pill.overlaps(boundsOf("map_scrubber"))).isFalse()
        // A tap on the visible pill's bottom edge is the pill's, not the strip's.
        composeRule.onNodeWithTag("map_scrub_time").performTouchInput { click(Offset(centerX, height - 4.dp.toPx() - 1f)) }
        composeRule.onNodeWithText("OK").assertExists()
    }

    @Test fun theTimePillHasItsOwnBandAtNormalText() = checkPillBand(1f)

    @Test fun theTimePillHasItsOwnBandAtLargeText() = checkPillBand(2f)

    private fun cardTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.let { out += it.joinToString("") { t -> t.text } }
            n.children.forEach(::walk)
        }
        walk(composeRule.onNodeWithTag("map_scrub_card", useUnmergedTree = true).fetchSemanticsNode())
        return out.map { it.replace(Char(0x00A0), ' ').replace(Char(0x202F), ' ').replace(Char(0x200B).toString(), "") }
    }

    @Test
    fun theVisitCardReadsTheTimelinesCompactRangeAndDuration() {
        show()
        assertThat(cardTexts()).contains("8:00–10:00 AM · 2 h")
        assertThat(cardTexts()).contains("Office")
    }

    @Test
    fun aVisitCrossingMidnightKeepsItsNoteAndDurationAtFontScale2() {
        val night = TimelineItem.Visit("n", at(22), at(22) + 4 * 3_600_000L, "Night shift")
        time = at(22)
        show(fontScale = 2f, rows = listOf(night))
        val sub = cardTexts().single { it.startsWith("10:00 PM") }
        assertThat(sub).isEqualTo("10:00 PM–12:00 AM (continues) · 2 h")
        val results = mutableListOf<TextLayoutResult>()
        val node = composeRule.onNode(hasText("(continues)", substring = true), useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertThat(results.single().hasVisualOverflow).isFalse()
        assertThat(results.single().lineCount).isAtMost(2)
    }

    @Test
    fun addJobIsAnnouncedOnce() {
        show()
        val config = composeRule.onNodeWithTag("map_scrub_add_job").fetchSemanticsNode().config
        assertThat(config.getOrNull(SemanticsProperties.Text)).isNull()
        assertThat(config[SemanticsProperties.ContentDescription]).containsExactly("Add job to Office")
    }


    private fun overlayHeightPx() = composeRule.onNodeWithTag("map_overlay").fetchSemanticsNode().size.height

    @Test
    fun theMinimiseButtonSitsLeftOfOpenWithTheSameSize() {
        time = at(11)
        show()
        val minimise = boundsOf("map_scrub_minimise")
        val open = boundsOf("map_scrub_open")
        assertThat(minimise.right).isAtMost(open.left + 1f)
        // A small gap between the two round buttons (8 dp).
        val gapPx = with(composeRule.density) { 8.dp.toPx() }
        assertThat(open.left - minimise.right).isWithin(1f).of(gapPx)
        assertThat(minimise.width).isWithin(1f).of(open.width)
        assertThat(minimise.height).isWithin(1f).of(open.height)
        composeRule.onNodeWithTag("map_scrub_minimise").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun onAGapCardMinimiseTakesTheOpenButtonsPlace() {
        time = at(13, 30)
        show()
        composeRule.onNodeWithTag("map_scrub_open").assertDoesNotExist()
        composeRule.onNodeWithTag("map_scrub_minimise").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertThat(boundsOf("map_scrub_minimise").right).isAtMost(boundsOf("map_scrub_card").right + 1f)
    }

    @Test
    fun minimisingHidesTheCardAndShowsMaximiseRightOfTheStrip_andStripStillScrubs() {
        show()
        composeRule.onNodeWithTag("map_scrub_maximise").assertDoesNotExist()
        val expandedHeight = overlayHeightPx()
        composeRule.onNodeWithTag("map_scrub_minimise").performClick()
        composeRule.waitForIdle()
        assertThat(minimisedChanges).containsExactly(true)
        composeRule.onNodeWithTag("map_scrub_card").assertDoesNotExist()
        composeRule.onNodeWithTag("map_scrub_maximise").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertThat(boundsOf("map_scrub_next").right).isAtMost(boundsOf("map_scrub_maximise").left + 1f)
        // The overlay is shorter, so the map's bottom inset (its measured height) shrinks with it.
        assertThat(overlayHeightPx()).isLessThan(expandedHeight)
        // The strip scrubs exactly as before.
        composeRule.onNodeWithTag("map_scrubber").performTouchInput {
            down(Offset(width * 0.05f, height / 2f))
            moveBy(Offset(width * 0.45f, 0f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { up() }
        assertThat(time).isEqualTo(at(11))
        assertThat(minimisedChanges).containsExactly(true)
        // And maximise brings the card back.
        composeRule.onNodeWithTag("map_scrub_maximise").performClick()
        composeRule.waitForIdle()
        assertThat(minimisedChanges).containsExactly(true, false).inOrder()
        composeRule.onNodeWithTag("map_scrub_maximise").assertDoesNotExist()
        composeRule.onNodeWithTag("map_scrub_card").assertExists()
        assertThat(overlayHeightPx()).isEqualTo(expandedHeight)
    }

    @Test
    fun aVerticalSwipeDownMinimisesAndUpMaximises() {
        show()
        composeRule.onNodeWithTag("map_scrub_card").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertThat(minimised).isTrue()
        composeRule.onNodeWithTag("map_scrub_card").assertDoesNotExist()
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertThat(minimised).isFalse()
        composeRule.onNodeWithTag("map_scrub_card").assertExists()
    }

    @Test
    fun aHorizontalDragNeverTogglesTheOverlay() {
        show()
        composeRule.onNodeWithTag("map_scrub_card").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("map_scrub_card").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("map_scrubber").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("map_scrub_time").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertThat(minimisedChanges).isEmpty()
        assertThat(minimised).isFalse()
    }

    @Test
    fun reducedMotionTogglesInstantly() {
        show(reducedMotion = true)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("map_scrub_minimise").performClick()
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() } // 48 ms: far short of the 200 ms animation
        composeRule.onNodeWithTag("map_scrub_card").assertDoesNotExist()
        composeRule.onNodeWithTag("map_scrub_maximise").assertWidthIsEqualTo(48.dp)
    }

    @Test
    fun normalMotionAnimatesBeforeSettling() {
        show()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("map_scrub_minimise").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.onNodeWithTag("map_scrub_card").assertExists() // still collapsing
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.onNodeWithTag("map_scrub_card").assertDoesNotExist()
        composeRule.onNodeWithTag("map_scrub_maximise").assertWidthIsEqualTo(48.dp)
    }

    @Test
    fun theToggleButtonsAreDescribedAndExposeTheState() {
        show()
        val hide = composeRule.onNodeWithTag("map_scrub_minimise").fetchSemanticsNode().config
        assertThat(hide[SemanticsProperties.ContentDescription]).containsExactly("Hide details")
        assertThat(hide[SemanticsProperties.StateDescription]).isEqualTo("Details shown")
        composeRule.onNodeWithTag("map_scrub_minimise").performClick()
        composeRule.waitForIdle()
        val showing = composeRule.onNodeWithTag("map_scrub_maximise").fetchSemanticsNode().config
        assertThat(showing[SemanticsProperties.ContentDescription]).containsExactly("Show details")
        assertThat(showing[SemanticsProperties.StateDescription]).isEqualTo("Details hidden")
    }

    @Test
    fun startingMinimisedShowsNoCard() {
        minimised = true
        show()
        composeRule.onNodeWithTag("map_scrub_card").assertDoesNotExist()
        composeRule.onNodeWithTag("map_scrub_maximise").assertExists()
        composeRule.onNodeWithTag("map_scrub_time").assertExists()
        composeRule.onNodeWithTag("map_scrubber").assertExists()
    }

    private fun checkMinimisedLayout(dark: Boolean, scale: Float) {
        minimised = true
        show(dark, scale)
        composeRule.onNodeWithTag("map_scrub_maximise").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("map_scrubber").assertHeightIsAtLeast(64.dp)
        composeRule.onNodeWithTag("map_scrub_time").assertHeightIsAtLeast(48.dp)
        assertThat(boundsOf("map_scrub_time").overlaps(boundsOf("map_scrubber"))).isFalse()
        assertThat(boundsOf("map_scrub_next").right).isAtMost(boundsOf("map_scrub_maximise").left + 1f)
    }

    @Test fun minimisedLightNormalText() = checkMinimisedLayout(dark = false, scale = 1f)

    @Test fun minimisedLightLargeText() = checkMinimisedLayout(dark = false, scale = 2f)

    @Test fun minimisedDarkNormalText() = checkMinimisedLayout(dark = true, scale = 1f)

    @Test fun minimisedDarkLargeText() = checkMinimisedLayout(dark = true, scale = 2f)

    @Test
    fun theExpandedCardHasRoomForBothButtonsAtLargeText() {
        time = at(11)
        show(fontScale = 2f)
        assertThat(boundsOf("map_scrub_minimise").overlaps(boundsOf("map_scrub_open"))).isFalse()
        composeRule.onNodeWithTag("map_scrub_open").assertWidthIsAtLeast(48.dp)
    }
}
