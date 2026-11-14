package me.tarry.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import me.tarry.app.ui.settings.isShorterRetention
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

    private fun setRetentionContent(current: Int?, chosen: MutableList<Int?>, fontScale: Float = 1f) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                SettingsContent(Settings(rawPingRetentionDays = current), onRetentionSelected = { chosen += it })
            }
        }
    }

    @Test
    fun retentionRowHintsThatOlderDaysLoseRouteLines() {
        setRetentionContent(90, mutableListOf())

        composeRule.onNodeWithText("90 days · older days lose map route lines").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shorteningRetentionAsksFirstAndOnlyConfirmCallsTheSetter() {
        val chosen = mutableListOf<Int?>()
        setRetentionContent(90, chosen)

        composeRule.onNodeWithText("Raw location retention").performScrollTo().performClick()
        composeRule.onNodeWithText("30 days").performClick()
        // The title and the confirm button share the wording.
        composeRule.onAllNodes(hasText("Remove older route lines")).assertCountEquals(2)
        composeRule.onNodeWithText(
            "Route lines older than 30 days will be removed from the map. Visits, trips and distances stay. This can't be undone.",
        ).assertIsDisplayed()
        assertThat(chosen).isEmpty()

        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(chosen).isEmpty()
        composeRule.onNodeWithText("Route lines older than", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("Raw location retention").performScrollTo().performClick()
        composeRule.onNodeWithText("30 days").performClick()
        composeRule.onAllNodes(hasText("Remove older route lines") and hasClickAction()).onFirst().performClick()
        assertThat(chosen).containsExactly(30)
    }

    @Test
    fun shorteningFromForeverAsksAndLengtheningDoesNot() {
        val chosen = mutableListOf<Int?>()
        setRetentionContent(null, chosen)
        composeRule.onNodeWithText("Raw location retention").performScrollTo().performClick()
        composeRule.onNodeWithText("1 year").performClick()
        composeRule.onNodeWithText("Route lines older than 1 year", substring = true).assertIsDisplayed()
        assertThat(chosen).isEmpty()
    }

    @Test
    fun lengtheningRetentionAppliesAtOnceWithNoDialog() {
        val chosen = mutableListOf<Int?>()
        setRetentionContent(30, chosen)

        composeRule.onNodeWithText("Raw location retention").performScrollTo().performClick()
        composeRule.onNodeWithText("1 year").performClick()

        assertThat(chosen).containsExactly(365)
        composeRule.onNodeWithText("Remove older route lines").assertDoesNotExist()
    }

    @Test
    fun theShortenDialogKeepsItsButtonsReachableAtFontScaleTwo() {
        val chosen = mutableListOf<Int?>()
        setRetentionContent(90, chosen, fontScale = 2f)

        composeRule.onNodeWithText("Raw location retention").performScrollTo().performClick()
        composeRule.onNodeWithText("30 days").performClick()

        composeRule.onNodeWithText("Cancel").performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        composeRule.onAllNodes(hasText("Remove older route lines") and hasClickAction()).onFirst()
            .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertThat(chosen).containsExactly(30)
    }

    @Test
    fun shorterRetentionRule() {
        assertThat(isShorterRetention(30, 90)).isTrue()
        assertThat(isShorterRetention(365, null)).isTrue()
        assertThat(isShorterRetention(null, 365)).isFalse()
        assertThat(isShorterRetention(90, 90)).isFalse()
        assertThat(isShorterRetention(365, 30)).isFalse()
    }

    private fun deleteDialogButtonMatcher() = hasText("Delete all data") and hasClickAction()

    @Test
    fun deleteAllNeedsTheWordTypedBeforeTheConfirmButtonWorks() {
        val calls = mutableListOf<String>()
        composeRule.setContent { SettingsContent(Settings(), onDeleteAll = { calls += "delete" }) }

        composeRule.onNodeWithText("Delete all data").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("Type delete to confirm").assertIsDisplayed()
        composeRule.onNodeWithText(
            "This removes every visit, trip, place, job and location point from this phone. Settings and your schedule stay. " +
                "Back up first if you might want them. This can't be undone.",
        ).assertIsDisplayed()
        // The dialog's confirm button is the second "Delete all data" (the first is the Settings row behind it).
        val confirm = composeRule.onAllNodes(deleteDialogButtonMatcher())[1]
        confirm.assertIsNotEnabled().performClick()
        assertThat(calls).isEmpty()

        composeRule.onNode(hasSetTextAction()).performTextInput("dele")
        composeRule.onAllNodes(deleteDialogButtonMatcher())[1].assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("te ")
        composeRule.onAllNodes(deleteDialogButtonMatcher())[1].assertIsEnabled().performClick()

        assertThat(calls).containsExactly("delete")
        composeRule.onNodeWithText("Type delete to confirm").assertDoesNotExist()
    }

    @Test
    fun deleteAllIsCaseInsensitiveAndCancelChangesNothing() {
        val calls = mutableListOf<String>()
        composeRule.setContent { SettingsContent(Settings(), onDeleteAll = { calls += "delete" }) }

        composeRule.onNodeWithText("Delete all data").performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("DELETE")
        composeRule.onAllNodes(deleteDialogButtonMatcher())[1].assertIsEnabled()
        composeRule.onNodeWithText("Cancel").assertHeightIsAtLeast(48.dp).performClick()

        assertThat(calls).isEmpty()
        composeRule.onNodeWithText("Type delete to confirm").assertDoesNotExist()
    }

    @Test
    fun deleteAllRowIsOffWhileAnOperationRuns() {
        composeRule.setContent { SettingsContent(Settings(), data = DataUiState(busy = DataBusy.DELETING)) }

        composeRule.onNodeWithText("Deleting…").performScrollTo().assertIsDisplayed()
        composeRule.onNode(deleteDialogButtonMatcher()).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theDeleteDialogFitsAtTwiceTheFontSize() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SettingsContent(Settings())
            }
        }

        composeRule.onNodeWithText("Delete all data").performScrollTo().performClick()

        composeRule.onNodeWithText("Type delete to confirm").assertIsDisplayed()
        composeRule.onAllNodes(deleteDialogButtonMatcher())[1].performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun deleteDialogFieldAndConfirmAreReachableAtNormalFontSize() {
        composeRule.setContent { SettingsContent(Settings()) }
        composeRule.onNodeWithText("Delete all data").performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodes(deleteDialogButtonMatcher())[1].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun privacyLineIsTheRestoreRowsSubtitleAndRetentionFollowsInTheSameGroup() {
        composeRule.setContent { SettingsContent(Settings()) }

        composeRule.onNodeWithText("Raw location retention").performScrollTo()
        val hint = "The file contains your full location history. Keep it somewhere private."
        // One merged row: the privacy line is read with "Restore from backup", not as a loose paragraph.
        val restore = composeRule.onNode(hasText("Restore from backup") and hasText(hint)).assertHasClickAction()
            .fetchSemanticsNode().boundsInRoot
        val backUp = composeRule.onNodeWithText("Back up now").fetchSemanticsNode().boundsInRoot
        val retention = composeRule.onNodeWithText("Raw location retention").fetchSemanticsNode().boundsInRoot
        assertThat(restore.top).isAtLeast(backUp.bottom)
        // Retention sits directly under restore (row dividers only), not after a separate paragraph.
        assertThat(retention.top - restore.bottom).isLessThan(4f * composeRule.density.density)
    }

    @Test
    fun aboutRowShowsTheVersionTheAboutScreenUses() {
        composeRule.setContent { SettingsContent(Settings()) }

        composeRule.onNodeWithText("${me.tarry.app.BuildConfig.VERSION_NAME} (${me.tarry.app.BuildConfig.VERSION_CODE})").performScrollTo().assertIsDisplayed()
    }
}
