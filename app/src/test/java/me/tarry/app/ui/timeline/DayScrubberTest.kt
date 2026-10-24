package me.tarry.app.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
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
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class DayScrubberTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 10, 21)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val items = listOf(
        TimelineItem.Visit("a", at(8), at(10), "Office"),
        TimelineItem.Trip("t", at(10), at(11), 900f, TripMode.VEHICLE),
        TimelineItem.Visit("b", at(11), at(13), "Site"),
        TimelineItem.Gap("g", at(13), at(14), GapCause.NO_FIX),
        TimelineItem.Visit("c", at(14), at(16), "Yard"),
    )
    private var time by mutableLongStateOf(at(8))
    private val opened = mutableListOf<String>()

    private fun show() {
        val (start, end) = dayBounds(day, zone)
        val model = ScrubberModel(items.toScrubItems(), start, end, listOf(RoutePoint(at(8), -33.9, 151.2, 5f)))
        composeRule.setContent {
            TarryTheme {
                DayScrubber(
                    model, items.filterIsInstance<TimelineItem.Visit>(), time, { time = it }, { opened += it },
                    date = day, today = day, zone = zone, onJump = { _, _ -> },
                )
            }
        }
    }

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
                    model, items.filterIsInstance<TimelineItem.Visit>(), time, { time = it }, { opened += it },
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
}
