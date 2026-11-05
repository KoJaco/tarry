package me.tarry.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.components.EmptyState
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.components.TarryCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h800dp")
class SurfaceEdgeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun close(a: Color, b: Color) =
        kotlin.math.abs(a.red - b.red) < 0.02f && kotlin.math.abs(a.green - b.green) < 0.02f && kotlin.math.abs(a.blue - b.blue) < 0.02f

    private fun map(dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) =
        run {
            composeRule.setContent { TarryTheme(darkTheme = dark) { Box(Modifier.padding(8.dp)) { content() } } }
            composeRule.onNodeWithTag("surface").captureToImage().toPixelMap()
        }

    private fun androidx.compose.ui.graphics.PixelMap.topCentre() = this[width / 2, 0]
    private fun androidx.compose.ui.graphics.PixelMap.centre() = this[width / 2, height / 2]

    @Test
    fun darkCard_hasALineEdge_andItsFillIsUntouched() {
        val m = map(dark = true) { TarryCard(Modifier.testTag("surface").width(200.dp).height(80.dp)) {} }
        assertThat(close(m.topCentre(), DarkTarryColors.line)).isTrue()
        assertThat(close(m.centre(), DarkTarryColors.card)).isTrue()
    }

    @Test
    fun lightCard_hasNoEdge() {
        val m = map(dark = false) { TarryCard(Modifier.testTag("surface").width(200.dp).height(80.dp)) {} }
        assertThat(close(m.topCentre(), LightTarryColors.card)).isTrue()
    }

    @Test
    fun darkSettingsGroup_andEmptyState_haveTheEdge() {
        val group = map(dark = true) {
            Box(Modifier.testTag("surface")) { SettingsGroup { row("Rounding", value = "Off") } }
        }
        assertThat(close(group.topCentre(), DarkTarryColors.line)).isTrue()
    }

    @Test
    fun darkEmptyState_hasTheEdge() {
        val m = map(dark = true) { Box(Modifier.testTag("surface")) { EmptyState("Nothing yet", "Body.") } }
        assertThat(close(m.topCentre(), DarkTarryColors.line)).isTrue()
    }

    @Test
    fun lightEmptyState_hasNoEdge() {
        val m = map(dark = false) { Box(Modifier.testTag("surface")) { EmptyState("Nothing yet", "Body.") } }
        assertThat(close(m.topCentre(), LightTarryColors.card)).isTrue()
    }

    @Test
    fun darkSheet_hasATopEdgeOnly() {
        val m = map(dark = true) {
            Box(Modifier.testTag("surface").width(200.dp).height(200.dp).background(DarkTarryColors.paper).sheetEdge())
        }
        assertThat(close(m.topCentre(), DarkTarryColors.line)).isTrue()
        // No edge down the sides or along the bottom.
        assertThat(close(m[0, m.height / 2], DarkTarryColors.paper)).isTrue()
        assertThat(close(m[m.width / 2, m.height - 1], DarkTarryColors.paper)).isTrue()
    }

    @Test
    fun lightSheet_hasNoEdge() {
        val m = map(dark = false) {
            Box(Modifier.testTag("surface").width(200.dp).height(200.dp).background(LightTarryColors.paper).sheetEdge())
        }
        assertThat(close(m.topCentre(), LightTarryColors.paper)).isTrue()
    }

    private fun stroke(dark: Boolean): androidx.compose.foundation.BorderStroke? {
        var result: androidx.compose.foundation.BorderStroke? = null
        composeRule.setContent { TarryTheme(darkTheme = dark) { result = surfaceEdgeStroke() } }
        composeRule.waitForIdle()
        return result
    }

    @Test
    fun dropdownMenuEdge_isALineStrokeInDark_andNoneInLight() {
        val dark = stroke(dark = true)!!
        assertThat(dark.width).isEqualTo(1.dp)
        assertThat((dark.brush as androidx.compose.ui.graphics.SolidColor).value).isEqualTo(DarkTarryColors.line)
    }

    @Test
    fun dropdownMenuEdge_noneInLight() {
        assertThat(stroke(dark = false)).isNull()
    }
}
