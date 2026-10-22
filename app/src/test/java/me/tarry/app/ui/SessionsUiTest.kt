package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.export.ExportFormat
import me.tarry.app.export.ExportRequest
import me.tarry.app.export.suggestedFileName
import me.tarry.app.tracking.SegmentationError
import me.tarry.app.ui.sessions.ExportResult
import me.tarry.app.ui.sessions.SegmentationUi
import me.tarry.app.ui.sessions.SessionListItem
import me.tarry.app.ui.sessions.SessionsActions
import me.tarry.app.ui.sessions.SessionsContent
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SessionsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val exports = mutableListOf<ExportRequest>()
    private var testWindowTaps = 0
    private var segmentationRuns = 0
    private val actions = SessionsActions({ exports += it }, { testWindowTaps++ }, {}, { segmentationRuns++ })
    private val session = SessionListItem(
        id = 7, start = 1_700_000_000_000, end = 1_700_000_600_000, zoneId = "Australia/Sydney",
        trigger = SessionTrigger.SCHEDULE, endReason = SessionEndReason.SCHEDULE_END, pingCount = 42,
    )

    @Test
    fun listsSessionDetails() {
        composeRule.setContent { SessionsContent(listOf(session), false, null, actions) }
        composeRule.onNodeWithText("Scheduled, Window ended").assertExists()
        composeRule.onNodeWithText("42 pings").assertExists()
    }

    @Test
    fun openSessionShowsInProgress() {
        composeRule.setContent {
            SessionsContent(listOf(session.copy(end = null, endReason = null, pingCount = 1)), false, null, actions)
        }
        composeRule.onNodeWithText("Scheduled, Still open").assertExists()
        composeRule.onNodeWithText("1 ping").assertExists()
    }

    @Test
    fun emptyStateIsShown() {
        composeRule.setContent { SessionsContent(emptyList(), false, null, actions) }
        composeRule.onNodeWithText("No sessions recorded yet").assertExists()
    }

    @Test
    fun testWindowButtonOnlyWhenDebug() {
        composeRule.setContent { SessionsContent(emptyList(), false, null, actions) }
        composeRule.onNodeWithTag("sessions_test_window").assertDoesNotExist()
    }

    @Test
    fun testWindowButtonInvokesAction() {
        composeRule.setContent { SessionsContent(emptyList(), true, null, actions) }
        composeRule.onNodeWithTag("sessions_test_window").performClick()
        assertThat(testWindowTaps).isEqualTo(1)
    }

    @Test
    fun runSegmentationButtonInvokesActionAndMeetsTouchTarget() {
        composeRule.setContent { SessionsContent(emptyList(), false, null, actions) }
        composeRule.onNodeWithTag("sessions_run_segmentation").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Run segmentation now").performClick()
        assertThat(segmentationRuns).isEqualTo(1)
    }

    @Test
    fun sessionExportButtonsRequestTheirFormat() {
        composeRule.setContent { SessionsContent(listOf(session), false, null, actions) }
        composeRule.onNode(hasContentDescription("Export GPX for session 7")).performScrollTo().performClick()
        composeRule.onNode(hasContentDescription("Export CSV for session 7")).performScrollTo().performClick()
        assertThat(exports).containsExactly(
            ExportRequest.Session(7, ExportFormat.GPX),
            ExportRequest.Session(7, ExportFormat.CSV),
        ).inOrder()
    }

    private fun showOne(item: SessionListItem) {
        composeRule.setContent { SessionsContent(listOf(item), false, null, actions) }
    }

    @Test
    fun completedSessionHasACompleteIcon() {
        showOne(session)
        composeRule.onNode(hasContentDescription("Completed session")).assertExists()
    }

    @Test
    fun killedSessionHasAKilledIcon() {
        showOne(session.copy(endReason = SessionEndReason.KILLED))
        composeRule.onNode(hasContentDescription("Session was killed")).assertExists()
    }

    @Test
    fun openSessionHasAnInProgressIcon() {
        showOne(session.copy(end = null, endReason = null))
        composeRule.onNode(hasContentDescription("Session in progress")).assertExists()
    }

    @Test
    fun exportRowsAreStackedClickableRowsBelowTheSession() {
        composeRule.setContent { SessionsContent(listOf(session), false, null, actions) }
        val group = composeRule.onNodeWithTag("session_exports_7")
        group.assertExists()
        composeRule.onNode(hasText("Export GPX") and hasAnyAncestor(hasTestTag("session_exports_7")))
            .assertHasClickAction().performScrollTo().performClick()
        composeRule.onNode(hasText("Export CSV") and hasAnyAncestor(hasTestTag("session_exports_7")))
            .assertHasClickAction().performScrollTo().performClick()
        assertThat(exports).containsExactly(
            ExportRequest.Session(7, ExportFormat.GPX),
            ExportRequest.Session(7, ExportFormat.CSV),
        ).inOrder()
    }

    @Test
    fun exportButtonsMeetTouchTarget() {
        composeRule.setContent { SessionsContent(listOf(session), false, null, actions) }
        composeRule.onNode(hasContentDescription("Export GPX for session 7")).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun rangeExportUsesChosenDatesInOrder() {
        val today = LocalDate.of(2026, 3, 2)
        composeRule.setContent { SessionsContent(emptyList(), false, null, actions, today = today) }
        composeRule.onNodeWithTag("range_export_CSV").performClick()
        val request = exports.single() as ExportRequest.Range
        assertThat(request.from).isEqualTo(today)
        assertThat(request.to).isEqualTo(today)
        assertThat(request.format).isEqualTo(ExportFormat.CSV)
    }

    @Test
    fun exportResultIsAnnounced() {
        composeRule.setContent { SessionsContent(emptyList(), false, ExportResult.Done(3), actions) }
        composeRule.onNodeWithText("Exported 3 pings").assertExists()
    }

    @Test
    fun suggestedFileNames() {
        assertThat(ExportRequest.Session(7, ExportFormat.GPX).suggestedFileName()).isEqualTo("tarry-session-7.gpx")
        val range = ExportRequest.Range(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7), ZoneId.of("UTC"), ExportFormat.CSV,
        )
        assertThat(range.suggestedFileName()).isEqualTo("tarry-pings-2026-03-01_to_2026-03-07.csv")
    }

    @Test
    fun runningSegmentationDisablesTheButtonAndSaysSo() {
        composeRule.setContent { SessionsContent(emptyList(), false, null, actions, segmentation = SegmentationUi(running = true)) }
        composeRule.onNodeWithTag("sessions_run_segmentation").assertIsNotEnabled()
        composeRule.onNodeWithText("Segmentation running…").assertExists()
        composeRule.onNodeWithTag("sessions_run_segmentation").performClick()
        assertThat(segmentationRuns).isEqualTo(0)
    }

    @Test
    fun lastSuccessIsShownWhenIdle() {
        composeRule.setContent {
            SessionsContent(emptyList(), false, null, actions, segmentation = SegmentationUi(lastSuccessMs = 1_700_000_000_000))
        }
        composeRule.onNodeWithTag("sessions_segmentation_status").assertTextContains("Last segmentation:", substring = true)
        composeRule.onNodeWithTag("sessions_run_segmentation").assertIsEnabled()
    }

    @Test
    fun errorIsShownInsteadOfSuccess() {
        composeRule.setContent {
            SessionsContent(
                emptyList(), false, null, actions,
                segmentation = SegmentationUi(lastSuccessMs = 1L, error = SegmentationError(1_700_000_000_000, "db closed")),
            )
        }
        composeRule.onNodeWithTag("sessions_segmentation_status").assertTextContains("db closed", substring = true)
        composeRule.onNodeWithText("Last segmentation:", substring = true).assertDoesNotExist()
    }

    @Test
    fun noStatusLineBeforeAnyRun() {
        composeRule.setContent { SessionsContent(emptyList(), false, null, actions) }
        composeRule.onNodeWithTag("sessions_segmentation_status").assertDoesNotExist()
    }
}
