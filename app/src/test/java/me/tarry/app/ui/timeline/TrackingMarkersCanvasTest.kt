package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w320dp-h1000dp")
class TrackingMarkersCanvasTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private fun at(hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val items = listOf(TimelineItem.Visit("v", at(9), at(10), "Marina"))

    private fun show(markers: List<TrackingMarker>, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TarryTheme {
                    TimelineContent(date, items, {}, {}, today = date, zone = zone, nowMs = at(20), trackingMarkers = markers)
                }
            }
        }
    }

    private fun lines(): List<String> = composeRule.onAllNodesWithTag("timeline_tracking_marker_line", useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].single().text }

    private fun hourLabels(): List<String> = composeRule.onAllNodesWithTag(HourLabelTag, useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].single().text }

    @Test
    fun startAndStopShowTheWordOverTheTimeInTheTimeColumn_andAreSpokenInFull() {
        show(listOf(TrackingMarker(at(7, 2), null), TrackingMarker(null, at(17, 4), SessionEndReason.SCHEDULE_END)))
        assertThat(lines()).containsExactly("Start", "7:02 AM", "Stop", "5:04 PM").inOrder()
        composeRule.onNodeWithContentDescription("Tracking started 7:02 AM").assertExists()
        composeRule.onNodeWithContentDescription("Tracking ended 5:04 PM").assertExists()
        composeRule.onAllNodesWithTag("timeline_tracking_marker").assertCountEquals(2)
    }

    @Test
    fun theLabelSitsLeftOfTheBlocks_notAcrossTheCanvas() {
        show(listOf(TrackingMarker(null, at(9, 30), SessionEndReason.USER_PAUSE)))
        val marker = composeRule.onNodeWithTag("timeline_tracking_marker").fetchSemanticsNode().boundsInRoot
        val visit = composeRule.onNodeWithTag("timeline_row_v").fetchSemanticsNode().boundsInRoot
        assertThat(marker.right).isAtMost(visit.left)
    }

    @Test
    fun anHourLabelThatWouldCollideWithAMarkerIsHidden_otherHoursStay() {
        show(listOf(TrackingMarker(null, at(9, 5))))
        val labels = hourLabels()
        assertThat(labels.none { it.startsWith("9") }).isTrue()
        assertThat(labels.any { it.startsWith("12") }).isTrue()
    }

    @Test
    fun withoutMarkersEveryHourLabelShows() {
        show(emptyList())
        assertThat(hourLabels().any { it.startsWith("9") }).isTrue()
    }

    @Test
    fun aCollapsedPairIsOneLabel_OffOverTheRange() {
        show(listOf(TrackingMarker(startedMs = at(12, 20), stoppedMs = at(12, 5), reason = SessionEndReason.USER_PAUSE)))
        assertThat(lines()).containsExactly("Off", "12:05 PM–12:20 PM").inOrder()
        composeRule.onNodeWithContentDescription("Tracking stopped 12:05 PM · started 12:20 PM").assertExists()
        composeRule.onAllNodesWithTag("timeline_tracking_marker").assertCountEquals(1)
    }

    @Test
    fun spokenMarkersFollowTimeOrderBetweenTheBlocks() {
        show(listOf(TrackingMarker(at(7, 2), null), TrackingMarker(null, at(17, 4))))
        val index = { text: String ->
            composeRule.onNodeWithContentDescription(text).fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        }
        // The visit (9:00-10:00) sits between them: the start is before it, the stop after.
        assertThat(index("Tracking started 7:02 AM")).isLessThan(index("Tracking stopped 5:04 PM"))
    }

    @Test
    fun theTimeColumnGrowsToFitTheLabelAtFontScale2() {
        show(listOf(TrackingMarker(startedMs = at(12, 20), stoppedMs = at(12, 5), reason = SessionEndReason.USER_PAUSE)), fontScale = 2f)
        val marker = composeRule.onNodeWithTag("timeline_tracking_marker").fetchSemanticsNode().boundsInRoot
        val visit = composeRule.onNodeWithTag("timeline_row_v").fetchSemanticsNode().boundsInRoot
        assertThat(marker.left).isAtLeast(0f)
        assertThat(marker.right).isAtMost(visit.left)
    }

    @Test
    fun noMarkersMeansNoLabels() {
        show(emptyList())
        assertThat(composeRule.onAllNodesWithTag("timeline_tracking_marker").fetchSemanticsNodes()).isEmpty()
    }
}
