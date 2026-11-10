package me.tarry.app.ui.timeline

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.assertNotTruncated
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-rAU-w360dp-h800dp")
class DayHeaderTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longDateKeepsClearOfTheButtonsOnANarrowPhone() {
        composeRule.setContent {
            TarryTheme {
                DayHeader(
                    date = LocalDate.of(2026, 9, 30),
                    today = LocalDate.of(2026, 10, 1),
                    onPreviousDay = {}, onNextDay = {}, onSelectDate = {},
                    modifier = Modifier.width(328.dp), // 360 dp minus the 16 dp gutters
                )
            }
        }
        val date = composeRule.onNodeWithTag("timeline_date").assertNotTruncated().fetchSemanticsNode()
        val previous = composeRule.onNodeWithTag("timeline_previous").fetchSemanticsNode().boundsInRoot
        val density = composeRule.density.density
        // On one line, and at least 12 dp short of the previous-day button.
        assertThat(date.layoutInfo.height.toFloat() / density).isLessThan(40f)
        assertThat(previous.left - date.boundsInRoot.right).isAtLeast(12f * density - 1f)
    }
}
