package me.tarry.app.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density

/**
 * Cached text measurements for the day canvas, which would otherwise re-measure on every recomposition (every pinch
 * frame). Line heights are keyed on the style (an instance lives for one density and font scale, see
 * [rememberTextMetrics]); line counts on (text, style, width in px).
 */
internal class TextMetrics(
    private val density: Density,
    private val measureLineHeightPx: (TextStyle) -> Int,
    private val measureLineCount: (String, TextStyle, Int) -> Int,
) {
    private val heights = HashMap<TextStyle, Float>()
    private val counts = HashMap<Triple<String, TextStyle, Int>, Int>()

    /** The height in dp of one line of [style]. */
    fun lineDp(style: TextStyle): Float = heights.getOrPut(style) { with(density) { measureLineHeightPx(style).toDp().value } }

    /** How many lines [text] takes in [style] at [widthPx]. */
    fun lineCount(text: String, style: TextStyle, widthPx: Int): Int {
        if (counts.size >= MaxCountEntries) counts.clear()
        return counts.getOrPut(Triple(text, style, widthPx)) { measureLineCount(text, style, widthPx) }
    }

    private companion object {
        const val MaxCountEntries = 1024
    }
}

/** A [TextMetrics] that starts over when the density or font scale changes. */
@Composable
internal fun rememberTextMetrics(): TextMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density.density, density.fontScale) { textMetrics(measurer, density) }
}

private fun textMetrics(measurer: TextMeasurer, density: Density) = TextMetrics(
    density,
    measureLineHeightPx = { measurer.measure("Ag", it).size.height },
    measureLineCount = { text, style, widthPx -> measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx)).lineCount },
)
