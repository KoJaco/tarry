package me.tarry.app.ui.catalog

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.R
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.core.data.repository.CatalogEditor
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.TagRepository
import me.tarry.core.data.repository.TimelineEditor
import javax.inject.Inject

@Immutable
data class TagRow(val id: String, val name: String, val archived: Boolean)

/** [tags] keeps the stored order (archived tags included); the screen shows [activeTags] first, then [archivedTags]. */
@Immutable
data class GroupRow(val id: String, val name: String, val colorArgb: Int, val tags: List<TagRow>) {
    val activeTags: List<TagRow> get() = tags.filterNot { it.archived }
    val archivedTags: List<TagRow> get() = tags.filter { it.archived }
}

@Immutable
data class TagsUiState(val groups: List<GroupRow>)

/** Backs the Tags screen: groups and their tags, create, rename, recolour, archive (undoable), delete, reorder. */
@HiltViewModel
class TagsViewModel @Inject constructor(
    private val tags: TagRepository,
    private val catalog: CatalogEditor,
    private val timeline: TimelineEditor,
    private val undoEvents: UndoEvents,
) : ViewModel() {
    /** Null until the first read finishes. */
    val state: StateFlow<TagsUiState?> = combine(tags.observeGroups(), tags.observeTags()) { groups, allTags ->
        val byGroup = allTags.groupBy { it.groupId }
        TagsUiState(
            groups.map { g ->
                GroupRow(
                    g.id, g.name, g.colorArgb,
                    byGroup[g.id].orEmpty().sortedBy { it.sortOrder }.map { TagRow(it.id, it.name, it.archived) },
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /** What blocks deleting the tag: live visits, trips and place defaults carrying it. */
    suspend fun tagUses(tagId: String): Int = catalog.tagUses(tagId)

    // region groups

    fun createGroup(name: String) = viewModelScope.launch { catalog.createTagGroup(name) }

    fun renameGroup(groupId: String, name: String) = viewModelScope.launch { catalog.renameTagGroup(groupId, name) }

    /** [colorArgb] is a `JobPalette` key, or 0 for none. */
    fun setGroupColour(groupId: String, colorArgb: Int) = viewModelScope.launch { catalog.setTagGroupColour(groupId, colorArgb) }

    fun archiveAll(groupId: String) = viewModelScope.launch {
        val result = catalog.archiveAllTags(groupId)
        if (result is EditResult.Ok) result.undo?.let { undoEvents.offer(UndoEvent(R.string.tags_undo_archived_all, it)) }
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch { catalog.deleteTagGroup(groupId) }

    fun reorderGroups(orderedIds: List<String>) = viewModelScope.launch { catalog.reorderTagGroups(orderedIds) }

    // endregion

    // region tags

    fun createTag(groupId: String, name: String) = viewModelScope.launch { timeline.createTag(name, groupId) }

    fun renameTag(tagId: String, name: String) = viewModelScope.launch { catalog.renameTag(tagId, name) }

    fun archiveTag(tagId: String) = viewModelScope.launch {
        val result = catalog.archiveTag(tagId)
        if (result is EditResult.Ok) result.undo?.let { undoEvents.offer(UndoEvent(R.string.tags_undo_archived, it)) }
    }

    fun unarchiveTag(tagId: String) = viewModelScope.launch { catalog.unarchiveTag(tagId) }

    fun deleteTag(tagId: String) = viewModelScope.launch { catalog.deleteTag(tagId) }

    /** [activeIds] is the new order of the group's active tags; its archived tags keep their place after them. */
    fun reorderTags(groupId: String, activeIds: List<String>) = viewModelScope.launch {
        val archivedIds = tags.tagsInGroup(groupId).filter { it.archived }.map { it.id }
        catalog.reorderTags(groupId, activeIds + archivedIds)
    }

    // endregion

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
