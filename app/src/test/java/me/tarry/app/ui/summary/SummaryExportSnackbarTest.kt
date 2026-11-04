package me.tarry.app.ui.summary

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import me.tarry.app.export.summary.SummaryExportFormat
import me.tarry.app.summary.SummaryExportEvent
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SummaryExportSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anOutcome_showsItsMessage_evenWhileNoSummaryScreenIsComposed() {
        val events = MutableSharedFlow<SummaryExportEvent>(extraBufferCapacity = 1)
        composeRule.setContent {
            TarryTheme {
                val host = remember { SnackbarHostState() }
                // Only the effect and the host: the Summary screen is not part of this composition.
                SummaryExportSnackbarEffect(events, host)
                SnackbarHost(host)
            }
        }
        composeRule.waitForIdle()
        events.tryEmit(SummaryExportEvent.Saved(SummaryExportFormat.PDF, 3))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Saved 3 blocks to PDF").assertIsDisplayed()
    }

    @Test
    fun aFailure_showsTheFailureMessage() {
        val events = MutableSharedFlow<SummaryExportEvent>(extraBufferCapacity = 1)
        composeRule.setContent {
            TarryTheme {
                val host = remember { SnackbarHostState() }
                SummaryExportSnackbarEffect(events, host)
                SnackbarHost(host)
            }
        }
        composeRule.waitForIdle()
        events.tryEmit(SummaryExportEvent.Failed)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Couldn't save the export. Try again.").assertIsDisplayed()
    }

    @Test
    fun nonBreaking_replacesNormalAndNarrowSpacesInsideATime() {
        assertThat("9:00 am".nonBreaking()).isEqualTo("9:00\u00A0am")
        assertThat("9:00\u202Fam".nonBreaking()).isEqualTo("9:00\u00A0am")
        assertThat("17:30".nonBreaking()).isEqualTo("17:30")
    }
}
