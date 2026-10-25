package me.tarry.app.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapAttributionTest {
    @Test
    fun creditFollowsTheFontScaleUpToTheCap() {
        assertThat(attributionFontFactor(1f)).isEqualTo(1f)
        assertThat(attributionFontFactor(1.3f)).isEqualTo(1f)
        // At font scale 2 the credit renders at 1.3x, so it stays one line over the map.
        assertThat(2f * attributionFontFactor(2f)).isWithin(1e-6f).of(1.3f)
    }
}
