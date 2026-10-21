package me.tarry.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.pow

/** Keeps the contrast table in docs/design/tokens.md true. Ratios are WCAG 2.x relative luminance. */
class ContrastGuardTest {
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

    private class Pairing(
        val name: String,
        val fg: (TarryColors) -> Color,
        val bg: (TarryColors) -> Color,
        val light: Double,
        val dark: Double,
        val minimum: Double = 4.5,
    )

    // Documented figures, rounded to 1 dp in tokens.md, so allow 0.05 of rounding.
    private val pairs = listOf(
        Pairing("ink on paper", { it.ink }, { it.paper }, 15.4, 16.0),
        Pairing("muted on paper", { it.muted }, { it.paper }, 5.6, 7.3),
        Pairing("muted on inset", { it.muted }, { it.inset }, 5.3, 5.6),
        Pairing("accentStrong on card", { it.accentStrong }, { it.card }, 6.3, 8.9),
        Pairing("onAccent on accent", { it.onAccent }, { it.accent }, 9.4, 9.4),
        Pairing("onAlertSoft on alertSoft", { it.onAlertSoft }, { it.alertSoft }, 7.2, 11.0),
        Pairing("alert on card", { it.alert }, { it.card }, 6.5, 9.8),
        Pairing("success on card", { it.success }, { it.card }, 7.7, 11.5),
        // UI component pair: 3:1 minimum rather than the text 4.5:1.
        Pairing("outline on paper", { it.outline }, { it.paper }, 3.1, 3.9, minimum = 3.0),
    )

    @Test
    fun documentedPairsMeetTheirContrastInBothThemes() {
        val failures = mutableListOf<String>()
        for (p in pairs) {
            val themes = listOf(Triple("light", LightTarryColors, p.light), Triple("dark", DarkTarryColors, p.dark))
            for ((themeName, colors, documented) in themes) {
                val r = ratio(p.fg(colors), p.bg(colors))
                if (r < documented - 0.05) failures += "$themeName ${p.name}: $r < documented $documented"
                if (r < p.minimum) failures += "$themeName ${p.name}: $r < minimum ${p.minimum}"
            }
        }
        assertThat(failures).isEmpty()
    }

    @Test
    fun everyJobSlotTextOnContainerIsAtLeast6Point6() {
        val failures = mutableListOf<String>()
        for (slot in JobPalette.slots) {
            for (dark in listOf(false, true)) {
                val c = slot.colors(dark)
                val r = ratio(c.text, c.container)
                if (r < 6.6 - 0.05) failures += "${slot.name} ${if (dark) "dark" else "light"}: $r"
            }
        }
        assertThat(failures).isEmpty()
    }
}
