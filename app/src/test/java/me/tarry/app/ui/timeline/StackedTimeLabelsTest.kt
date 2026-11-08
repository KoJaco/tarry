package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.SessionEndReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** From font scale 1.5 the time column is only the rail and the labels sit above their lines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1000dp")
class StackedTimeLabelsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private fun at(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val items = listOf(TimelineItem.Visit("v", at(9), at(11), "Marina"))

    private fun show(fontScale: Float, markers: List<TrackingMarker> = emptyList(), nowMs: Long = at(20)) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TarryTheme { TimelineContent(date, items, {}, {}, today = date, zone = zone, nowMs = nowMs, trackingMarkers = markers) }
            }
        }
    }

    /** Hour label text to its bounds (dp, unclipped, so labels below the viewport still measure). */
    private fun hourLabels(): List<Pair<String, DpRect>> {
        val all = composeRule.onAllNodesWithTag(HourLabelTag, useUnmergedTree = true)
        return all.fetchSemanticsNodes().indices.map { i ->
            all[i].fetchSemanticsNode().config[SemanticsProperties.Text].single().text to all[i].getUnclippedBoundsInRoot()
        }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    fun thresholdIs1Point5() {
        assertThat(stackedTimeLabels(1.49f)).isFalse()
        assertThat(stackedTimeLabels(1.5f)).isTrue()
        assertThat(stackedTimeLabels(2f)).isTrue()
    }

    @Test
    fun jobsGetAWiderShareOnceTheTimeColumnHasCollapsed() {
        assertThat(DayColumns.jobFraction(2f)).isAtLeast(0.3f)
        assertThat(DayColumns.jobFraction(1.49f)).isLessThan(0.3f)
    }

    @Test
    fun atFontScale2HourLabelsEndAtTheirLineAndStartLeftOfTheBlocks_theBlocksGetTheFreedWidth() {
        show(2f)
        val block = bounds("timeline_row_v")
        val ten = hourLabels().first { it.first.startsWith("10") }.second
        // The visit starts at the canvas top (9:00); 10:00 is one hour of 2 x 144 dp below. The label ends at that line.
        assertThat(ten.bottom.value).isWithin(2f).of(block.top.value + 288f)
        assertThat(ten.left.value).isLessThan(block.left.value)
        // 20 dp gutter + 8 dp card padding + 12 dp rail + 4 dp inset: no 100 dp-wide side column any more.
        assertThat(block.left.value).isAtMost(50f)
    }

    @Test
    fun below1Point5TheTimeColumnIsUnchanged() {
        show(1.4f)
        val block = bounds("timeline_row_v")
        hourLabels().forEach { assertThat(it.second.right.value).isAtMost(block.left.value) }
        assertThat(block.left.value).isAtLeast(60f)
    }

    @Test
    fun markerLabelSitsAboveItsTickOnTheLeftAtLargeFonts() {
        show(2f, markers = listOf(TrackingMarker(at(7, 30), null)))
        val marker = bounds("timeline_tracking_marker")
        val visit = bounds("timeline_row_v")
        // 7:30 is 1.5 h x 288 dp above the 9 o'clock visit; the label ends at that tick, not centred on it.
        assertThat(marker.bottom.value).isAtMost(visit.top.value - 432f + 1f)
        assertThat(marker.bottom.value).isAtLeast(visit.top.value - 432f - 4f)
        assertThat(marker.left.value).isAtMost(visit.left.value)
    }

    @Test
    fun anHourLabelThatWouldOverlapAMarkerLabelIsHidden_atLargeFonts() {
        show(2f, markers = listOf(TrackingMarker(null, at(10, 5), SessionEndReason.USER_PAUSE)))
        val marker = bounds("timeline_tracking_marker")
        hourLabels().forEach {
            assertThat(it.second.bottom <= marker.top || it.second.top >= marker.bottom).isTrue()
        }
    }

    @Test
    fun stackedHourMinorAndMarkerLabelsAreComposedBeforeTheBlocks_soTheyDrawUnderThem() {
        show(2f, markers = listOf(TrackingMarker(at(7, 30), null)))
        // Semantics ids are handed out in composition order, which is the draw order: earlier is underneath.
        val blockId = composeRule.onNodeWithTag("timeline_row_v").fetchSemanticsNode().id
        val hourIds = composeRule.onAllNodesWithTag(HourLabelTag, useUnmergedTree = true).fetchSemanticsNodes().map { it.id }
        val markerId = composeRule.onNodeWithTag("timeline_tracking_marker").fetchSemanticsNode().id
        assertThat(hourIds).isNotEmpty()
        hourIds.forEach { assertThat(it).isLessThan(blockId) }
        assertThat(markerId).isLessThan(blockId)
    }
}
