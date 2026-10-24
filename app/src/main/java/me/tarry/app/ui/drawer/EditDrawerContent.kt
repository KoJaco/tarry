package me.tarry.app.ui.drawer

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.tarry.app.R
import me.tarry.app.ui.components.DestructiveButton
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.components.SettingsGroupScope
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.TarryChip
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.timeline.formatDistance
import me.tarry.app.ui.timeline.formatDuration
import me.tarry.app.ui.timeline.rememberTimeFormats
import me.tarry.app.ui.timeline.rowLabelRes
import me.tarry.core.data.repository.MergeDirection
import java.time.ZoneId

private const val NOTES_DEBOUNCE_MS = 700L
private val CheckRowMinHeight = 56.dp
private val ErrorIconSize = 24.dp

/** Which dialog or picker sheet is open. Kept in the content so a rotation re-asks rather than loses edits. */
private sealed interface DrawerDialog {
    data object JobPicker : DrawerDialog
    data object NewJob : DrawerDialog
    data object NewTag : DrawerDialog
    data object RenamePlace : DrawerDialog
    data object PlacePicker : DrawerDialog
    data object ConfirmReset : DrawerDialog
    data object TrimStart : DrawerDialog
    data object TrimEnd : DrawerDialog
    data object Split : DrawerDialog
    data object NewStart : DrawerDialog
    data object NewEnd : DrawerDialog
    data object NewPlace : DrawerDialog
}

/**
 * The drawer body for a visit, a trip or a new visit (the create form). Stateless apart from which dialog is
 * open and the notes text being typed (saved on a short debounce, on blur and when the drawer closes).
 */
@Composable
fun EditDrawerContent(
    state: EditDrawerState,
    actions: EditDrawerActions,
    modifier: Modifier = Modifier,
    /** Extra rows under "Choose place" in the new-visit form: the seam for a later "Pick on map" option. */
    placeExtras: SettingsGroupScope.() -> Unit = {},
) {
    val space = TarryTheme.space
    var dialog by remember { mutableStateOf<DrawerDialog?>(null) }
    // New-visit form values; they survive rotation and are unused by the other kinds.
    var newStart by rememberSaveable(state.start) { mutableLongStateOf(state.start) }
    var newEnd by rememberSaveable(state.end) { mutableLongStateOf(state.end) }
    var newPlaceId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = space.gutter)
            .padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.xl),
    ) {
        DrawerHeader(state, onRename = { dialog = DrawerDialog.RenamePlace })
        when (state.kind) {
            EditKind.NEW_VISIT -> NewVisitForm(
                state = state,
                start = newStart,
                end = newEnd,
                placeId = newPlaceId,
                onOpen = { dialog = it },
                onSave = { newPlaceId?.let { actions.onCreateManual(it, newStart, newEnd) } },
                placeExtras = placeExtras,
            )
            EditKind.TRIP -> {
                ErrorBanner(state.error)
                AttributionSection(state) { dialog = DrawerDialog.JobPicker }
                TagsSection(state, actions, onNewTag = { dialog = DrawerDialog.NewTag })
                NotesSection(state, actions)
                TogglesSection(state, actions)
                val addStop = stringResource(R.string.edit_trip_add_stop)
                SettingsGroup { row(title = addStop, onClick = actions.onAddStop) }
            }
            EditKind.VISIT -> {
                JobSection(state) { dialog = DrawerDialog.JobPicker }
                TagsSection(state, actions, onNewTag = { dialog = DrawerDialog.NewTag })
                NotesSection(state, actions)
                TogglesSection(state, actions)
                TimeSection(state, actions, onOpen = { dialog = it })
            }
        }
    }
    DrawerDialogs(state, actions, dialog, onShow = { dialog = it }, onClose = { dialog = null })
    val zone = rememberZone(state.zoneId)
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    if (dialog == DrawerDialog.NewPlace) {
        PlacePickerSheet(
            places = state.places,
            onSelect = {
                newPlaceId = it
                dialog = null
            },
            onDismiss = { dialog = null },
            title = stringResource(R.string.edit_new_picker_title),
        )
    }
    if (dialog == DrawerDialog.NewStart || dialog == DrawerDialog.NewEnd) {
        val isStart = dialog == DrawerDialog.NewStart
        val anchor = if (isStart) newStart else newEnd
        TimePickerDialog(
            title = stringResource(if (isStart) R.string.edit_time_picker_trim_start else R.string.edit_time_picker_trim_end),
            initialMs = anchor,
            zone = zone,
            is24Hour = is24Hour,
            onConfirm = { hour, minute ->
                val picked = instantNear(hour, minute, zone, anchor)
                if (isStart) newStart = picked else newEnd = picked
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

/** Times and place for a manual visit. Save needs a place; the editor rejects empty ranges and overlaps (shown inline). */
@Composable
private fun NewVisitForm(
    state: EditDrawerState,
    start: Long,
    end: Long,
    placeId: String?,
    onOpen: (DrawerDialog) -> Unit,
    onSave: () -> Unit,
    placeExtras: SettingsGroupScope.() -> Unit,
) {
    val formats = rememberTimeFormats()
    val zone = rememberZone(state.zoneId)
    val placeLabel = placeId?.let { id -> state.places.firstOrNull { it.id == id }?.label }
    val startTitle = stringResource(R.string.edit_new_start)
    val endTitle = stringResource(R.string.edit_new_end)
    val choose = stringResource(R.string.edit_new_choose_place)
    ErrorBanner(state.error)
    Section(R.string.edit_new_section_when) {
        SettingsGroup {
            row(startTitle, onClick = { onOpen(DrawerDialog.NewStart) }, value = formats.time(start, zone))
            row(endTitle, onClick = { onOpen(DrawerDialog.NewEnd) }, value = formats.time(end, zone))
        }
    }
    Section(R.string.edit_new_section_place) {
        SettingsGroup {
            row(choose, onClick = { onOpen(DrawerDialog.NewPlace) }, value = placeLabel)
            placeExtras()
        }
        if (placeId == null) {
            Text(
                stringResource(R.string.edit_new_choose_place_required),
                style = MaterialTheme.typography.bodySmall,
                color = TarryTheme.colors.muted,
            )
        }
    }
    PrimaryButton(
        stringResource(R.string.edit_new_save),
        onClick = onSave,
        enabled = placeId != null,
        modifier = Modifier.fillMaxWidth(),
    )
}

// region header

@Composable
private fun DrawerHeader(state: EditDrawerState, onRename: () -> Unit) {
    val formats = rememberTimeFormats()
    val zone = rememberZone(state.zoneId)
    val range = stringResource(R.string.timeline_time_range, formats.time(state.start, zone), formats.time(state.end, zone))
    val duration = formatDuration(state.end - state.start)
    val title = when (state.kind) {
        EditKind.VISIT -> state.placeLabel ?: stringResource(R.string.timeline_unknown_place)
        EditKind.TRIP -> stringResource(state.tripMode.rowLabelRes())
        EditKind.NEW_VISIT -> stringResource(R.string.edit_drawer_title_new_visit)
    }
    val subtitle = if (state.kind == EditKind.TRIP) {
        stringResource(R.string.edit_header_range_distance, range, duration, formatDistance(state.distanceM))
    } else {
        stringResource(R.string.edit_header_range, range, duration)
    }
    Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = TarryTheme.colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (state.kind == EditKind.VISIT && state.hasPlace) {
                IconCircleButton(
                    icon = R.drawable.ic_edit,
                    contentDescription = stringResource(R.string.edit_rename_place),
                    onClick = onRename,
                )
            }
        }
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        if (state.needsReview) {
            Text(
                text = stringResource(R.string.timeline_needs_review),
                style = MaterialTheme.typography.labelMedium,
                color = TarryTheme.colors.onAccentSoft,
                modifier = Modifier
                    .padding(top = TarryTheme.space.xs)
                    .clip(TarryTheme.shapes.pill)
                    .background(TarryTheme.colors.accentSoft)
                    .padding(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.xs),
            )
        }
    }
}

@Composable
private fun rememberZone(zoneId: String): ZoneId =
    remember(zoneId) { runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault()) }

// endregion

// region sections

@Composable
private fun JobSection(state: EditDrawerState, onOpen: () -> Unit) {
    val own = state.ownJob
    val inherited = state.placeJob
    val subtitle = when {
        own != null -> own.name
        inherited != null -> stringResource(R.string.edit_job_from_place, inherited.name)
        else -> stringResource(R.string.edit_job_none)
    }
    Section(R.string.edit_section_job) {
        val title = stringResource(R.string.edit_job_row_title)
        SettingsGroup { row(title = title, subtitle = subtitle, onClick = onOpen) }
    }
}

@Composable
private fun AttributionSection(state: EditDrawerState, onOpen: () -> Unit) {
    val subtitle = state.ownJob?.name ?: stringResource(R.string.edit_attribution_none)
    Section(R.string.edit_section_attribution) {
        val title = stringResource(R.string.edit_attribution_row_title)
        SettingsGroup { row(title = title, subtitle = subtitle, onClick = onOpen) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(state: EditDrawerState, actions: EditDrawerActions, onNewTag: () -> Unit) {
    Section(R.string.edit_section_tags) {
        if (state.tagGroups.isEmpty()) {
            Text(
                stringResource(R.string.edit_tags_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = TarryTheme.colors.muted,
            )
        }
        state.tagGroups.forEach { group ->
            Text(group.name, style = MaterialTheme.typography.labelMedium, color = TarryTheme.colors.muted)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
                verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
            ) {
                group.tags.forEach { tag ->
                    TarryChip(
                        text = tag.name,
                        selected = tag.id in state.tagIds,
                        onClick = { actions.onToggleTag(tag.id) },
                        role = Role.Checkbox,
                    )
                }
            }
        }
        if (state.tagsInherited) {
            Text(
                stringResource(R.string.edit_tags_from_place),
                style = MaterialTheme.typography.bodySmall,
                color = TarryTheme.colors.muted,
            )
        }
        NewTagSurface(onClick = onNewTag)
    }
}

/** Always-visible inset surface, the same treatment as the notes field, with a centred "+" and "New tag". */
@Composable
private fun NewTagSurface(onClick: () -> Unit) {
    val label = stringResource(R.string.edit_tag_new)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TarryTheme.shapes.card)
            .background(TarryTheme.colors.inset)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = CheckRowMinHeight)
            .padding(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s, Alignment.CenterHorizontally),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_plus),
            contentDescription = null,
            tint = TarryTheme.colors.ink,
            modifier = Modifier.size(ErrorIconSize),
        )
        Text(label, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
    }
}

@Composable
private fun TogglesSection(state: EditDrawerState, actions: EditDrawerActions) {
    val billableSubtitle = when {
        !state.billableInherited -> null
        state.resolvedBillable -> stringResource(R.string.edit_billable_from_place_yes)
        else -> stringResource(R.string.edit_billable_from_place_no)
    }
    val billable = stringResource(R.string.edit_billable_title)
    val setDefault = stringResource(R.string.edit_set_default)
    val setDefaultHint = stringResource(R.string.edit_set_default_hint)
    Section(R.string.edit_section_toggles) {
        SettingsGroup {
            switchRow(billable, state.resolvedBillable, actions.onBillable, subtitle = billableSubtitle)
            if (state.kind == EditKind.VISIT && state.hasPlace) {
                switchRow(setDefault, state.isPlaceDefault, actions.onSetPlaceDefault, subtitle = setDefaultHint)
            }
        }
    }
}

@Composable
private fun NotesSection(state: EditDrawerState, actions: EditDrawerActions) {
    var notes by remember(state.id) { mutableStateOf(state.notes) }
    val saved by rememberUpdatedState(state.notes)
    val onNotes by rememberUpdatedState(actions.onNotes)
    val latest by rememberUpdatedState(notes)
    // Debounced save while typing; blur and closing the drawer flush straight away.
    LaunchedEffect(notes) {
        if (notes != saved) {
            delay(NOTES_DEBOUNCE_MS)
            onNotes(notes)
        }
    }
    DisposableEffect(state.id) {
        onDispose { if (latest != saved) onNotes(latest) }
    }
    Section(R.string.edit_section_notes) {
        InsetTextField(
            value = notes,
            onValueChange = { notes = it },
            label = stringResource(R.string.edit_notes_label),
            singleLine = false,
            minLines = 3,
            modifier = Modifier.onFocusChanged { if (!it.isFocused && latest != saved) onNotes(latest) },
        )
    }
}

@Composable
private fun TimeSection(state: EditDrawerState, actions: EditDrawerActions, onOpen: (DrawerDialog) -> Unit) {
    val formats = rememberTimeFormats()
    val zone = rememberZone(state.zoneId)
    Section(R.string.edit_section_time) {
        ErrorBanner(state.error)
        val trimStart = stringResource(R.string.edit_time_trim_start)
        val trimEnd = stringResource(R.string.edit_time_trim_end)
        val split = stringResource(R.string.edit_time_split)
        val mergePrevious = stringResource(R.string.edit_time_merge_previous)
        val mergeNext = stringResource(R.string.edit_time_merge_next)
        val changePlace = stringResource(R.string.edit_time_change_place)
        val startText = formats.time(state.start, zone)
        val endText = formats.time(state.end, zone)
        SettingsGroup {
            row(trimStart, onClick = { onOpen(DrawerDialog.TrimStart) }, value = startText)
            row(trimEnd, onClick = { onOpen(DrawerDialog.TrimEnd) }, value = endText)
            row(split, onClick = { onOpen(DrawerDialog.Split) })
            row(mergePrevious, onClick = { actions.onMerge(MergeDirection.PREVIOUS) })
            row(mergeNext, onClick = { actions.onMerge(MergeDirection.NEXT) })
            row(changePlace, onClick = { onOpen(DrawerDialog.PlacePicker) })
        }
        if (state.canReset) {
            SecondaryButton(
                stringResource(R.string.edit_reset),
                onClick = { onOpen(DrawerDialog.ConfirmReset) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DestructiveButton(stringResource(R.string.edit_delete), onClick = actions.onDelete, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Section(titleRes: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
        SectionHeader(stringResource(titleRes))
        content()
    }
}

/** Validation failures from the editor, inline and announced politely. Nothing is shown when there is no error. */
@Composable
private fun ErrorBanner(error: EditError?) {
    if (error == null) return
    val message = stringResource(
        when (error) {
            EditError.OVERLAP -> R.string.edit_error_overlap
            EditError.EMPTY_RANGE -> R.string.edit_error_empty_range
            EditError.NOT_FOUND -> R.string.edit_error_not_found
            EditError.BLANK_NAME -> R.string.edit_error_blank_name
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TarryTheme.shapes.card)
            .background(TarryTheme.colors.alertSoft)
            .padding(TarryTheme.space.m)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = TarryTheme.colors.alert,
            modifier = Modifier.size(ErrorIconSize),
        )
        Text(message, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.onAlertSoft, modifier = Modifier.weight(1f))
    }
}

// endregion

// region dialogs

@Composable
private fun DrawerDialogs(
    state: EditDrawerState,
    actions: EditDrawerActions,
    dialog: DrawerDialog?,
    onShow: (DrawerDialog) -> Unit,
    onClose: () -> Unit,
) {
    val zone = rememberZone(state.zoneId)
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val isTrip = state.kind == EditKind.TRIP
    if (dialog == DrawerDialog.JobPicker) {
        JobPickerSheet(
            title = stringResource(if (isTrip) R.string.edit_attribution_picker_title else R.string.edit_job_picker_title),
            noneLabel = stringResource(if (isTrip) R.string.edit_attribution_none else R.string.edit_job_none),
            jobs = state.jobs,
            selectedId = state.ownJob?.id,
            onSelect = {
                actions.onSetJob(it)
                onClose()
            },
            onNewJob = { onShow(DrawerDialog.NewJob) },
            onDismiss = onClose,
        )
    }
    if (dialog == DrawerDialog.NewJob) {
        NameDialog(
            title = stringResource(R.string.edit_job_new_title),
            label = stringResource(R.string.edit_job_name_label),
            onConfirm = {
                actions.onCreateJob(it)
                onClose()
            },
            onDismiss = onClose,
        )
    }
    if (dialog == DrawerDialog.NewTag) {
        NameDialog(
            title = stringResource(R.string.edit_tag_new_title),
            label = stringResource(R.string.edit_tag_name_label),
            onConfirm = {
                actions.onCreateTag(it)
                onClose()
            },
            onDismiss = onClose,
        )
    }
    if (dialog == DrawerDialog.RenamePlace) {
        NameDialog(
            title = stringResource(R.string.edit_rename_place_title),
            label = stringResource(R.string.edit_rename_place_label),
            initial = state.placeName.orEmpty(),
            allowBlank = true,
            onConfirm = {
                actions.onRenamePlace(it)
                onClose()
            },
            onDismiss = onClose,
        )
    }
    if (dialog == DrawerDialog.PlacePicker) {
        PlacePickerSheet(
            places = state.places,
            onSelect = {
                actions.onChangePlace(it)
                onClose()
            },
            onDismiss = onClose,
        )
    }
    if (dialog == DrawerDialog.ConfirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.edit_reset_confirm_title),
            body = stringResource(R.string.edit_reset_confirm_body),
            confirmLabel = stringResource(R.string.edit_reset_confirm_action),
            onConfirm = {
                actions.onReset()
                onClose()
            },
            onDismiss = onClose,
        )
    }
    val timeDialog = when (dialog) {
        DrawerDialog.TrimStart -> Triple(R.string.edit_time_picker_trim_start, state.start, actions.onTrimStart)
        DrawerDialog.TrimEnd -> Triple(R.string.edit_time_picker_trim_end, state.end, actions.onTrimEnd)
        DrawerDialog.Split -> Triple(R.string.edit_time_picker_split, state.start + (state.end - state.start) / 2, actions.onSplit)
        else -> null
    }
    if (timeDialog != null) {
        val (titleRes, anchor, apply) = timeDialog
        TimePickerDialog(
            title = stringResource(titleRes),
            initialMs = anchor,
            zone = zone,
            is24Hour = is24Hour,
            onConfirm = { hour, minute ->
                apply(instantNear(hour, minute, zone, anchor))
                onClose()
            },
            onDismiss = onClose,
        )
    }
}

// endregion
