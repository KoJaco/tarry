package me.tarry.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.assertNotTruncated
import me.tarry.app.ui.shell.TarryBottomBar
import me.tarry.app.ui.textLayout
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The toggle label in a one-third slot of a 360 dp screen at font scale 2: it may wrap, but never inside a word. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xxhdpi")
class ToggleLabelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(state: TrackingState, fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                TarryTheme {
                    Box(Modifier.width(360.dp)) { TarryBottomBar(state, true, false, {}, {}, {}) }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertWholeWords(label: String) {
        val layout = composeRule.onNodeWithText(label).assertNotTruncated().textLayout()
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertThat(label[end - 1].isWhitespace() || label[end].isWhitespace()).isTrue()
        }
        // Every word of the label is intact on some line.
        val lines = (0 until layout.lineCount).map { label.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
        label.split(' ').forEach { word -> assertThat(lines.any { word in it.split(' ') }).isTrue() }
    }

    @Test
    fun needsAttentionNeverSplitsAWordAtFontScaleTwo() {
        show(TrackingState.ATTENTION, 2f)
        assertWholeWords("Needs attention")
    }

    @Test
    fun trackingNeverSplitsAWordAtFontScaleTwo() {
        show(TrackingState.TRACKING, 2f)
        assertWholeWords("Tracking")
    }

    @Test
    fun needsAttentionKeepsItsFullSizeAtNormalFontScale() {
        show(TrackingState.ATTENTION, 1f)
        assertWholeWords("Needs attention")
    }
}
