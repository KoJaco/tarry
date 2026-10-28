package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.assertNotTruncated
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

    @Test
    fun startAndStopLabelsRenderWithTheirTimes_evenOutsideTheVisits() {
        show(listOf(TrackingMarker(at(7, 2), null), TrackingMarker(null, at(17, 4), SessionEndReason.USER_PAUSE)))
        composeRule.onNodeWithText("Tracking started 7:02 AM", substring = true).assertExists()
        composeRule.onNodeWithText("Tracking paused 5:04 PM", substring = true).assertExists()
        composeRule.onAllNodesWithTag("timeline_tracking_marker").assertCountEquals(2)
    }

    @Test
    fun collapsedMarkerShowsBothTimesInOneLabel() {
        show(listOf(TrackingMarker(startedMs = at(12, 58), stoppedMs = at(12, 56), reason = SessionEndReason.USER_PAUSE)))
        composeRule.onNodeWithText("Tracking stopped 12:56 PM · started 12:58 PM", substring = true).assertExists()
        composeRule.onAllNodesWithTag("timeline_tracking_marker").assertCountEquals(1)
    }

    @Test
    fun labelIsNotTruncatedAtFontScale2() {
        show(listOf(TrackingMarker(startedMs = at(12, 58), stoppedMs = at(12, 56))), fontScale = 2f)
        composeRule.onNodeWithTag("timeline_tracking_marker").assertNotTruncated()
    }

    @Test
    fun noMarkersMeansNoLabels() {
        show(emptyList())
        assertThat(composeRule.onAllNodesWithTag("timeline_tracking_marker").fetchSemanticsNodes()).isEmpty()
    }
}
