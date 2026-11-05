package me.tarry.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.components.SheetEdgeHandle
import me.tarry.app.ui.components.TarrySheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet edge must sit on the sheet's own top edge. A `ModalBottomSheet` modifier stays at the window's top while the
 * content is offset, so the edge now lives in the drag handle inside the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h800dp")
class SheetEdgePlacementTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun close(a: Color, b: Color) =
        kotlin.math.abs(a.red - b.red) < 0.02f && kotlin.math.abs(a.green - b.green) < 0.02f && kotlin.math.abs(a.blue - b.blue) < 0.02f

    @Test
    fun darkHandle_drawsTheEdgeAtItsOwnTop_notAtTheTopOfTheContainerAboveIt() {
        composeRule.setContent {
            TarryTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    Spacer(Modifier.height(300.dp).testTag("above"))
                    Box(Modifier.testTag("sheet").background(DarkTarryColors.paper)) { SheetEdgeHandle() }
                }
            }
        }
        val sheet = composeRule.onNodeWithTag("sheet").captureToImage().toPixelMap()
        assertThat(close(sheet[sheet.width / 2, 0], DarkTarryColors.line)).isTrue()
        val root = composeRule.onNodeWithTag("sheet").fetchSemanticsNode().positionInRoot
        assertThat(root.y).isGreaterThan(100f)
    }

    @Test
    fun lightHandle_hasNoEdge() {
        composeRule.setContent {
            TarryTheme(darkTheme = false) { Box(Modifier.testTag("sheet").background(LightTarryColors.paper)) { SheetEdgeHandle() } }
        }
        val sheet = composeRule.onNodeWithTag("sheet").captureToImage().toPixelMap()
        assertThat(close(sheet[sheet.width / 2, 0], LightTarryColors.paper)).isTrue()
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun realModalSheet_hasItsEdgeInsideTheSheet_belowTheWindowTop() {
        composeRule.setContent {
            TarryTheme(darkTheme = true) {
                TarrySheet(onDismissRequest = {}) { Box(Modifier.testTag("content").height(120.dp)) }
            }
        }
        composeRule.waitForIdle()
        // The content (and so the handle above it) is laid out well below y = 0 of the window.
        val content = composeRule.onNodeWithTag("content").fetchSemanticsNode().positionInRoot
        assertThat(content.y).isGreaterThan(200f)
        val handle = composeRule.onNodeWithTag("sheetEdgeHandle", useUnmergedTree = true)
        assertThat(handle.fetchSemanticsNode().positionInRoot.y).isGreaterThan(100f)
        val px = handle.captureToImage().toPixelMap()
        assertThat(close(px[px.width / 2, 0], DarkTarryColors.line)).isTrue()
    }
}
