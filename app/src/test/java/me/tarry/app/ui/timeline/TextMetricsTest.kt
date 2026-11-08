package me.tarry.app.ui.timeline

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextMetricsTest {
    private var heightCalls = 0
    private var countCalls = 0
    private val metrics = TextMetrics(
        density = Density(2f),
        measureLineHeightPx = { heightCalls++; 40 },
        measureLineCount = { text, _, width -> countCalls++; text.length / width + 1 },
    )

    @Test
    fun lineHeightIsMeasuredOncePerStyleAndConvertedToDp() {
        val style = TextStyle()
        assertThat(metrics.lineDp(style)).isEqualTo(20f)
        assertThat(metrics.lineDp(style)).isEqualTo(20f)
        assertThat(heightCalls).isEqualTo(1)
        metrics.lineDp(TextStyle(fontSize = 12.sp))
        assertThat(heightCalls).isEqualTo(2)
    }

    @Test
    fun lineCountIsMeasuredOncePerTextStyleAndWidth() {
        val style = TextStyle()
        assertThat(metrics.lineCount("abcdef", style, 3)).isEqualTo(3)
        assertThat(metrics.lineCount("abcdef", style, 3)).isEqualTo(3)
        assertThat(countCalls).isEqualTo(1)
        assertThat(metrics.lineCount("abcdef", style, 6)).isEqualTo(2)
        assertThat(metrics.lineCount("abcdeg", style, 6)).isEqualTo(2)
        assertThat(countCalls).isEqualTo(3)
    }
}
