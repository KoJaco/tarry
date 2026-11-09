package me.tarry.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollEdgeFadeBandsTest {
    @Test
    fun atTheStart_onlyTheRightEdgeFades() {
        assertThat(scrollEdgeFadeBands(canScrollBackward = false, canScrollForward = true, widthPx = 400f, fadePx = 64f))
            .containsExactly(336f..400f)
    }

    @Test
    fun inTheMiddle_bothEdgesFade() {
        assertThat(scrollEdgeFadeBands(canScrollBackward = true, canScrollForward = true, widthPx = 400f, fadePx = 64f))
            .containsExactly(0f..64f, 336f..400f).inOrder()
    }

    @Test
    fun atTheEnd_onlyTheLeftEdgeFades() {
        assertThat(scrollEdgeFadeBands(canScrollBackward = true, canScrollForward = false, widthPx = 400f, fadePx = 64f))
            .containsExactly(0f..64f)
    }

    @Test
    fun whenEverythingFits_nothingFades() {
        assertThat(scrollEdgeFadeBands(canScrollBackward = false, canScrollForward = false, widthPx = 400f, fadePx = 64f)).isEmpty()
    }

    @Test
    fun aFadeNeverReachesPastTheMiddle() {
        assertThat(scrollEdgeFadeBands(canScrollBackward = true, canScrollForward = true, widthPx = 100f, fadePx = 64f))
            .containsExactly(0f..50f, 50f..100f).inOrder()
    }

    @Test
    fun theFadeIsThirtyTwoDp() {
        assertThat(ChipsEdgeFadeWidth.value).isEqualTo(32f)
    }
}

class ScrollDeltaToRevealTest {
    @Test
    fun fullyInside_doesNotScroll() {
        assertThat(scrollDeltaToReveal(10f, 90f, 100f)).isEqualTo(0f)
    }

    @Test
    fun cutOffAtTheEnd_scrollsForwardByTheOverhang() {
        assertThat(scrollDeltaToReveal(60f, 130f, 100f)).isEqualTo(30f)
    }

    @Test
    fun cutOffAtTheStart_scrollsBack() {
        assertThat(scrollDeltaToReveal(-25f, 40f, 100f)).isEqualTo(-25f)
    }

    @Test
    fun widerThanTheViewport_putsItsStartFirst() {
        assertThat(scrollDeltaToReveal(20f, 220f, 100f)).isEqualTo(20f)
    }
}
