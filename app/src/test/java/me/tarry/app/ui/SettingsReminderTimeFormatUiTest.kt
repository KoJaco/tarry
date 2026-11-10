package me.tarry.app.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.app.ui.timeline.TimeFormats
import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.util.Locale

/** The reminder time reads like every other time in the app: the Timeline's [TimeFormats] in the phone's own locale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-rAU")
class SettingsReminderTimeFormatUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theTimelineFormatterGivesLowerCaseAmPmInEnAu() {
        val text = TimeFormats(Locale("en", "AU"), is24Hour = false).time(17 * 3_600_000L + 30 * 60_000L, ZoneOffset.UTC)
        assertThat(text.replace(' ', ' ').replace(' ', ' ')).isEqualTo("5:30 pm")
    }

    @Test
    fun theSubtitleAndTheTimeRowUseThatFormat() {
        composeRule.setContent {
            SettingsContent(Settings(reviewReminderEnabled = true, reviewReminderMinuteOfDay = 17 * 60 + 30))
        }

        composeRule.onNode(hasText("Scheduled days at ", substring = true)).performScrollTo()
        val subtitle = composeRule.onNode(hasText("Scheduled days at ", substring = true)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
        assertThat(subtitle).contains("5:30")
        assertThat(subtitle).containsMatch("5:30.pm")
        composeRule.onNode(hasText("Review reminder time") and hasText("5:30", substring = true)).assertExists()
    }
}
