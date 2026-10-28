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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.tarry.app.R
import me.tarry.app.map.GeoPoint
import me.tarry.app.map.PlacePick
import me.tarry.app.map.formatPinCoordinates
import me.tarry.app.ui.components.DestructiveButton
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.TarryChip
import me.tarry.app.ui.map.LocalMapsEnabled
import me.tarry.app.ui.map.PlaceMiniMap
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.timeline.formatDistance
import me.tarry.app.ui.timeline.formatDuration
import me.tarry.app.ui.timeline.rememberTimeFormats
import me.tarry.app.ui.timeline.rowLabelRes
import me.tarry.core.data.repository.MergeDirection
import java.time.ZoneId

private const val NOTES_DEBOUNCE_MS = 700L
private val CheckRowMinHeight = 56.dp
private val ChipMinHeight = 48.dp
private val ErrorIconSize = 24.dp

/** What a job picker (and an inline "New job" after it) is choosing for. */
private enum class JobPurpose { BLOCK_JOB, NEW_BLOCK_JOB, ADD_TO_JOB, PLACE_DEFAULT }

/** Which dialog or picker sheet is open. Kept in the content so a rotation re-asks rather than loses edits. */
private sealed interface DrawerDialog {
    data object RenamePlace : DrawerDialog
    data object PlacePicker : DrawerDialog
    data object ConfirmReset : DrawerDialog
    data object TrimStart : DrawerDialog
    data object TrimEnd : DrawerDialog
    data object Split : DrawerDialog
    data object NewStart : DrawerDialog
    data object NewEnd : DrawerDialog
    data object NewPlace : DrawerDialog
    data object PickOnMap : DrawerDialog
    data class JobPicker(val purpose: JobPurpose) : DrawerDialog
    data class NewJobName(val purpose: JobPurpose) : DrawerDialog
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
) {
    val space = TarryTheme.space
    var dialog by remember { mutableStateOf<DrawerDialog?>(null) }
    // New-visit form values; they survive rotation and are unused by the other kinds.
    var newStart by rememberSaveable(state.start) { mutableLongStateOf(state.start) }
    var newEnd by rememberSaveable(state.end) { mutableLongStateOf(state.end) }
    // Keyed like the times, so a pick never carries over to a later new visit in the same composition.
    var newPlaceId by rememberSaveable(state.start, state.end) { mutableStateOf<String?>(null) }
    // A map pin chosen as a NEW place ([lat, lng]); exclusive with newPlaceId. ArrayList so it survives rotation.
    var newPin by rememberSaveable(state.start, state.end) { mutableStateOf<ArrayList<Double>?>(null) }
    // New-job form values. The billable flag is null until the user touches the switch, so it follows the chosen job.
    var newJobId by rememberSaveable(state.start, state.end, state.jobId) { mutableStateOf(state.jobId) }
    var newBillable by rememberSaveable(state.start, state.end, state.jobId) { mutableStateOf<Boolean?>(null) }
    var newNotes by rememberSaveable(state.start, state.end, state.jobId) { mutableStateOf("") }
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
                pin = newPin?.let { GeoPoint(it[0], it[1]) },
                onOpen = { dialog = it },
                onSave = {
                    val pin = newPin
                    val id = newPlaceId
                    if (pin != null) actions.onCreateManualAtNewPlace(pin[0], pin[1], newStart, newEnd)
                    else if (id != null) actions.onCreateManual(id, newStart, newEnd)
                },
            )
            EditKind.TRIP -> {
                ErrorBanner(state.error)
                SegmentJobSection(state, actions, onOpen = { dialog = it })
                NotesSection(state, actions)
                val addStop = stringResource(R.string.edit_trip_add_stop)
                SettingsGroup { row(title = addStop, onClick = actions.onAddStop) }
            }
            EditKind.VISIT -> {
                SegmentJobSection(state, actions, onOpen = { dialog = it })
                NotesSection(state, actions)
                TimeSection(state, actions, onOpen = { dialog = it })
            }
            EditKind.JOB -> {
                ErrorBanner(state.error)
                JobPickerSection(state, state.jobId, JobPurpose.BLOCK_JOB, onOpen = { dialog = it })
                WhenSection(state, state.start, state.end, onOpen = { dialog = it })
                BillableSection(state.billable, actions.onBlockBillable)
                NotesSection(state, actions)
                CoversLine(state)
                DestructiveButton(stringResource(R.string.edit_job_delete), onClick = actions.onDeleteBlock, modifier = Modifier.fillMaxWidth())
            }
            EditKind.NEW_JOB -> {
                val chosen = state.jobs.firstOrNull { it.id == newJobId }
                val billable = newBillable ?: chosen?.defaultBillable ?: true
                val validRange = newEnd > newStart
                ErrorBanner(state.error)
                JobPickerSection(state, newJobId, JobPurpose.NEW_BLOCK_JOB, onOpen = { dialog = it })
                WhenSection(state, newStart, newEnd, onOpen = { dialog = it })
                if (!validRange) {
                    Text(
                        stringResource(R.string.edit_job_end_before_start),
                        style = MaterialTheme.typography.bodySmall,
                        color = TarryTheme.colors.alert,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                BillableSection(billable) { newBillable = it }
                Section(R.string.edit_section_notes) {
                    InsetTextField(
                        value = newNotes,
                        onValueChange = { newNotes = it },
                        label = stringResource(R.string.edit_notes_label),
                        singleLine = false,
                        minLines = 3,
                    )
                }
                PrimaryButton(
                    stringResource(R.string.edit_job_save),
                    onClick = { newJobId?.let { actions.onCreateBlock(it, newStart, newEnd, newBillable, newNotes) } },
                    enabled = newJobId != null && validRange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    DrawerDialogs(state, actions, dialog, onClose = { dialog = null })
    val zone = rememberZone(state.zoneId)
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    if (dialog == DrawerDialog.NewPlace) {
        PlacePickerSheet(
            places = state.places,
            onSelect = {
                newPlaceId = it
                newPin = null
                dialog = null
            },
            onDismiss = { dialog = null },
            title = stringResource(R.string.edit_new_picker_title),
        )
    }
    if (dialog == DrawerDialog.PickOnMap) {
        val pin = newPin
        PinPickerDialog(
            places = state.places,
            chosenCentre = pin?.let { GeoPoint(it[0], it[1]) } ?: state.places.firstOrNull { it.id == newPlaceId }?.centre,
            onPick = { pick ->
                when (pick) {
                    is PlacePick.Known -> {
                        newPlaceId = pick.placeId
                        newPin = null
                    }
                    is PlacePick.New -> {
                        newPlaceId = null
                        newPin = arrayListOf(pick.lat, pick.lng)
                    }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
    if (dialog == DrawerDialog.NewStart || dialog == DrawerDialog.NewEnd) {
        val isStart = dialog == DrawerDialog.NewStart
        val anchor = if (state.kind == EditKind.JOB) { if (isStart) state.start else state.end } else if (isStart) newStart else newEnd
        TimePickerDialog(
            title = stringResource(if (isStart) R.string.edit_time_picker_trim_start else R.string.edit_time_picker_trim_end),
            initialMs = anchor,
            zone = zone,
            is24Hour = is24Hour,
            onConfirm = { hour, minute ->
                val picked = instantNear(hour, minute, zone, anchor)
                if (state.kind == EditKind.JOB) {
                    if (isStart) actions.onBlockStart(picked) else actions.onBlockEnd(picked)
                } else if (isStart) newStart = picked else newEnd = picked
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
    val jobPurpose = (dialog as? DrawerDialog.JobPicker)?.purpose
    val chooseJob: (JobPurpose, String?) -> Unit = { purpose, id ->
        when (purpose) {
            JobPurpose.BLOCK_JOB -> id?.let(actions.onSetBlockJob)
            JobPurpose.NEW_BLOCK_JOB -> newJobId = id
            JobPurpose.ADD_TO_JOB -> id?.let(actions.onAddToJob)
            JobPurpose.PLACE_DEFAULT -> actions.onSetPlaceDefaultJob(id)
        }
    }
    if (jobPurpose != null) {
        JobPickerSheet(
            title = stringResource(
                if (jobPurpose == JobPurpose.PLACE_DEFAULT) R.string.edit_place_default_job_picker else R.string.edit_job_picker_title,
            ),
            noneLabel = if (jobPurpose == JobPurpose.PLACE_DEFAULT) stringResource(R.string.edit_place_default_job_none) else null,
            jobs = state.jobs,
            selectedId = when (jobPurpose) {
                JobPurpose.BLOCK_JOB -> state.jobId
                JobPurpose.NEW_BLOCK_JOB -> newJobId
                JobPurpose.PLACE_DEFAULT -> state.placeDefaultJob?.id
                JobPurpose.ADD_TO_JOB -> null
            },
            onSelect = {
                chooseJob(jobPurpose, it)
                dialog = null
            },
            onNewJob = { dialog = DrawerDialog.NewJobName(jobPurpose) },
            onDismiss = { dialog = null },
        )
    }
    (dialog as? DrawerDialog.NewJobName)?.let { naming ->
        NameDialog(
            title = stringResource(R.string.edit_job_new_title),
            label = stringResource(R.string.edit_job_name_label),
            onConfirm = { name ->
                actions.onCreateJob(name) { chooseJob(naming.purpose, it) }
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
    pin: GeoPoint?,
    onOpen: (DrawerDialog) -> Unit,
    onSave: () -> Unit,
) {
    val placeLabel = if (pin != null) {
        stringResource(R.string.edit_new_place_at, formatPinCoordinates(pin.lat, pin.lng))
    } else {
        placeId?.let { id -> state.places.firstOrNull { it.id == id }?.label }
    }
    val pickOnMap = stringResource(R.string.edit_new_pick_on_map)
    val choose = stringResource(R.string.edit_new_choose_place)
    ErrorBanner(state.error)
    WhenSection(state, start, end, onOpen)
    Section(R.string.edit_new_section_place) {
        SettingsGroup {
            row(choose, onClick = { onOpen(DrawerDialog.NewPlace) }, value = placeLabel)
            row(pickOnMap, onClick = { onOpen(DrawerDialog.PickOnMap) }, icon = R.drawable.ic_location_pin)
        }
        if (placeId == null && pin == null) {
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
        enabled = placeId != null || pin != null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Start and end rows that open the time picker; shared by the new-visit and the job block forms. */
@Composable
private fun WhenSection(state: EditDrawerState, start: Long, end: Long, onOpen: (DrawerDialog) -> Unit) {
    val formats = rememberTimeFormats()
    val zone = rememberZone(state.zoneId)
    val startTitle = stringResource(R.string.edit_new_start)
    val endTitle = stringResource(R.string.edit_new_end)
    Section(R.string.edit_new_section_when) {
        SettingsGroup {
            row(startTitle, onClick = { onOpen(DrawerDialog.NewStart) }, value = formats.time(start, zone))
            row(endTitle, onClick = { onOpen(DrawerDialog.NewEnd) }, value = formats.time(end, zone))
        }
    }
}

// region job blocks

/** The block's job as a row that opens the picker; a new block must have one (hint shown until it does). */
@Composable
private fun JobPickerSection(state: EditDrawerState, jobId: String?, purpose: JobPurpose, onOpen: (DrawerDialog) -> Unit) {
    val job = state.jobs.firstOrNull { it.id == jobId }
    val title = stringResource(R.string.edit_job_row_title)
    val choose = stringResource(R.string.edit_job_choose)
    Section(R.string.edit_section_job) {
        SettingsGroup { row(title = title, onClick = { onOpen(DrawerDialog.JobPicker(purpose)) }, value = job?.name ?: choose) }
        if (jobId == null) {
            Text(
                stringResource(R.string.edit_job_required),
                style = MaterialTheme.typography.bodySmall,
                color = TarryTheme.colors.muted,
            )
        }
    }
}

@Composable
private fun BillableSection(billable: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(R.string.edit_job_billable)
    SettingsGroup { switchRow(title, billable, onChange) }
}

/** "Covers 3 visits · 2 trips", read-only. */
@Composable
private fun CoversLine(state: EditDrawerState) {
    val visits = pluralStringResource(R.plurals.edit_job_covers_visits, state.coveredVisits, state.coveredVisits)
    val trips = pluralStringResource(R.plurals.edit_job_covers_trips, state.coveredTrips, state.coveredTrips)
    Text(
        stringResource(R.string.edit_job_covers, visits, trips),
        style = MaterialTheme.typography.bodyMedium,
        color = TarryTheme.colors.muted,
        modifier = Modifier.testTag("drawer_job_covers"),
    )
}

/**
 * The visit or trip's Job section: chips for the blocks overlapping it (tap opens the block), "Add to job", and for a
 * visit at a live, non-ignored place the place's default job.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentJobSection(state: EditDrawerState, actions: EditDrawerActions, onOpen: (DrawerDialog) -> Unit) {
    val formats = rememberTimeFormats()
    val zone = rememberZone(state.zoneId)
    val addToJob = stringResource(R.string.edit_segment_add_to_job)
    Section(R.string.edit_section_job) {
        if (state.blocks.isEmpty()) {
            Text(
                stringResource(R.string.edit_segment_no_jobs),
                style = MaterialTheme.typography.bodyMedium,
                color = TarryTheme.colors.muted,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
                verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
            ) {
                state.blocks.forEach { chip ->
                    val range = stringResource(R.string.timeline_time_range, formats.time(chip.start, zone), formats.time(chip.end, zone))
                    BlockChipView(chip, stringResource(R.string.edit_segment_block_chip, chip.job.name, range)) { actions.onOpenBlock(chip.id) }
                }
            }
        }
        SettingsGroup { row(addToJob, onClick = { onOpen(DrawerDialog.JobPicker(JobPurpose.ADD_TO_JOB)) }) }
        if (state.kind == EditKind.VISIT && state.hasPlace && !state.placeIgnored) {
            val rowTitle = stringResource(R.string.edit_place_default_job_row, state.placeLabel ?: stringResource(R.string.timeline_unknown_place))
            val none = stringResource(R.string.edit_place_default_job_none)
            SettingsGroup {
                row(
                    rowTitle,
                    onClick = { onOpen(DrawerDialog.JobPicker(JobPurpose.PLACE_DEFAULT)) },
                    value = state.placeDefaultJob?.name ?: none,
                )
            }
            Text(
                stringResource(R.string.place_defaults_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TarryTheme.colors.muted,
            )
        }
    }
}

/** A job colour swatch, the job name and the block's range on a pill; a 48 dp tap target that opens the block. */
@Composable
private fun BlockChipView(chip: BlockChip, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .testTag("drawer_block_${chip.id}")
            .heightIn(min = ChipMinHeight)
            .clip(TarryTheme.shapes.pill)
            .background(TarryTheme.colors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        JobSwatch(chip.job)
        Text(label, style = MaterialTheme.typography.labelLarge, color = TarryTheme.colors.ink)
    }
}

// endregion

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
        EditKind.JOB -> state.jobs.firstOrNull { it.id == state.jobId }?.name ?: stringResource(R.string.edit_job_drawer_title)
        EditKind.NEW_JOB -> stringResource(R.string.edit_job_drawer_title_new)
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
        if (state.kind != EditKind.NEW_JOB) Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        val centre = state.placeCentre
        val placeId = state.placeId
        if (state.kind == EditKind.VISIT && centre != null && placeId != null && LocalMapsEnabled.current) {
            PlaceMiniMap(
                placeId = placeId,
                placeLabel = state.placeLabel ?: stringResource(R.string.timeline_unknown_place),
                centre = centre,
                radiusM = state.placeRadiusM,
                modifier = Modifier.padding(top = TarryTheme.space.s),
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
            EditError.JOB_REQUIRED -> R.string.edit_error_job_required
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
    onClose: () -> Unit,
) {
    val zone = rememberZone(state.zoneId)
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
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
