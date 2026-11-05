package me.tarry.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.R
import me.tarry.app.ui.theme.DarkTarryColors
import me.tarry.app.ui.theme.LightTarryColors
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h800dp")
class EmptyStateAndChipStyleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateTitle_isTitleLarge() {
        composeRule.setContent { TarryTheme { EmptyState("Nothing yet", "Visits appear here.") } }
        val layout = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("Nothing yet").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action?.invoke(layout)
        assertThat(layout.single().layoutInput.style.fontSize.value).isEqualTo(20f)
    }

    @Test
    fun noEmptyStateTitleEndsInAFullStop() {
        val titles = R.string::class.java.fields
            .filter { it.name.contains("empty") && it.name.endsWith("title") || it.name.contains("empty_title") }
        assertThat(titles).isNotEmpty()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        for (field in titles) {
            val text = context.getString(field.getInt(null))
            assertThat(text.trimEnd()).doesNotMatch(".*[.。]$")
        }
    }

    /** The colour of the chip's top-centre pixel, which lies on its border when it has one. */
    private fun topEdge(selected: Boolean, dark: Boolean): Color {
        composeRule.setContent {
            TarryTheme(darkTheme = dark) {
                Box(Modifier.padding(8.dp)) {
                    TarryChip("Today", selected, {}, Modifier.testTag("chip"))
                }
            }
        }
        val map = composeRule.onNodeWithTag("chip").captureToImage().toPixelMap()
        return map[map.width / 2, 0]
    }

    private fun close(a: Color, b: Color) =
        kotlin.math.abs(a.red - b.red) < 0.02f && kotlin.math.abs(a.green - b.green) < 0.02f && kotlin.math.abs(a.blue - b.blue) < 0.02f

    @Test
    fun unselectedChip_hasALineBorder_inLight() {
        assertThat(close(topEdge(selected = false, dark = false), LightTarryColors.line)).isTrue()
    }

    @Test
    fun unselectedChip_hasALineBorder_inDark() {
        assertThat(close(topEdge(selected = false, dark = true), DarkTarryColors.line)).isTrue()
    }

    @Test
    fun selectedChip_hasNoBorder() {
        val edge = topEdge(selected = true, dark = false)
        assertThat(close(edge, LightTarryColors.ink)).isTrue()
    }
}
