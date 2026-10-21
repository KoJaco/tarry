package me.tarry.app.ui.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
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

    private fun show(fontScale: Float) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                TarryTheme { WeekStrip(monday, monday, {}) }
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
}
