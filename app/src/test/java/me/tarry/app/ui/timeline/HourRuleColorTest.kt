package me.tarry.app.ui.timeline

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.DarkTarryColors
import me.tarry.app.ui.theme.LightTarryColors
import org.junit.Test

/** Hour lines and ticks are `outline` and reach 3:1 on `card`; the minor marks stay the quiet `line` family. */
class HourRuleColorTest {
    private fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun hourRulesAreOutlineAndAtLeast3to1OnCardInBothThemes() {
        for (colors in listOf(LightTarryColors, DarkTarryColors)) {
            assertThat(hourRuleColor(colors)).isEqualTo(colors.outline)
            assertThat(ratio(hourRuleColor(colors), colors.card)).isAtLeast(3.0)
        }
    }

    @Test
    fun minorMarksStayFainterThanTheHourRules() {
        for (colors in listOf(LightTarryColors, DarkTarryColors)) {
            assertThat(ratio(minorTickColor(colors), colors.card)).isLessThan(ratio(hourRuleColor(colors), colors.card))
        }
    }

    @Test
    fun zoomPillIsInsetInBothThemes_notTheCardItSitsOn() {
        for (colors in listOf(LightTarryColors, DarkTarryColors)) {
            assertThat(zoomPillColor(colors)).isEqualTo(colors.inset)
            assertThat(zoomPillColor(colors)).isNotEqualTo(colors.card)
        }
    }
}
