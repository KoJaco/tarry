package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HourLabelCoverTest {
    @Test
    fun labelNearTheNowLineIsHidden() {
        // Pill half (10) + label half (8) = 18 dp reach at scale 1.
        assertThat(hourLabelCoveredByNowPill(markDp = 720f, nowDp = 707f, fontScale = 1f)).isTrue()
        assertThat(hourLabelCoveredByNowPill(markDp = 720f, nowDp = 735f, fontScale = 1f)).isTrue()
    }

    @Test
    fun labelClearOfTheNowLineStays() {
        assertThat(hourLabelCoveredByNowPill(markDp = 720f, nowDp = 690f, fontScale = 1f)).isFalse()
        assertThat(hourLabelCoveredByNowPill(markDp = 720f, nowDp = null, fontScale = 1f)).isFalse()
    }

    @Test
    fun reachGrowsWithFontScale() {
        assertThat(hourLabelCoveredByNowPill(markDp = 720f, nowDp = 690f, fontScale = 2f)).isTrue()
    }
}
