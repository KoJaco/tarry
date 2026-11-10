package me.tarry.app.ui.timeline

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w320dp-h1000dp")
class OffStretchCanvasTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private fun at(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun show(items: List<TimelineItem>, markers: List<TrackingMarker>, nowMs: Long = at(20), today: LocalDate = date) {
        composeRule.setContent {
            TarryTheme { TimelineContent(date, items, {}, {}, today = today, zone = zone, nowMs = nowMs, trackingMarkers = markers) }
        }
    }

    private val visit = TimelineItem.Visit("v", at(9), at(11), "Marina")

    @Test
    fun anOffStretchIsOneSpokenNodeWithItsLabel() {
        show(listOf(visit), listOf(TrackingMarker(null, at(11, 0)), TrackingMarker(at(13, 0), null)))
        val node = composeRule.onNodeWithTag("timeline_off_stretch").fetchSemanticsNode()
        assertThat(node.config[SemanticsProperties.ContentDescription].single()).isEqualTo("Not tracking, 11:00 AM to 1:00 PM")
        composeRule.onNodeWithText("Not tracking").assertExists()
        // Two hours at 144 dp an hour, starting at 11:00.
        val b = composeRule.onNodeWithTag("timeline_off_stretch").getUnclippedBoundsInRoot()
        assertThat(b.height.value).isWithin(1f).of(288f)
    }

    @Test
    fun noHatchIsDrawnOverAVisitThatContinuesAcrossTheStretch() {
        val continuing = TimelineItem.Visit("v2", at(9), at(14), "Marina")
        show(listOf(continuing), listOf(TrackingMarker(null, at(11, 0)), TrackingMarker(at(11, 20), null)))
        composeRule.onAllNodesWithTag("timeline_off_stretch").assertCountEquals(0)
    }

    @Test
    fun noStopMeansNoStretch() {
        show(listOf(visit), listOf(TrackingMarker(at(8, 0), null)))
        composeRule.onAllNodesWithTag("timeline_off_stretch").assertCountEquals(0)
    }

    @Test
    fun aStopAfterWhichTrackingNeverRestartedRunsToNowOnToday() {
        show(listOf(visit), listOf(TrackingMarker(null, at(11, 0))), nowMs = at(15, 0))
        val b = composeRule.onNodeWithTag("timeline_off_stretch").getUnclippedBoundsInRoot()
        assertThat(b.height.value).isWithin(1f).of(4 * 144f)
    }

    @Test
    fun theLabelIsLeftOutWhenAGapCoversTheTopOfTheStretch() {
        val gap = TimelineItem.Gap("g", at(11, 0), at(12, 0), me.tarry.core.data.model.GapCause.NO_FIX)
        show(listOf(visit, gap), listOf(TrackingMarker(null, at(11, 0)), TrackingMarker(at(13, 0), null)))
        composeRule.onAllNodesWithTag("timeline_off_stretch").assertCountEquals(1)
        composeRule.onNodeWithText("Not tracking").assertDoesNotExist()
    }

    @Test
    fun offLabelNeedsRoomAndNoBlockOnTop() {
        val off = PlacedOff(OffStretch(0, 1), 100f, 40f)
        assertThat(offLabelFits(off, 16f, emptyList())).isTrue()
        assertThat(offLabelFits(PlacedOff(OffStretch(0, 1), 100f, 10f), 16f, emptyList())).isFalse()
        val block = PlacedBlock(TimelineItem.Gap("g", 0, 1, me.tarry.core.data.model.GapCause.NO_FIX), 90f, 30f, false, emptyList())
        assertThat(offLabelFits(off, 16f, listOf(block))).isFalse()
    }
}
