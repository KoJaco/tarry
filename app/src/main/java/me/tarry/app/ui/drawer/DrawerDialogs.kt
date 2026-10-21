package me.tarry.app.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.tarry.app.R
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import java.time.Instant
import java.time.ZoneId

private val PickerRowMinHeight = 56.dp
private val SwatchSize = 24.dp
private val SwatchBorder = 1.5.dp

@Composable
private fun sheetShape() = TarryTheme.shapes.container.copy(
    bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
    bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
)

/** A job's palette slot as a small tinted square with its border. Decorative: the job name is always written beside it. */
@Composable
internal fun JobSwatch(job: JobOption, modifier: Modifier = Modifier) {
    val colors = JobPalette.lookup(job.colorArgb, job.id).colors(TarryTheme.isDark)
    Box(
        modifier = modifier
            .size(SwatchSize)
            .clip(TarryTheme.shapes.small)
            .background(colors.container)
            .border(SwatchBorder, colors.border, TarryTheme.shapes.small),
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = TarryTheme.colors.ink,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * The job list as a bottom sheet of selectable rows with colour swatches. [noneLabel] is the "no job" row ("No job" for
 * a visit, "Unattributed" for a trip). [onNewJob] adds the "New job…" action when set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobPickerSheet(
    title: String,
    noneLabel: String,
    jobs: List<JobOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onNewJob: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = sheetShape(), containerColor = TarryTheme.colors.paper) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = TarryTheme.space.gutter)
                .padding(bottom = TarryTheme.space.l),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
        ) {
            SheetTitle(title)
            TarryCard(contentPadding = PaddingValues(0.dp), modifier = Modifier.selectableGroup()) {
                PickerRow(label = noneLabel, selected = selectedId == null, swatch = null, onClick = { onSelect(null) })
                jobs.forEach { job ->
                    HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
                    PickerRow(label = job.name, selected = job.id == selectedId, swatch = job, onClick = { onSelect(job.id) })
                }
            }
            if (onNewJob != null) {
                SecondaryButton(stringResource(R.string.edit_job_new), onClick = onNewJob, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, selected: Boolean, swatch: JobOption?, onClick: () -> Unit, detail: String? = null) {
    val space = TarryTheme.space
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = PickerRowMinHeight)
            .padding(horizontal = space.l, vertical = space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.m),
    ) {
        if (swatch != null) JobSwatch(swatch)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = TarryTheme.colors.ink,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Known places with a search field (name or address), for "Change place". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacePickerSheet(
    places: List<PlaceOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(places, query) {
        val q = query.trim()
        if (q.isEmpty()) places else places.filter { it.label.contains(q, ignoreCase = true) || it.detail?.contains(q, ignoreCase = true) == true }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = sheetShape(), containerColor = TarryTheme.colors.paper) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = TarryTheme.space.gutter)
                .padding(bottom = TarryTheme.space.l),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
        ) {
            SheetTitle(stringResource(R.string.edit_place_picker_title))
            InsetTextField(query, { query = it }, stringResource(R.string.edit_place_search_label))
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.edit_place_none_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TarryTheme.colors.muted,
                )
            } else {
                TarryCard(
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    shown.forEachIndexed { index, place ->
                        if (index > 0) HorizontalDivider(thickness = 1.dp, color = TarryTheme.colors.divider)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onSelect(place.id) }
                                .heightIn(min = PickerRowMinHeight)
                                .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(place.label, style = MaterialTheme.typography.titleSmall, color = TarryTheme.colors.ink)
                                if (place.detail != null && place.detail != place.label) {
                                    Text(place.detail, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A one-field dialog for "New job…" and "New tag". Save is disabled while the name is blank. */
@Composable
internal fun NameDialog(title: String, label: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container,
        containerColor = TarryTheme.colors.card,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = TarryTheme.colors.ink) },
        text = { InsetTextField(name, { name = it }, label) },
        confirmButton = {
            PrimaryButton(stringResource(R.string.edit_dialog_save), onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank())
        },
        dismissButton = { TertiaryButton(stringResource(R.string.edit_dialog_cancel), onClick = onDismiss) },
    )
}

@Composable
internal fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container,
        containerColor = TarryTheme.colors.card,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = TarryTheme.colors.ink) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted) },
        confirmButton = { PrimaryButton(confirmLabel, onClick = onConfirm) },
        dismissButton = { TertiaryButton(stringResource(R.string.edit_dialog_cancel), onClick = onDismiss) },
    )
}

/**
 * M3 [TimePicker] in a dialog, in the user's 12/24 hour setting ([is24Hour]). Starts on [initialMs] in [zone]; confirms
 * with the picked hour and minute (the caller picks the date).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(
    title: String,
    initialMs: Long,
    zone: ZoneId,
    is24Hour: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = Instant.ofEpochMilli(initialMs).atZone(zone)
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = TarryTheme.shapes.container,
            color = TarryTheme.colors.card,
            modifier = Modifier.width(IntrinsicSize.Min),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(TarryTheme.space.xxl),
                verticalArrangement = Arrangement.spacedBy(TarryTheme.space.l),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TarryTheme.colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s, Alignment.End),
                ) {
                    TertiaryButton(stringResource(R.string.edit_time_cancel), onClick = onDismiss)
                    PrimaryButton(stringResource(R.string.edit_time_ok), onClick = { onConfirm(state.hour, state.minute) })
                }
            }
        }
    }
}
