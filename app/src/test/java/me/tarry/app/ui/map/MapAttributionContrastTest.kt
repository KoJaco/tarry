package me.tarry.app.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.DarkTarryColors
import me.tarry.app.ui.theme.LightTarryColors
import me.tarry.app.ui.timeline.contrastRatio
import org.junit.Test

class MapAttributionContrastTest {
    @Test
    fun creditTextReachesFourPointFiveToOneInBothThemesOverAnyTile() {
        listOf(LightTarryColors, DarkTarryColors).forEach { colors ->
            // The pill is translucent: check the worst tiles, pure white and pure black.
            listOf(Color.White, Color.Black).forEach { tile ->
                val pill = attributionBackdrop(colors).compositeOver(tile)
                assertThat(contrastRatio(colors.ink, pill)).isAtLeast(4.5)
            }
        }
    }
}
