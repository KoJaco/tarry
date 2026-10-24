package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MiniMapTest {
    private val key = MiniMapKey("p", GeoPoint(-33.0, 151.0), 75f, false, 800, 360)

    @Test
    fun keyChangesWithEveryInput() {
        assertThat(key).isEqualTo(key.copy())
        assertThat(key.copy(placeId = "q")).isNotEqualTo(key)
        assertThat(key.copy(centre = GeoPoint(-33.0001, 151.0))).isNotEqualTo(key)
        assertThat(key.copy(radiusM = 100f)).isNotEqualTo(key)
        assertThat(key.copy(isDark = true)).isNotEqualTo(key)
        assertThat(key.copy(widthPx = 801)).isNotEqualTo(key)
        assertThat(key.copy(heightPx = 361)).isNotEqualTo(key)
    }

    @Test
    fun fitRadiusLeavesMarginAndNeverCollapses() {
        val config = MiniMapConfig()
        assertThat(config.fitRadiusM(100f)).isWithin(1e-9).of(160.0)
        assertThat(config.fitRadiusM(0f)).isGreaterThan(0.0)
    }
}
