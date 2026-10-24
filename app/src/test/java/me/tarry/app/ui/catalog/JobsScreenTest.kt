package me.tarry.app.ui.catalog

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class JobsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sky = JobPalette.slots[1].key
    private val acme = JobRow("a", "Acme", sky, archived = false, placeDefaults = 0)
    private val old = JobRow("o", "Old site", JobPalette.slots[2].key, archived = true, placeDefaults = 0)

    private val created = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val coloured = mutableListOf<Pair<String, Int>>()
    private val archived = mutableListOf<String>()
    private val unarchived = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    private fun show(state: JobsUiState?, uses: Int = 0) = composeRule.setContent {
        TarryTheme {
            JobsContent(
                state = state,
                loadUses = { uses },
                onCreate = { created += it },
                onRename = { id, name -> renamed += id to name },
                onColour = { id, argb -> coloured += id to argb },
                onArchive = { archived += it },
                onUnarchive = { unarchived += it },
                onDelete = { deleted += it },
            )
        }
    }

    private fun state(vararg rows: JobRow) = JobsUiState(rows.filterNot { it.archived }, rows.filter { it.archived })

    @Test
    fun listsActiveJobsAndAnArchivedSection() {
        show(state(acme, old))

        composeRule.onNodeWithText("Acme").assertExists()
        composeRule.onNodeWithText("Old site").assertExists()
        composeRule.onNodeWithText("Archived", ignoreCase = true).assertExists()
        composeRule.onNodeWithText("Acme").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun emptyStateOffersTheNewJobAction() {
        show(state())

        composeRule.onNodeWithText("No jobs yet").assertExists()
        composeRule.onNodeWithText("New job").assertExists()
    }

    @Test
    fun newJobAsksForANameAndCreates() {
        show(state(acme))

        composeRule.onNodeWithText("New job").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Beta")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(created).containsExactly("Beta")
    }

    @Test
    fun tappingARowOpensTheActionsSheet() {
        show(state(acme))

        composeRule.onNodeWithText("Acme").performClick()

        for (label in listOf("Rename", "Colour", "Archive", "Delete")) composeRule.onNodeWithText(label).assertExists()
        composeRule.onNodeWithText("Unarchive").assertDoesNotExist()
    }

    @Test
    fun anArchivedJobOffersUnarchiveInPlaceOfArchive() {
        show(state(acme, old))

        composeRule.onNodeWithText("Old site").performClick()
        composeRule.onNodeWithText("Unarchive").performClick()

        assertThat(unarchived).containsExactly("o")
    }

    @Test
    fun deleteIsDisabledWithAVisibleReasonWhileTheJobIsInUse() {
        show(state(acme), uses = 3)

        composeRule.onNodeWithText("Acme").performClick()

        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("Cannot delete: used by 3 visits, trips or places", substring = true).assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        assertThat(deleted).isEmpty()
    }

    @Test
    fun deleteAsksForConfirmationWhenUnused() {
        show(state(acme), uses = 0)

        composeRule.onNodeWithText("Acme").performClick()
        composeRule.onNodeWithText("Delete").assertIsEnabled()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete Acme?").assertExists()
        assertThat(deleted).isEmpty()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()

        assertThat(deleted).containsExactly("a")
    }

    @Test
    fun archiveConfirmStatesHowManyPlaceDefaultsAreCleared() {
        show(state(acme.copy(placeDefaults = 2)))

        composeRule.onNodeWithText("Acme").performClick()
        composeRule.onNodeWithText("Archive").performClick()
        composeRule.onNodeWithText("Archive Acme?").assertExists()
        composeRule.onNodeWithText("cleared on 2 places", substring = true).assertExists()
        assertThat(archived).isEmpty()
        composeRule.onNode(hasText("Archive") and hasClickAction()).performClick()

        assertThat(archived).containsExactly("a")
    }

    @Test
    fun cancellingTheArchiveConfirmDoesNothing() {
        show(state(acme))

        composeRule.onNodeWithText("Acme").performClick()
        composeRule.onNodeWithText("Archive").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(archived).isEmpty()
        composeRule.onNodeWithText("Archive Acme?").assertDoesNotExist()
    }

    @Test
    fun colourGridHasEightSpokenSwatchesOfAtLeast48dpWithTheCurrentOneSelected() {
        show(state(acme))

        composeRule.onNodeWithText("Acme").performClick()
        composeRule.onNodeWithText("Colour").performClick()

        for (slot in JobPalette.slots) {
            composeRule.onNodeWithContentDescription(slot.name).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        composeRule.onNodeWithContentDescription("Sky").assertIsSelected()
        composeRule.onNodeWithContentDescription("Sage").performClick()

        assertThat(coloured).containsExactly("a" to JobPalette.slots[2].key)
    }

    @Test
    fun renameShowsTheCurrentNameAndSavesTheNewOne() {
        show(state(acme))

        composeRule.onNodeWithText("Acme").performClick()
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("Acme")).assertExists()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Acme Ltd")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(renamed).containsExactly("a" to "Acme Ltd")
    }
}
