package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-rAU")
class WeekStripTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val au = Locale("en", "AU")
    private val monday = LocalDate.of(2026, 10, 19)

    private fun show(fontScale: Float, selected: LocalDate = monday, width: Dp = 320.dp) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                TarryTheme { WeekStrip(selected, LocalDate.of(2026, 10, 25), {}, Modifier.width(width)) }
            }
        }
    }

    @Test
    fun labelIsThreeLettersWithNoDotForEnAu() {
        assertThat(DayOfWeek.values().map { weekdayLabel(it, au, narrow = false) })
            .containsExactly("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").inOrder()
    }

    @Test
    fun narrowLabelIsOneLetter() {
        assertThat(weekdayLabel(DayOfWeek.MONDAY, au, narrow = true)).isEqualTo("M")
    }

    @Test
    fun stripShowsMonAtNormalScale() {
        show(1f)
        composeRule.onNodeWithText("Mon", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun stripShowsNarrowLetterAtLargeScale() {
        show(2f)
        composeRule.onNodeWithText("M", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun cell(day: LocalDate) = composeRule.onNodeWithTag("timeline_week_$day")

    @Test
    fun atNormalScaleTheSevenCellsShareTheWidthAndNothingScrolls() {
        show(1f)
        composeRule.onNodeWithTag("timeline_week_strip").assert(!hasScrollAction())
        val first = cell(monday).getUnclippedBoundsInRoot()
        val last = cell(monday.plusDays(6)).getUnclippedBoundsInRoot()
        assertThat(last.right.value).isWithin(1f).of(320f)
        assertThat(first.top).isEqualTo(last.top)
    }

    @Test
    fun atTwiceTheFontSizeTheStripStaysOneRowAndScrolls() {
        show(2f)
        composeRule.onNodeWithTag("timeline_week_strip").assert(hasScrollAction())
        val tops = (0..6).map { cell(monday.plusDays(it.toLong())).getUnclippedBoundsInRoot().top }
        assertThat(tops.toSet()).hasSize(1)
        // 7 cells of at least 48 dp plus gaps cannot fit 320 dp.
        assertThat(cell(monday.plusDays(6)).getUnclippedBoundsInRoot().right.value).isGreaterThan(320f)
        (0..6).forEach { assertThat(cell(monday.plusDays(it.toLong())).getUnclippedBoundsInRoot().width.value).isAtLeast(48f) }
    }

    @Test
    fun theSelectedDayIsKeptInViewWhenTheStripScrolls() {
        val sunday = monday.plusDays(6)
        show(2f, selected = sunday)
        composeRule.waitForIdle()
        cell(sunday).assertIsDisplayed()
        val bounds = cell(sunday).fetchSemanticsNode().boundsInRoot
        assertThat(bounds.right).isAtMost(320f + 1f)
    }

    @Test
    fun cellMinWidthStaysAtLeastATouchTargetOnceTheFontGrows() {
        assertThat(weekCellMinWidth(1f)).isEqualTo(40.dp)
        assertThat(weekCellMinWidth(1.3f).value).isAtLeast(48f)
        assertThat(weekCellMinWidth(2f).value).isAtLeast(weekCellMinWidth(1.5f).value)
        assertThat(weekFits(320.dp, 1f, 4.dp)).isTrue()
        assertThat(weekFits(320.dp, 2f, 4.dp)).isFalse()
    }
}
