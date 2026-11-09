package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.settings.DataBusy
import me.tarry.app.ui.settings.DataUiState
import me.tarry.app.ui.settings.RestorePrompt
import me.tarry.app.ui.settings.SettingsContent
import me.tarry.core.data.db.BackupCounts
import me.tarry.core.data.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsDataUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val prompt = RestorePrompt(
        backupCreatedAt = 1_700_000_000_000L,
        backup = BackupCounts(visits = 77, locations = 10_952),
        phone = BackupCounts(visits = 3, locations = 1),
    )

    @Test
    fun dataSectionHasBackUpAndRestoreRowsAndThePrivacyLine() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            SettingsContent(Settings(), onBackUp = { calls += "backup" }, onRestore = { calls += "restore" })
        }

        composeRule.onNodeWithText("Data", ignoreCase = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Back up now").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("Restore from backup").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("The file contains your full location history. Keep it somewhere private.")
            .performScrollTo().assertIsDisplayed()

        assertThat(calls).containsExactly("backup", "restore").inOrder()
    }

    @Test
    fun backUpShowsTheLastBackupDateOnceThereIsOne() {
        composeRule.setContent { SettingsContent(Settings(lastBackupMs = 1_700_000_000_000L)) }

        composeRule.onNodeWithText("Last backup:", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noLastBackupLineBeforeTheFirstBackup() {
        composeRule.setContent { SettingsContent(Settings()) }

        composeRule.onNodeWithText("Last backup:", substring = true).assertDoesNotExist()
    }

    @Test
    fun rowsAreDisabledWithAProgressRowWhileAnOperationRuns() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            SettingsContent(
                Settings(),
                data = DataUiState(busy = DataBusy.BACKING_UP),
                onBackUp = { calls += "backup" },
                onRestore = { calls += "restore" },
            )
        }

        composeRule.onNodeWithText("Backing up…").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Back up now").performScrollTo().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("Restore from backup").performScrollTo().assertIsNotEnabled().performClick()

        assertThat(calls).isEmpty()
    }

    @Test
    fun theConfirmDialogShowsBothCountsAndCancelChangesNothing() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            var data by remember { mutableStateOf(DataUiState(prompt = prompt)) }
            SettingsContent(
                Settings(),
                data = data,
                onConfirmRestore = { calls += "replace" },
                onBackUpFirst = { calls += "backup first" },
                onCancelRestore = {
                    calls += "cancel"
                    data = DataUiState()
                },
            )
        }

        composeRule.onNodeWithText("This replaces everything on this phone.").assertIsDisplayed()
        composeRule.onNodeWithText("77 visits", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("10,952 locations", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("This phone now: 3 visits, 1 location").assertIsDisplayed()
        composeRule.onNodeWithText("Replace").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Back up current data first").assertHeightIsAtLeast(48.dp)

        composeRule.onNodeWithText("Cancel").assertHeightIsAtLeast(48.dp).performClick()

        assertThat(calls).containsExactly("cancel")
        composeRule.onNodeWithText("This replaces everything on this phone.").assertDoesNotExist()
    }

    @Test
    fun replaceAndBackUpFirstReportTheirChoice() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            SettingsContent(
                Settings(),
                data = DataUiState(prompt = prompt),
                onConfirmRestore = { calls += "replace" },
                onBackUpFirst = { calls += "backup first" },
            )
        }

        composeRule.onNodeWithText("Back up current data first").performClick()
        composeRule.onNodeWithText("Replace").performClick()

        assertThat(calls).containsExactly("backup first", "replace").inOrder()
    }

    @Test
    fun theDialogIsHiddenWhileTheBackUpFirstRunsAndComesBack() {
        var data by mutableStateOf(DataUiState(prompt = prompt, busy = DataBusy.BACKING_UP))
        composeRule.setContent { SettingsContent(Settings(), data = data) }
        composeRule.onNodeWithText("This replaces everything on this phone.").assertDoesNotExist()

        data = DataUiState(prompt = prompt)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("This replaces everything on this phone.").assertIsDisplayed()
    }

    @Test
    fun retentionRowOpensThePickerAndSelectingCallsTheSetter() {
        val chosen = mutableListOf<Int?>()
        composeRule.setContent {
            SettingsContent(Settings(rawPingRetentionDays = 90), onRetentionSelected = { chosen += it })
        }

        composeRule.onNodeWithText("Raw location retention").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

        composeRule.onNodeWithText("Older days keep their visits and trips. Their route lines on the map are removed.").assertIsDisplayed()
        composeRule.onNodeWithText("30 days").assertIsDisplayed()
        composeRule.onNodeWithText("1 year").assertIsDisplayed()
        composeRule.onNodeWithText("Forever").performClick()
        assertThat(chosen).containsExactly(null)
    }
}
