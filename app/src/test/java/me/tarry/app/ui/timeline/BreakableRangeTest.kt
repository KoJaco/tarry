package me.tarry.app.ui.timeline

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class BreakableRangeTest {
    @get:Rule
    val composeRule = createComposeRule()


    @Test
    fun eachTimeIsWholeAndTheOnlyBreakFollowsTheDash() {
        val text = "${"8:31 am".keepWhole()}–${"11:06 am".keepWhole()}".breakAfterDash()
        assertThat(text).isEqualTo("8:31 am–​11:06 am")
    }

    @Test
    fun aNarrowBlockWrapsAfterTheDashNotInsideATime() {
        var layout: TextLayoutResult? = null
        composeRule.setContent {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val text = "${"8:31 am".keepWhole()}–${"11:06 am".keepWhole()}".breakAfterDash()
            // Wide enough for "11:06 am" but not for the whole range.
            val widest = measurer.measure("11:06 am").size.width
            val width: Dp = with(density) { (widest + 4).toDp() }
            Text(text, modifier = Modifier.width(width), onTextLayout = { layout = it })
        }
        composeRule.waitForIdle()
        val result = layout!!
        assertThat(result.lineCount).isEqualTo(2)
        val lines = (0 until result.lineCount).map { result.layoutInput.text.text.substring(result.getLineStart(it), result.getLineEnd(it)).replace("​", "") }
        assertThat(lines).containsExactly("8:31 am–", "11:06 am").inOrder()
    }
}
