package me.tarry.app.ui.catalog

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
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
class TagsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val work = GroupRow(
        "work", "Work", JobPalette.slots[1].key,
        listOf(TagRow("a", "Alpha", false), TagRow("b", "Bravo", false), TagRow("c", "Charlie", false), TagRow("old", "Retired", true)),
    )
    private val home = GroupRow("home", "Home", 0, listOf(TagRow("h1", "Hearth", false), TagRow("h2", "Hall", false)))

    private val tagReorders = mutableListOf<Pair<String, List<String>>>()
    private val groupReorders = mutableListOf<List<String>>()
    private val createdGroups = mutableListOf<String>()
    private val createdTags = mutableListOf<Pair<String, String>>()
    private val groupColours = mutableListOf<Pair<String, Int>>()
    private val deletedGroups = mutableListOf<String>()
    private val archivedAll = mutableListOf<String>()
    private val archivedTags = mutableListOf<String>()
    private val unarchivedTags = mutableListOf<String>()
    private val deletedTags = mutableListOf<String>()

    private fun show(state: TagsUiState?, uses: Int = 0) = composeRule.setContent {
        TarryTheme {
            TagsContent(
                state = state,
                actions = TagsActions(
                    loadTagUses = { uses },
                    onCreateGroup = { createdGroups += it },
                    onRenameGroup = { _, _ -> },
                    onGroupColour = { id, argb -> groupColours += id to argb },
                    onArchiveAll = { archivedAll += it },
                    onDeleteGroup = { deletedGroups += it },
                    onReorderGroups = { groupReorders += it },
                    onCreateTag = { g, n -> createdTags += g to n },
                    onRenameTag = { _, _ -> },
                    onArchiveTag = { archivedTags += it },
                    onUnarchiveTag = { unarchivedTags += it },
                    onDeleteTag = { deletedTags += it },
                    onReorderTags = { g, ids -> tagReorders += g to ids },
                ),
            )
        }
    }

    private fun customAction(description: String, label: String): Boolean {
        val node = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].firstOrNull { it.label == label } ?: return false
        composeRule.runOnUiThread { action.action() }
        composeRule.waitForIdle()
        return true
    }

    private fun hasCustomAction(description: String, label: String): Boolean =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].any { it.label == label }

    @Test
    fun emptyStateOffersTheNewGroupAction() {
        show(TagsUiState(emptyList()))

        composeRule.onNodeWithText("No tag groups yet").assertExists()
        composeRule.onNodeWithText("New group").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Materials")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(createdGroups).containsExactly("Materials")
    }

    @Test
    fun showsGroupsWithLabelledHandlesOfAtLeast48dp() {
        show(TagsUiState(listOf(work, home)))

        composeRule.onNodeWithText("Work").assertExists()
        composeRule.onNodeWithText("Alpha").assertExists()
        composeRule.onNodeWithContentDescription("Reorder Work").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Reorder Alpha").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Actions for Alpha").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Actions for Work").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aSingleGroupOrTagHasNothingToReorder() {
        show(TagsUiState(listOf(GroupRow("g", "Solo", 0, listOf(TagRow("t", "Only", false))))))

        composeRule.onNodeWithContentDescription("Reorder Solo").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Reorder Only").assertDoesNotExist()
    }

    @Test
    fun moveDownAndMoveUpCustomActionsEachCommitOnce() {
        show(TagsUiState(listOf(work, home)))

        assertThat(customAction("Reorder Bravo", "Move down")).isTrue()
        assertThat(tagReorders).containsExactly("work" to listOf("a", "c", "b"))

        assertThat(customAction("Reorder Charlie", "Move up")).isTrue()
        assertThat(tagReorders).hasSize(2)
        // The list shows the committed order straight away: after Bravo moved down, Charlie was second.
        assertThat(tagReorders.last()).isEqualTo("work" to listOf("c", "a", "b"))
    }

    @Test
    fun endsOfTheListOfferOnlyTheMovesThatArePossible() {
        show(TagsUiState(listOf(work, home)))

        assertThat(hasCustomAction("Reorder Alpha", "Move up")).isFalse()
        assertThat(hasCustomAction("Reorder Alpha", "Move down")).isTrue()
        assertThat(hasCustomAction("Reorder Charlie", "Move down")).isFalse()
        assertThat(hasCustomAction("Reorder Charlie", "Move up")).isTrue()
        assertThat(hasCustomAction("Reorder Work", "Move up")).isFalse()
        assertThat(hasCustomAction("Reorder Home", "Move up")).isTrue()
    }

    @Test
    fun groupsHaveMoveActionsToo() {
        show(TagsUiState(listOf(work, home)))

        assertThat(customAction("Reorder Work", "Move down")).isTrue()

        assertThat(groupReorders).containsExactly(listOf("home", "work"))
        assertThat(tagReorders).isEmpty()
    }

    @Test
    fun longPressThenDragCommitsOnceWithTheNewOrder() {
        show(TagsUiState(listOf(work, home)))

        dragHandle("Reorder Alpha", dy = 70f)

        // Alpha crossed Bravo's midpoint only: one commit, still inside Work.
        assertThat(tagReorders).containsExactly("work" to listOf("b", "a", "c"))
        assertThat(groupReorders).isEmpty()
    }

    @Test
    fun aDragFarBeyondTheGroupStaysInsideItsGroup() {
        show(TagsUiState(listOf(work, home)))

        dragHandle("Reorder Alpha", dy = 2_000f)

        assertThat(tagReorders).containsExactly("work" to listOf("b", "c", "a"))
        assertThat(groupReorders).isEmpty()
    }

    @Test
    fun aDragThatDoesNotCrossAMidpointCommitsNothing() {
        show(TagsUiState(listOf(work, home)))

        dragHandle("Reorder Alpha", dy = 10f)

        assertThat(tagReorders).isEmpty()
    }

    @Test
    fun draggingAGroupHandleReordersGroups() {
        show(TagsUiState(listOf(work, home)))

        dragHandle("Reorder Home", dy = -2_000f)

        assertThat(groupReorders).containsExactly(listOf("home", "work"))
        assertThat(tagReorders).isEmpty()
    }

    private fun dragHandle(description: String, dy: Float) {
        composeRule.onNodeWithContentDescription(description).performScrollTo()
        composeRule.onNodeWithContentDescription(description).performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(1_500)
        composeRule.onNodeWithContentDescription(description).performTouchInput {
            moveBy(Offset(0f, dy / 2))
            moveBy(Offset(0f, dy / 2))
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun archivedTagsAreMarkedAndOfferUnarchiveButNoHandle() {
        show(TagsUiState(listOf(work, home)))

        composeRule.onNodeWithText("Retired").assertExists()
        composeRule.onNodeWithText("Archived").assertExists()
        composeRule.onNodeWithContentDescription("Reorder Retired").assertDoesNotExist()
        composeRule.onNodeWithText("Unarchive").assertHeightIsAtLeast(48.dp).performClick()

        assertThat(unarchivedTags).containsExactly("old")
    }

    @Test
    fun groupDeleteIsDisabledWithAReasonUnlessTheGroupIsEmpty() {
        show(TagsUiState(listOf(work, GroupRow("empty", "Spare", 0, emptyList()))))

        composeRule.onNodeWithContentDescription("Actions for Work").performClick()
        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("Cannot delete: the group still has 4 tags", substring = true).assertExists()
    }

    @Test
    fun anEmptyGroupCanBeDeletedAfterConfirming() {
        show(TagsUiState(listOf(work, GroupRow("empty", "Spare", 0, emptyList()))))

        composeRule.onNodeWithContentDescription("Actions for Spare").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Delete group Spare?").assertExists()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()

        assertThat(deletedGroups).containsExactly("empty")
    }

    @Test
    fun archiveAllTagsIsOneTapWithUndoElsewhere() {
        show(TagsUiState(listOf(work, home)))

        composeRule.onNodeWithContentDescription("Actions for Work").performClick()
        composeRule.onNodeWithText("Archive all tags").performClick()

        assertThat(archivedAll).containsExactly("work")
    }

    @Test
    fun groupColourOffersNoColourAndTheEightSlots() {
        show(TagsUiState(listOf(work, home)))

        composeRule.onNodeWithContentDescription("Actions for Work").performClick()
        composeRule.onNodeWithText("Colour").performClick()
        for (slot in JobPalette.slots) composeRule.onNodeWithContentDescription(slot.name).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("No colour").performClick()

        assertThat(groupColours).containsExactly("work" to 0)
    }

    @Test
    fun tagMenuArchivesAndBlocksDeleteWhileInUse() {
        show(TagsUiState(listOf(work, home)), uses = 2)

        composeRule.onNodeWithContentDescription("Actions for Alpha").performClick()
        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("Cannot delete: used by 2 visits, trips or place defaults", substring = true).assertExists()
        composeRule.onNodeWithText("Archive").performClick()

        assertThat(archivedTags).containsExactly("a")
        assertThat(deletedTags).isEmpty()
    }

    @Test
    fun tagMenuDeletesAnUnusedTagAfterConfirming() {
        show(TagsUiState(listOf(work, home)), uses = 0)

        composeRule.onNodeWithContentDescription("Actions for Bravo").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete tag Bravo?").assertExists()
        composeRule.onNode(hasText("Delete") and hasClickAction()).performClick()

        assertThat(deletedTags).containsExactly("b")
    }

    @Test
    fun newTagIsCreatedInItsOwnGroup() {
        show(TagsUiState(listOf(work, home)))

        composeRule.onAllNodesWithText("New tag")[1].performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Boiler")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(createdTags).containsExactly("home" to "Boiler")
    }
}
