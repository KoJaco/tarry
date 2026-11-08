package me.tarry.app.ui.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/** How many title lines a visit block gets from its height. Native graphics, so text really wraps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w320dp-h1000dp")
class BlockTextLinesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneId.of("Australia/Sydney")
    private val date = LocalDate.of(2026, 10, 21)
    private fun at(hour: Int, minute: Int = 0): Long = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val longTitle = "The Very Long Named Place Where Something Happens Every Single Day Of The Working Week And More"

    private var zoom by mutableStateOf(1f)

    private fun show(item: TimelineItem.Visit) {
        composeRule.setContent {
            TarryTheme {
                val layout = layoutDay(listOf(item), date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone, -1L, 1f, zoom = zoom)
                DayCanvas(layout, layout.canvasStart, layout.canvasEnd, zone, at(20))
            }
        }
    }

    private fun titleLayout(): TextLayoutResult {
        val node = composeRule.onNode(hasText(longTitle) and hasAnyAncestor(hasTestTag("timeline_row_v")), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first()
    }

    @Test
    fun tallBlockLetsALongTitleUseAsManyLinesAsItNeeds() {
        show(TimelineItem.Visit("v", at(9), at(12), longTitle))
        val layout = titleLayout()
        assertThat(layout.lineCount).isAtLeast(3)
        assertThat(layout.hasVisualOverflow).isFalse()
    }

    @Test
    fun blockThatCannotFitTheTitleEndsInAnEllipsis() {
        // 45 min is 108 dp: three title lines at most once the times are counted, and the title needs more.
        show(TimelineItem.Visit("v", at(9), at(9, 45), longTitle))
        val layout = titleLayout()
        assertThat(layout.lineCount).isAtMost(3)
        assertThat(layout.hasVisualOverflow).isTrue()
    }

    @Test
    fun theSameBlockShowsMoreTitleLinesWhenZoomedIn() {
        show(TimelineItem.Visit("v", at(9), at(9, 45), longTitle))
        val low = titleLayout().lineCount
        zoom = 2f
        composeRule.waitForIdle()
        assertThat(titleLayout().lineCount).isGreaterThan(low)
    }
}
