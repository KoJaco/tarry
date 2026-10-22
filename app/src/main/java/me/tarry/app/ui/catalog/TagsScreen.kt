package me.tarry.app.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.R
import me.tarry.app.ui.components.EmptyState
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.LoadingBlocks
import me.tarry.app.ui.components.ReorderState
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.components.TarryChip
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.components.receded
import me.tarry.app.ui.components.reorderHandle
import me.tarry.app.ui.components.reorderItem
import me.tarry.app.ui.components.rememberReorderState
import me.tarry.app.ui.drawer.ConfirmDialog
import me.tarry.app.ui.drawer.JobOption
import me.tarry.app.ui.drawer.JobSwatch
import me.tarry.app.ui.drawer.NameDialog
import me.tarry.app.ui.theme.TarryTheme

private val HandleSize = 48.dp

/** Hilt-wired Tags screen, pushed over Settings. Undo of an archive shows in the shell's snackbar host. */
@Composable
fun TagsScreen(modifier: Modifier = Modifier, viewModel: TagsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TagsContent(
        state = state,
        actions = TagsActions(
            loadTagUses = viewModel::tagUses,
            onCreateGroup = { viewModel.createGroup(it) },
            onRenameGroup = { id, name -> viewModel.renameGroup(id, name) },
            onGroupColour = { id, argb -> viewModel.setGroupColour(id, argb) },
            onArchiveAll = { viewModel.archiveAll(it) },
            onDeleteGroup = { viewModel.deleteGroup(it) },
            onReorderGroups = { viewModel.reorderGroups(it) },
            onCreateTag = { group, name -> viewModel.createTag(group, name) },
            onRenameTag = { id, name -> viewModel.renameTag(id, name) },
            onArchiveTag = { viewModel.archiveTag(it) },
            onUnarchiveTag = { viewModel.unarchiveTag(it) },
            onDeleteTag = { viewModel.deleteTag(it) },
            onReorderTags = { group, ids -> viewModel.reorderTags(group, ids) },
        ),
        modifier = modifier,
    )
}

/** Everything the Tags screen can do; [loadTagUses] counts what blocks deleting a tag. */
class TagsActions(
    val loadTagUses: suspend (String) -> Int,
    val onCreateGroup: (String) -> Unit,
    val onRenameGroup: (String, String) -> Unit,
    val onGroupColour: (String, Int) -> Unit,
    val onArchiveAll: (String) -> Unit,
    val onDeleteGroup: (String) -> Unit,
    val onReorderGroups: (List<String>) -> Unit,
    val onCreateTag: (groupId: String, name: String) -> Unit,
    val onRenameTag: (String, String) -> Unit,
    val onArchiveTag: (String) -> Unit,
    val onUnarchiveTag: (String) -> Unit,
    val onDeleteTag: (String) -> Unit,
    val onReorderTags: (groupId: String, activeIds: List<String>) -> Unit,
)

private enum class TagDialog { NEW_GROUP, NEW_TAG, GROUP_ACTIONS, GROUP_COLOUR, GROUP_RENAME, GROUP_DELETE, TAG_ACTIONS, TAG_RENAME, TAG_DELETE }

/** Stateless Tags screen: one section per group, groups and tags reorderable, menus as bottom sheets. */
@Composable
fun TagsContent(state: TagsUiState?, actions: TagsActions, modifier: Modifier = Modifier) {
    val space = TarryTheme.space
    var dialog by rememberSaveable { mutableStateOf<TagDialog?>(null) }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = { kind: TagDialog, id: String? ->
        targetId = id
        dialog = kind
    }
    val close = {
        dialog = null
        targetId = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(
            text = stringResource(R.string.tags_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TarryTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        when {
            state == null -> LoadingBlocks(count = 3)
            state.groups.isEmpty() -> EmptyState(stringResource(R.string.tags_empty_title), stringResource(R.string.tags_empty_body))
            else -> {
                val groupIds = state.groups.map { it.id }
                val groupReorder = rememberReorderState(groupIds) { actions.onReorderGroups(it) }
                Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
                    groupReorder.order.forEach { id ->
                        val group = state.groups.firstOrNull { it.id == id } ?: return@forEach
                        key(id) {
                            GroupSection(
                                group = group,
                                groupReorder = groupReorder,
                                canReorderGroups = groupIds.size > 1,
                                actions = actions,
                                onGroupMenu = { open(TagDialog.GROUP_ACTIONS, group.id) },
                                onNewTag = { open(TagDialog.NEW_TAG, group.id) },
                                onTagMenu = { open(TagDialog.TAG_ACTIONS, it) },
                                modifier = Modifier.reorderItem(groupReorder, id),
                            )
                        }
                    }
                }
            }
        }
        if (state != null) {
            SecondaryButton(
                stringResource(R.string.tags_new_group),
                onClick = { open(TagDialog.NEW_GROUP, null) },
                modifier = Modifier.fillMaxWidth().padding(top = space.m),
            )
        }
    }

    if (state == null) return
    val group = state.groups.firstOrNull { it.id == targetId }
        ?: state.groups.firstOrNull { g -> g.tags.any { it.id == targetId } }
    val tag = state.groups.flatMap { it.tags }.firstOrNull { it.id == targetId }
    when (dialog) {
        TagDialog.NEW_GROUP -> NameDialog(
            title = stringResource(R.string.tags_new_group_title),
            label = stringResource(R.string.tags_group_name_label),
            onConfirm = { actions.onCreateGroup(it); close() },
            onDismiss = close,
        )
        TagDialog.NEW_TAG -> if (group != null) {
            NameDialog(
                title = stringResource(R.string.edit_tag_new_title),
                label = stringResource(R.string.edit_tag_name_label),
                onConfirm = { actions.onCreateTag(group.id, it); close() },
                onDismiss = close,
            )
        }
        TagDialog.GROUP_ACTIONS -> if (group != null) {
            val tagCount = group.tags.size
            ActionsSheet(
                title = group.name,
                actions = listOf(
                    SheetAction(stringResource(R.string.catalog_action_rename), { dialog = TagDialog.GROUP_RENAME }),
                    SheetAction(stringResource(R.string.catalog_action_colour), { dialog = TagDialog.GROUP_COLOUR }),
                    SheetAction(
                        stringResource(R.string.tags_archive_all),
                        { actions.onArchiveAll(group.id); close() },
                        enabled = group.activeTags.isNotEmpty(),
                    ),
                    SheetAction(
                        label = stringResource(R.string.catalog_action_delete),
                        onClick = { dialog = TagDialog.GROUP_DELETE },
                        enabled = tagCount == 0,
                        reason = if (tagCount > 0) pluralStringResource(R.plurals.tags_delete_group_blocked, tagCount, tagCount) else null,
                        destructive = true,
                    ),
                ),
                onDismiss = close,
            )
        }
        TagDialog.GROUP_COLOUR -> if (group != null) {
            SheetFrame(stringResource(R.string.jobs_colour_title, group.name), onDismiss = close) {
                ColourGrid(selectedArgb = group.colorArgb, allowNone = true, onPick = { actions.onGroupColour(group.id, it); close() })
            }
        }
        TagDialog.GROUP_RENAME -> if (group != null) {
            NameDialog(
                title = stringResource(R.string.tags_rename_group_title),
                label = stringResource(R.string.tags_group_name_label),
                initial = group.name,
                onConfirm = { actions.onRenameGroup(group.id, it); close() },
                onDismiss = close,
            )
        }
        TagDialog.GROUP_DELETE -> if (group != null) {
            ConfirmDialog(
                title = stringResource(R.string.tags_delete_group_title, group.name),
                body = stringResource(R.string.tags_delete_group_body),
                confirmLabel = stringResource(R.string.catalog_action_delete),
                onConfirm = { actions.onDeleteGroup(group.id); close() },
                onDismiss = close,
            )
        }
        TagDialog.TAG_ACTIONS -> if (tag != null) {
            val uses by produceState<Int?>(null, tag.id) { value = actions.loadTagUses(tag.id) }
            val blocked = uses?.takeIf { it > 0 }
            ActionsSheet(
                title = tag.name,
                actions = listOf(
                    SheetAction(stringResource(R.string.catalog_action_rename), { dialog = TagDialog.TAG_RENAME }),
                    if (tag.archived) {
                        SheetAction(stringResource(R.string.catalog_action_unarchive), { actions.onUnarchiveTag(tag.id); close() })
                    } else {
                        SheetAction(stringResource(R.string.catalog_action_archive), { actions.onArchiveTag(tag.id); close() })
                    },
                    SheetAction(
                        label = stringResource(R.string.catalog_action_delete),
                        onClick = { dialog = TagDialog.TAG_DELETE },
                        enabled = uses == 0,
                        reason = when {
                            uses == null -> stringResource(R.string.catalog_checking_use)
                            blocked != null -> pluralStringResource(R.plurals.tags_delete_tag_blocked, blocked, blocked)
                            else -> null
                        },
                        destructive = true,
                    ),
                ),
                onDismiss = close,
            )
        }
        TagDialog.TAG_RENAME -> if (tag != null) {
            NameDialog(
                title = stringResource(R.string.tags_rename_tag_title),
                label = stringResource(R.string.edit_tag_name_label),
                initial = tag.name,
                onConfirm = { actions.onRenameTag(tag.id, it); close() },
                onDismiss = close,
            )
        }
        TagDialog.TAG_DELETE -> if (tag != null) {
            ConfirmDialog(
                title = stringResource(R.string.tags_delete_tag_title, tag.name),
                body = stringResource(R.string.tags_delete_tag_body),
                confirmLabel = stringResource(R.string.catalog_action_delete),
                onConfirm = { actions.onDeleteTag(tag.id); close() },
                onDismiss = close,
            )
        }
        null -> Unit
    }
}

@Composable
private fun GroupSection(
    group: GroupRow,
    groupReorder: ReorderState<String>,
    canReorderGroups: Boolean,
    actions: TagsActions,
    onGroupMenu: () -> Unit,
    onNewTag: () -> Unit,
    onTagMenu: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    val active = group.activeTags
    val tagReorder = rememberReorderState(active.map { it.id }) { actions.onReorderTags(group.id, it) }
    TarryCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s)) {
                if (canReorderGroups) {
                    ReorderHandle(
                        Modifier.reorderHandle(
                            groupReorder, group.id,
                            label = stringResource(R.string.tags_reorder, group.name),
                            moveUpLabel = stringResource(R.string.tags_move_up),
                            moveDownLabel = stringResource(R.string.tags_move_down),
                        ),
                    )
                }
                if (group.colorArgb != 0) JobSwatch(JobOption(group.id, group.name, group.colorArgb))
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TarryTheme.colors.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconCircleButton(
                    icon = R.drawable.ic_more,
                    contentDescription = stringResource(R.string.catalog_actions_for, group.name),
                    onClick = onGroupMenu,
                    containerColor = TarryTheme.colors.inset,
                )
            }
            if (group.tags.isEmpty()) {
                Text(stringResource(R.string.tags_group_empty), style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
                tagReorder.order.forEach { id ->
                    val tag = active.firstOrNull { it.id == id } ?: return@forEach
                    key(id) {
                        Row(
                            modifier = Modifier.fillMaxWidth().reorderItem(tagReorder, id),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(space.s),
                        ) {
                            if (active.size > 1) {
                                ReorderHandle(
                                    Modifier.reorderHandle(
                                        tagReorder, id,
                                        label = stringResource(R.string.tags_reorder, tag.name),
                                        moveUpLabel = stringResource(R.string.tags_move_up),
                                        moveDownLabel = stringResource(R.string.tags_move_down),
                                    ),
                                )
                            }
                            Box(Modifier.weight(1f)) { TagChip(tag, onClick = { onTagMenu(tag.id) }) }
                            TagMenuButton(tag, onClick = { onTagMenu(tag.id) })
                        }
                    }
                }
            }
            group.archivedTags.forEach { tag -> ArchivedTagRow(tag, onUnarchive = { actions.onUnarchiveTag(tag.id) }, onMenu = { onTagMenu(tag.id) }) }
            TertiaryButton(stringResource(R.string.tags_new_tag), onClick = onNewTag)
        }
    }
}

@Composable
private fun TagChip(tag: TagRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TarryChip(text = tag.name, selected = false, onClick = onClick, modifier = modifier, role = Role.Button, unselectedColor = TarryTheme.colors.inset)
}

@Composable
private fun TagMenuButton(tag: TagRow, onClick: () -> Unit) {
    IconCircleButton(
        icon = R.drawable.ic_more,
        contentDescription = stringResource(R.string.catalog_actions_for, tag.name),
        onClick = onClick,
        containerColor = TarryTheme.colors.inset,
    )
}

/** An archived tag: dimmed, marked with the word "Archived" (never dimming alone), with a one-tap Unarchive. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArchivedTagRow(tag: TagRow, onUnarchive: () -> Unit, onMenu: () -> Unit) {
    val space = TarryTheme.space
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.s),
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(space.s),
            verticalArrangement = Arrangement.spacedBy(space.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.receded(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.s),
            ) {
                TagChip(tag, onClick = onMenu)
                Text(
                    stringResource(R.string.tags_archived_marker),
                    style = MaterialTheme.typography.labelMedium,
                    color = TarryTheme.colors.muted,
                )
            }
            TertiaryButton(stringResource(R.string.catalog_action_unarchive), onClick = onUnarchive)
        }
        TagMenuButton(tag, onClick = onMenu)
    }
}

/** 48 dp drag handle target; [modifier] carries the reorder gesture and semantics. */
@Composable
private fun ReorderHandle(modifier: Modifier) {
    Box(modifier = modifier.size(HandleSize), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_drag_handle),
            contentDescription = null,
            tint = TarryTheme.colors.muted,
            modifier = Modifier.size(24.dp),
        )
    }
}
