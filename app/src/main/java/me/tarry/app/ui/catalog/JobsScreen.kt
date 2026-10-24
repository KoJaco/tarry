package me.tarry.app.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import me.tarry.app.ui.components.LoadingBlocks
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.drawer.ConfirmDialog
import me.tarry.app.ui.drawer.JobOption
import me.tarry.app.ui.drawer.JobSwatch
import me.tarry.app.ui.drawer.NameDialog
import me.tarry.app.ui.theme.TarryTheme
import androidx.compose.foundation.layout.PaddingValues

private val JobRowMinHeight = 56.dp

/** Hilt-wired Jobs screen, pushed over Settings. Undo of an archive shows in the shell's snackbar host. */
@Composable
fun JobsScreen(modifier: Modifier = Modifier, viewModel: JobsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    JobsContent(
        state = state,
        loadUses = viewModel::uses,
        onCreate = { viewModel.create(it) },
        onRename = { id, name -> viewModel.rename(id, name) },
        onColour = { id, argb -> viewModel.setColour(id, argb) },
        onArchive = { viewModel.archive(it) },
        onUnarchive = { viewModel.unarchive(it) },
        onDelete = { viewModel.delete(it) },
        modifier = modifier,
    )
}

private enum class JobDialog { ACTIONS, COLOUR, RENAME, ARCHIVE, DELETE, NEW }

/** Stateless Jobs list with its actions sheet and dialogs. [loadUses] counts what blocks deleting a job. */
@Composable
fun JobsContent(
    state: JobsUiState?,
    loadUses: suspend (String) -> Int,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onColour: (String, Int) -> Unit,
    onArchive: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by rememberSaveable { mutableStateOf<JobDialog?>(null) }
    val selected = state?.let { s -> (s.active + s.archived).firstOrNull { it.id == selectedId } }
    val close = {
        dialog = null
        selectedId = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(
            text = stringResource(R.string.jobs_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TarryTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        when {
            state == null -> LoadingBlocks(count = 3)
            state.isEmpty -> EmptyState(stringResource(R.string.jobs_empty_title), stringResource(R.string.jobs_empty_body))
            else -> {
                JobCard(state.active) { selectedId = it; dialog = JobDialog.ACTIONS }
                if (state.archived.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.catalog_archived_section), Modifier.padding(top = space.m))
                    JobCard(state.archived) { selectedId = it; dialog = JobDialog.ACTIONS }
                }
            }
        }
        if (state != null) {
            SecondaryButton(
                stringResource(R.string.jobs_new),
                onClick = { dialog = JobDialog.NEW },
                modifier = Modifier.fillMaxWidth().padding(top = space.m),
            )
        }
    }

    if (dialog == JobDialog.NEW) {
        NameDialog(
            title = stringResource(R.string.edit_job_new_title),
            label = stringResource(R.string.edit_job_name_label),
            onConfirm = { onCreate(it); close() },
            onDismiss = close,
        )
    }
    if (selected == null) return
    val name = selected.name
    when (dialog) {
        JobDialog.ACTIONS -> {
            val uses by produceState<Int?>(null, selected.id) { value = loadUses(selected.id) }
            val blocked = uses?.takeIf { it > 0 }
            ActionsSheet(
                title = name,
                actions = listOf(
                    SheetAction(stringResource(R.string.catalog_action_rename), { dialog = JobDialog.RENAME }),
                    SheetAction(stringResource(R.string.catalog_action_colour), { dialog = JobDialog.COLOUR }),
                    if (selected.archived) {
                        SheetAction(stringResource(R.string.catalog_action_unarchive), { onUnarchive(selected.id); close() })
                    } else {
                        SheetAction(stringResource(R.string.catalog_action_archive), { dialog = JobDialog.ARCHIVE })
                    },
                    SheetAction(
                        label = stringResource(R.string.catalog_action_delete),
                        onClick = { dialog = JobDialog.DELETE },
                        enabled = uses == 0,
                        reason = when {
                            uses == null -> stringResource(R.string.catalog_checking_use)
                            blocked != null -> pluralStringResource(R.plurals.jobs_delete_blocked, blocked, blocked)
                            else -> null
                        },
                        destructive = true,
                    ),
                ),
                onDismiss = close,
            )
        }
        JobDialog.COLOUR -> SheetFrame(stringResource(R.string.jobs_colour_title, name), onDismiss = close) {
            ColourGrid(selectedArgb = selected.colorArgb, allowNone = false, onPick = { onColour(selected.id, it); close() })
        }
        JobDialog.RENAME -> NameDialog(
            title = stringResource(R.string.jobs_rename_title),
            label = stringResource(R.string.edit_job_name_label),
            initial = name,
            onConfirm = { onRename(selected.id, it); close() },
            onDismiss = close,
        )
        JobDialog.ARCHIVE -> ConfirmDialog(
            title = stringResource(R.string.jobs_archive_title, name),
            body = if (selected.placeDefaults > 0) {
                pluralStringResource(R.plurals.jobs_archive_clears_places, selected.placeDefaults, selected.placeDefaults)
            } else {
                stringResource(R.string.jobs_archive_body)
            },
            confirmLabel = stringResource(R.string.catalog_action_archive),
            onConfirm = { onArchive(selected.id); close() },
            onDismiss = close,
        )
        JobDialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.jobs_delete_title, name),
            body = stringResource(R.string.jobs_delete_body),
            confirmLabel = stringResource(R.string.catalog_action_delete),
            onConfirm = { onDelete(selected.id); close() },
            onDismiss = close,
        )
        else -> Unit
    }
}

@Composable
private fun JobCard(rows: List<JobRow>, onClick: (String) -> Unit) {
    TarryCard(contentPadding = PaddingValues(0.dp)) {
        rows.forEachIndexed { index, job ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onClick(job.id) }
                    .heightIn(min = JobRowMinHeight)
                    .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
            ) {
                JobSwatch(JobOption(job.id, job.name, job.colorArgb))
                Text(
                    job.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TarryTheme.colors.ink,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
