package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NowPillTest {
    @Test
    fun pillHalfHeightIsOneMeasuredLinePlusItsVerticalPadding() {
        val pad = nowPillHalfHeightDp(0f)
        assertThat(nowPillHalfHeightDp(16f)).isEqualTo(8f + pad)
        assertThat(nowPillHalfHeightDp(32f)).isGreaterThan(nowPillHalfHeightDp(16f))
    }
}
