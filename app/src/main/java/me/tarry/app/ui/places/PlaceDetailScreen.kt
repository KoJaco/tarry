package me.tarry.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
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
import me.tarry.app.segmentation.RadiusPreviewInput
import me.tarry.app.ui.catalog.SheetFrame
import me.tarry.app.ui.components.DestructiveButton
import me.tarry.app.ui.components.LoadingBlocks
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.components.TarryChip
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.drawer.ConfirmDialog
import me.tarry.app.ui.drawer.JobPickerSheet
import me.tarry.app.ui.drawer.NameDialog
import me.tarry.app.ui.drawer.PlacePickerSheet
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.repository.MergePreview
import me.tarry.core.data.repository.PlaceLimits
import kotlin.math.roundToInt

private enum class PlaceDialog { NAME, JOB, RADIUS, MERGE_PICK, MERGE_CONFIRM, DELETE }

/** Hilt-wired place detail, pushed over the Places list. Closes itself when the place is deleted, pruned or merged away. */
@Composable
fun PlaceDetailScreen(placeId: String, onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: PlaceDetailViewModel = hiltViewModel(key = placeId)) {
    LaunchedEffect(placeId) { viewModel.load(placeId) }
    val load by viewModel.state.collectAsStateWithLifecycle()
    PlaceDetailContent(
        load = load,
        onClose = onClose,
        loadRadiusInput = viewModel::radiusInput,
        loadMergePreview = viewModel::previewMerge,
        onRename = { viewModel.rename(it) },
        onSetJob = { viewModel.setDefaultJob(it) },
        onSetIgnored = { viewModel.setIgnored(it) },
        onSaveRadius = { viewModel.setRadius(it) },
        onMerge = { viewModel.merge(it) },
        onDelete = { viewModel.delete() },
        modifier = modifier,
    )
}

/** Stateless place detail: defaults, ignore, radius, merge and delete, each behind its own sheet or confirm dialog. */
@Composable
fun PlaceDetailContent(
    load: PlaceDetailLoad,
    onClose: () -> Unit,
    loadRadiusInput: suspend () -> RadiusPreviewInput?,
    loadMergePreview: suspend (String) -> MergePreview?,
    onRename: (String) -> Unit,
    onSetJob: (String?) -> Unit,
    onSetIgnored: (Boolean) -> Unit,
    onSaveRadius: (Float) -> Unit,
    onMerge: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    var dialog by rememberSaveable { mutableStateOf<PlaceDialog?>(null) }
    var mergeTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val closeDialog = { dialog = null }
    if (load is PlaceDetailLoad.Missing) LaunchedEffect(Unit) { onClose() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        val ui = (load as? PlaceDetailLoad.Ready)?.ui
        if (ui == null) {
            if (load is PlaceDetailLoad.Loading) LoadingBlocks(count = 3)
            return@Column
        }
        Text(
            ui.label,
            style = MaterialTheme.typography.headlineMedium,
            color = TarryTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        val sub = buildList {
            if (ui.name != null && ui.address != null) add(ui.address)
            add(pluralStringResource(R.plurals.places_visit_count, ui.visitCount, ui.visitCount))
        }.joinToString(" · ")
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)

        val nameTitle = stringResource(R.string.place_name_row)
        val nameValue = ui.name ?: stringResource(R.string.place_detail_not_named)
        val jobTitle = stringResource(R.string.place_default_job_row)
        val jobValue = ui.defaultJob?.name ?: stringResource(R.string.place_default_job_none)
        val ignoreTitle = stringResource(R.string.place_ignore_row)
        val ignoreSub = stringResource(R.string.place_ignore_subtitle)
        val radiusTitle = stringResource(R.string.place_radius_row)
        val radiusValue = stringResource(R.string.place_radius_value, ui.radiusM.roundToInt())
        SettingsGroup(Modifier.padding(top = space.s)) {
            row(nameTitle, onClick = { dialog = PlaceDialog.NAME }, subtitle = nameValue)
            row(jobTitle, onClick = { dialog = PlaceDialog.JOB }, subtitle = jobValue)
            switchRow(ignoreTitle, checked = ui.ignored, onCheckedChange = onSetIgnored, subtitle = ignoreSub)
            row(radiusTitle, onClick = { dialog = PlaceDialog.RADIUS }, subtitle = radiusValue)
        }
        Text(stringResource(R.string.place_defaults_hint), style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        SecondaryButton(
            stringResource(R.string.place_merge_action),
            onClick = { dialog = PlaceDialog.MERGE_PICK },
            enabled = ui.otherPlaces.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = space.m),
        )
        DestructiveButton(stringResource(R.string.place_delete_action), onClick = { dialog = PlaceDialog.DELETE }, modifier = Modifier.fillMaxWidth())

        when (dialog) {
            PlaceDialog.NAME -> NameDialog(
                title = stringResource(R.string.place_name_dialog_title),
                label = stringResource(R.string.edit_rename_place_label),
                initial = ui.name.orEmpty(),
                allowBlank = true,
                onConfirm = { onRename(it); closeDialog() },
                onDismiss = closeDialog,
            )
            PlaceDialog.JOB -> JobPickerSheet(
                title = stringResource(R.string.place_default_job_picker_title),
                noneLabel = stringResource(R.string.place_default_job_none_option),
                jobs = ui.pickableJobs,
                selectedId = ui.defaultJob?.id,
                onSelect = { onSetJob(it); closeDialog() },
                onNewJob = null,
                onDismiss = closeDialog,
            )
            PlaceDialog.RADIUS -> {
                val input by produceState<RadiusPreviewInput?>(null) { value = loadRadiusInput() }
                PlaceRadiusSheet(
                    initialRadiusM = ui.radiusM,
                    input = input,
                    onSave = { onSaveRadius(it); closeDialog() },
                    onDismiss = closeDialog,
                )
            }
            PlaceDialog.MERGE_PICK -> PlacePickerSheet(
                places = ui.otherPlaces,
                onSelect = { mergeTargetId = it; dialog = PlaceDialog.MERGE_CONFIRM },
                onDismiss = closeDialog,
                title = stringResource(R.string.place_merge_picker_title),
            )
            PlaceDialog.MERGE_CONFIRM -> {
                val target = ui.otherPlaces.firstOrNull { it.id == mergeTargetId }
                if (target == null) {
                    LaunchedEffect(Unit) { closeDialog() }
                } else {
                    val merge by produceState<MergePreview?>(null, target.id) { value = loadMergePreview(target.id) }
                    ConfirmDialog(
                        title = stringResource(R.string.place_merge_confirm_title, target.label),
                        body = mergeBody(target.label, merge),
                        confirmLabel = stringResource(R.string.place_merge_confirm),
                        onConfirm = { onMerge(target.id); closeDialog() },
                        onDismiss = closeDialog,
                        confirmEnabled = merge != null,
                    )
                }
            }
            PlaceDialog.DELETE -> ConfirmDialog(
                title = stringResource(R.string.place_delete_confirm_title, ui.label),
                body = stringResource(R.string.place_delete_confirm_body),
                confirmLabel = stringResource(R.string.place_delete_confirm),
                onConfirm = { onDelete(); closeDialog() },
                onDismiss = closeDialog,
            )
            null -> Unit
        }
    }
}

@Composable
private fun mergeBody(targetLabel: String, preview: MergePreview?): String {
    val head = if (preview == null) {
        stringResource(R.string.place_merge_checking)
    } else {
        stringResource(R.string.place_merge_confirm_body, targetLabel, preview.radiusM.roundToInt())
    }
    val cap = if (preview?.capped == true) stringResource(R.string.place_merge_capped, PlaceLimits.RADIUS_MAX_M.roundToInt()) else null
    return listOfNotNull(head, cap, stringResource(R.string.place_merge_defaults_note)).joinToString("\n\n")
}
