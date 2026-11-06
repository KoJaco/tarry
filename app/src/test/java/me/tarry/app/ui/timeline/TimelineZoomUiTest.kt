package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Zoom behaviour of the whole timeline screen: the Zoom menu, the two-finger pinch and the one-finger scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w320dp-h1000dp")
class TimelineZoomUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private var zoom by mutableStateOf(ZoomLevel.Q15)
    private val zoomChanges = mutableListOf<ZoomLevel>()

    private fun at(hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun show(fontScale: Float = 1f) {
        val items = listOf(
            TimelineItem.Visit("a", at(9), at(11), "A"),
            TimelineItem.Visit("b", at(13), at(15), "B"),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TarryTheme {
                    TimelineContent(
                        date, items, {}, {}, today = date.plusDays(1), zone = zone,
                        zoomLevel = zoom,
                        onZoomLevelChange = { zoom = it; zoomChanges += it },
                    )
                }
            }
        }
    }

    private fun select(id: String) {
        composeRule.onNodeWithTag("timeline_row_$id").performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
    }

    /** Distance in px between the first two hour labels. */
    private fun hourSpacing(): Float {
        val tops = composeRule.onAllNodesWithTag(HourLabelTag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.positionInRoot.y }.sorted()
        return tops[1] - tops[0]
    }

    private fun firstLabelTop(): Float =
        composeRule.onAllNodesWithTag(HourLabelTag, useUnmergedTree = true).fetchSemanticsNodes()
            .minOf { it.positionInRoot.y }

    private fun assertHandlesCentredOnVisit(fontScale: Float) {
        show(fontScale)
        select("a")
        val density = composeRule.density.density
        // Unclipped: at font scale 2 the block runs past the bottom of the viewport.
        fun centreX(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionInRoot.x + it.size.width / 2f }
        val block = centreX("timeline_row_a")
        listOf(ResizeEdge.Start, ResizeEdge.End).forEach { edge ->
            val handle = centreX(handleTag(edge, "a"))
            assertThat((handle - block) / density).isWithin(1f).of(0f)
        }
    }

    @Test
    fun selectedVisitsHandlesAreCentredOnTheVisitAtFontScale1() = assertHandlesCentredOnVisit(1f)

    @Test
    fun selectedVisitsHandlesAreCentredOnTheVisitAtFontScale2() = assertHandlesCentredOnVisit(2f)

    @Test
    fun choosingFiveMinutesTriplesTheSpacingBetweenHourMarks() {
        show()
        val before = hourSpacing()
        composeRule.onNodeWithTag("timeline_zoom").performClick()
        composeRule.onNodeWithTag("timeline_zoom_5").performClick()
        composeRule.waitForIdle()
        assertThat(zoom).isEqualTo(ZoomLevel.Q5)
        assertThat(hourSpacing()).isWithin(1f).of(before * 3f)
    }

    @Test
    fun aTwoFingerPinchEnlargesTheCanvasAndLandsOnALevel() {
        show()
        val before = hourSpacing()
        // Fingers 100 px apart spread to 170 px: a ratio of 1.7, which is nearest the 2x level.
        composeRule.onNodeWithTag("timeline_row_a").performTouchInput {
            val mid = center
            pinch(
                Offset(mid.x, mid.y - 50f), Offset(mid.x, mid.y - 85f),
                Offset(mid.x, mid.y + 50f), Offset(mid.x, mid.y + 85f),
            )
        }
        composeRule.waitForIdle()
        assertThat(hourSpacing()).isGreaterThan(before * 1.5f)
        assertThat(zoomChanges).isNotEmpty()
        assertThat(zoom).isEqualTo(ZoomLevel.Q10)
        assertThat(hourSpacing()).isWithin(1f).of(before * ZoomLevel.Q10.factor)
    }

    @Test
    fun aSidewaysPinchDoesNotZoom() {
        show()
        val before = hourSpacing()
        composeRule.onNodeWithTag("timeline_row_a").performTouchInput {
            val mid = center
            pinch(
                Offset(mid.x - 50f, mid.y), Offset(mid.x - 90f, mid.y),
                Offset(mid.x + 50f, mid.y), Offset(mid.x + 90f, mid.y),
            )
        }
        composeRule.waitForIdle()
        assertThat(hourSpacing()).isWithin(0.5f).of(before)
        assertThat(zoom).isEqualTo(ZoomLevel.Q15)
    }

    @Test
    fun aOneFingerVerticalSwipeScrollsAndDoesNotZoom() {
        show()
        val spacing = hourSpacing()
        val top = firstLabelTop()
        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertThat(firstLabelTop()).isLessThan(top)
        assertThat(hourSpacing()).isWithin(0.5f).of(spacing)
        assertThat(zoom).isEqualTo(ZoomLevel.Q15)
        assertThat(zoomChanges).isEmpty()
    }
}
