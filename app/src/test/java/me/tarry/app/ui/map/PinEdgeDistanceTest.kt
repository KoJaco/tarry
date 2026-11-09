package me.tarry.app.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinEdgeDistanceTest {
    @Test
    fun distanceIsMeasuredToTheCircleEdgeInPixels() {
        // Radius 13 style units at 2 px per unit is a 26 px circle.
        assertThat(pinEdgeDistancePx(40f, 13f, 2f)).isEqualTo(14f)
        assertThat(pinEdgeDistancePx(10f, 13f, 2f)).isEqualTo(-16f)
    }

    @Test
    fun aWideGroupBeatsASmallPinWhoseCentreIsNearerTheTap() {
        val wide = pinEdgeDistancePx(30f, 30f, 1f)
        val small = pinEdgeDistancePx(20f, 13f, 1f)
        assertThat(wide).isLessThan(small)
    }
}
