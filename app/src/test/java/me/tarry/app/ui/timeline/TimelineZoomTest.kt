package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimelineZoomTest {
    @Test
    fun tickMinutesSwitchAtTheThresholds() {
        assertThat(tickMinutesFor(1f)).isEqualTo(15)
        assertThat(tickMinutesFor(1.49f)).isEqualTo(15)
        assertThat(tickMinutesFor(1.5f)).isEqualTo(10)
        assertThat(tickMinutesFor(2.49f)).isEqualTo(10)
        assertThat(tickMinutesFor(2.5f)).isEqualTo(5)
        assertThat(tickMinutesFor(3f)).isEqualTo(5)
    }

    @Test
    fun nearestLevelPicksTheClosestFactor() {
        assertThat(nearestLevel(1f)).isEqualTo(ZoomLevel.Q15)
        assertThat(nearestLevel(1.4f)).isEqualTo(ZoomLevel.Q15)
        assertThat(nearestLevel(1.6f)).isEqualTo(ZoomLevel.Q10)
        assertThat(nearestLevel(2.4f)).isEqualTo(ZoomLevel.Q10)
        assertThat(nearestLevel(2.6f)).isEqualTo(ZoomLevel.Q5)
        assertThat(nearestLevel(3f)).isEqualTo(ZoomLevel.Q5)
    }

    @Test
    fun levelsMatchTheirSteps() {
        ZoomLevel.entries.forEach {
            assertThat(it.tickMinutes).isEqualTo(it.snapMinutes)
            assertThat(tickMinutesFor(it.factor)).isEqualTo(it.tickMinutes)
        }
        assertThat(ZoomLevel.fromMinutes(10)).isEqualTo(ZoomLevel.Q10)
        assertThat(ZoomLevel.fromMinutes(7)).isEqualTo(ZoomLevel.Q15)
    }

    @Test
    fun anchoredScrollKeepsTheFocalTimeFixed() {
        // Focal point at content y = 1000 with scroll 600: 400 px below the viewport top. Zoom 1 -> 2.
        val newScroll = anchoredScroll(oldScroll = 600, focalInContent = 1000f, oldFactor = 1f, newFactor = 2f)
        assertThat(newScroll).isEqualTo(1600)
        // The focal time now sits at 2000 in content, so 2000 - 1600 = 400 below the viewport top again.
        assertThat(1000f * 2f - newScroll).isEqualTo(1000f - 600)
    }

    @Test
    fun anchoredScrollGoesBackWhenZoomingOut() {
        assertThat(anchoredScroll(1600, 2000f, 2f, 1f)).isEqualTo(600)
    }

    @Test
    fun anchoredScrollUnchangedWithoutZoomChange() {
        assertThat(anchoredScroll(123, 500f, 2f, 2f)).isEqualTo(123)
    }

    @Test
    fun anchoredScrollMayGoNegativeForTheCallerToClamp() {
        assertThat(anchoredScroll(0, 100f, 3f, 1f)).isLessThan(0)
    }

    @Test
    fun steppingMovesOneLevelAndStopsAtTheLimits() {
        assertThat(ZoomLevel.Q15.zoomedOut()).isNull()
        assertThat(ZoomLevel.Q15.zoomedIn()).isEqualTo(ZoomLevel.Q10)
        assertThat(ZoomLevel.Q10.zoomedIn()).isEqualTo(ZoomLevel.Q5)
        assertThat(ZoomLevel.Q10.zoomedOut()).isEqualTo(ZoomLevel.Q15)
        assertThat(ZoomLevel.Q5.zoomedIn()).isNull()
    }

    @Test
    fun pinnedButtonsFollowTheViewportTopWithinTheCanvas() {
        // Canvas at 500 px in the content, 2000 px tall, buttons 100 px, inset 20 px.
        assertThat(pinnedTop(0, 500f, 20f, 2000f, 100f)).isEqualTo(0f)
        assertThat(pinnedTop(480, 500f, 20f, 2000f, 100f)).isEqualTo(0f)
        assertThat(pinnedTop(900, 500f, 20f, 2000f, 100f)).isEqualTo(420f)
        assertThat(pinnedTop(5000, 500f, 20f, 2000f, 100f)).isEqualTo(1900f)
    }

    @Test
    fun minorLabelRuleByTickLevel() {
        assertThat((1..3).map { minorMarkLabelled(15, it * 15) }).containsExactly(false, false, false)
        assertThat(listOf(10, 20, 30, 40, 50).map { minorMarkLabelled(10, it) }).doesNotContain(false)
        assertThat((5..55 step 5).filter { minorMarkLabelled(5, it) }).containsExactly(15, 30, 45).inOrder()
    }
}
