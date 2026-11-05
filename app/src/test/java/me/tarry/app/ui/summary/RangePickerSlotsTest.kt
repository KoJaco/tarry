package me.tarry.app.ui.summary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h1600dp")
class RangePickerSlotsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val format = DateTimeFormatter.ofPattern("d MMM y", Locale.ENGLISH)
    private val from = LocalDate.of(2026, 10, 26)
    private val to = LocalDate.of(2026, 11, 1)

    private fun show(fontScale: Float, width: Int, start: LocalDate? = from, end: LocalDate? = to) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TarryTheme {
                    Column(Modifier.width(width.dp)) {
                        RangePickerTitle()
                        RangePickerHeadline(start, end, format)
                    }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.lines(): List<String> {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!(results)
        val layout = results.single()
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
    }

    @Test
    fun titleIsTheShortMutedLabel_andHeadlineIsTheRangeOnOneLineAtScaleOne() {
        show(fontScale = 1f, width = 300)
        composeRule.onNodeWithText("Select dates").assertExists()
        val headline = composeRule.onNodeWithText("26 Oct 2026 – 1 Nov 2026".noBreakDates())
        assertThat(headline.lines()).hasSize(1)
    }

    // Robolectric does not measure glyph widths, so the wrap rule is checked on the text: the only plain space is the
    // one after the dash (the single legal break point), so no date can be split at any font scale.
    @Test
    fun headlineHasExactlyOneBreakPoint_betweenTheDates() {
        show(fontScale = 2f, width = 240)
        val text = composeRule.onNodeWithText("26 Oct 2026 – 1 Nov 2026".noBreakDates()).fetchSemanticsNode()
            .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)!!.single().text
        assertThat(text.count { it == ' ' }).isEqualTo(1)
        assertThat(text.indexOf(' ')).isEqualTo(text.indexOf('–') + 1)
        assertThat(text.substringBefore(' ').endsWith("2026$nb–")).isTrue()
    }

    @Test
    fun withoutASelection_theHeadlineShowsPlaceholders() {
        show(fontScale = 1f, width = 300, start = null, end = null)
        composeRule.onNodeWithText("Start date – End date".noBreakDates()).assertExists()
    }

    @Test
    fun titleAndHeadlineShareTheirLeftEdge_atTheMonthHeadingInset() {
        show(fontScale = 1f, width = 300)
        val title = composeRule.onNodeWithText("Select dates").fetchSemanticsNode().boundsInRoot
        val headline = composeRule.onNodeWithText("26 Oct 2026 – 1 Nov 2026".noBreakDates()).fetchSemanticsNode().boundsInRoot
        assertThat(title.left).isEqualTo(headline.left)
        assertThat(title.left).isWithin(0.5f).of(24f * composeRule.density.density)
    }

    private val nb = ' '

    // The dash stays with the first date (non-breaking space before it); the break point is the plain space after it.
    private fun String.noBreakDates(): String {
        val (a, b) = split(" – ")
        return a.replace(' ', nb) + "$nb– " + b.replace(' ', nb)
    }
}
