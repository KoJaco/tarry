package me.tarry.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.pow

/** Keeps the "disabled icon" row of the contrast table in docs/design/tokens.md true. WCAG 2.x relative luminance. */
class DisabledIconContrastTest {
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun disabledOn(colors: TarryColors, bg: Color) = ratio(colors.disabledIcon.compositeOver(bg), bg)

    @Test
    fun alphaIsMutedAt72Percent() {
        assertThat(DISABLED_ICON_ALPHA).isEqualTo(0.72f)
        assertThat(LightTarryColors.disabledIcon).isEqualTo(LightTarryColors.muted.copy(alpha = 0.72f))
    }

    @Test
    fun disabledIconReachesThreeToOne_onPaperAndCard_inBothThemes() {
        for (colors in listOf(LightTarryColors, DarkTarryColors)) {
            assertThat(disabledOn(colors, colors.paper)).isAtLeast(3.0)
            assertThat(disabledOn(colors, colors.card)).isAtLeast(3.0)
        }
    }

    @Test
    fun documentedRatios() {
        assertThat(disabledOn(LightTarryColors, LightTarryColors.paper)).isWithin(0.05).of(3.1)
        assertThat(disabledOn(LightTarryColors, LightTarryColors.card)).isWithin(0.05).of(3.4)
        assertThat(disabledOn(DarkTarryColors, DarkTarryColors.paper)).isWithin(0.05).of(4.3)
        assertThat(disabledOn(DarkTarryColors, DarkTarryColors.card)).isWithin(0.05).of(4.1)
    }

    @Test
    fun disabledIconStaysClearlyLighterThanTheEnabledInkIcon() {
        for (colors in listOf(LightTarryColors, DarkTarryColors)) {
            val enabled = ratio(colors.ink, colors.card)
            val disabled = disabledOn(colors, colors.card)
            assertThat(disabled).isLessThan(enabled / 2)
        }
    }
}
