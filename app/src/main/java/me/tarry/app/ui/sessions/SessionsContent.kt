package me.tarry.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.export.ExportFormat
import me.tarry.app.export.ExportRequest
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the sessions screen can ask for. The stateful wrapper opens the file chooser and calls the ViewModel. */
data class SessionsActions(
    val onExport: (ExportRequest) -> Unit,
    val onStartTestWindow: () -> Unit,
    val onDismissExportResult: () -> Unit,
    val onRunSegmentation: () -> Unit = {},
)

private val MinTouchTarget = 48.dp

/** Stateless sessions list, test-window button (debug only) and export controls. */
@Composable
fun SessionsContent(
    sessions: List<SessionListItem>,
    showTestWindow: Boolean,
    exportResult: ExportResult?,
    actions: SessionsActions,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(stringResource(R.string.sessions_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.sessions_export_privacy),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            ExportResultText(exportResult, actions.onDismissExportResult)
            OutlinedButton(
                onClick = actions.onRunSegmentation,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag("sessions_run_segmentation"),
            ) {
                Text(stringResource(R.string.sessions_run_segmentation))
            }
            if (showTestWindow) {
                Text(stringResource(R.string.sessions_test_window_hint), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = actions.onStartTestWindow,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag("sessions_test_window"),
                ) {
                    Text(stringResource(R.string.sessions_test_window))
                }
            }
            RangeExport(today, actions.onExport)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.sessions_empty), style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(sessions, key = { it.id }) { session -> SessionRow(session, actions.onExport) }
    }
}

@Composable
private fun ExportResultText(result: ExportResult?, onDismiss: () -> Unit) {
    val text = when (result) {
        null -> return
        is ExportResult.Done -> pluralStringResource(R.plurals.sessions_export_done, result.pingCount, result.pingCount)
        ExportResult.Failed -> stringResource(R.string.sessions_export_failed)
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("sessions_export_result"),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.sessions_date_picker_ok))
        }
    }
}

@Composable
private fun SessionRow(session: SessionListItem, onExport: (ExportRequest) -> Unit) {
    val start = formatSessionInstant(session.start, session.zoneId)
    val end = session.end?.let { formatSessionInstant(it, session.zoneId) } ?: stringResource(R.string.sessions_row_open)
    val detail = stringResource(
        R.string.sessions_row_detail,
        stringResource(session.trigger.labelRes()),
        stringResource(session.endReason.labelRes()),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("session_row_${session.id}")) {
        Text(stringResource(R.string.sessions_row_title, start, end), style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        Text(
            pluralStringResource(R.plurals.sessions_row_pings, session.pingCount, session.pingCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("session_pings_${session.id}"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val gpx = stringResource(R.string.sessions_export_gpx_description, session.id)
            val csv = stringResource(R.string.sessions_export_csv_description, session.id)
            OutlinedButton(
                onClick = { onExport(ExportRequest.Session(session.id, ExportFormat.GPX)) },
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = gpx }
                    .testTag("session_export_gpx_${session.id}"),
            ) { Text(stringResource(R.string.sessions_export_gpx)) }
            OutlinedButton(
                onClick = { onExport(ExportRequest.Session(session.id, ExportFormat.CSV)) },
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .semantics { contentDescription = csv }
                    .testTag("session_export_csv_${session.id}"),
            ) { Text(stringResource(R.string.sessions_export_csv)) }
        }
    }
}

/** Export of every ping between two days (inclusive), in the phone's current time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeExport(today: LocalDate, onExport: (ExportRequest) -> Unit) {
    var fromDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var toDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val from = LocalDate.ofEpochDay(fromDay)
    val to = LocalDate.ofEpochDay(toDay)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    Text(
        stringResource(R.string.sessions_export_range_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val fromText = from.format(dateFormat)
        val toText = to.format(dateFormat)
        val fromDescription = stringResource(R.string.sessions_export_range_from_description, fromText)
        val toDescription = stringResource(R.string.sessions_export_range_to_description, toText)
        OutlinedButton(
            onClick = { picking = PICK_FROM },
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .semantics { contentDescription = fromDescription }
                .testTag("range_from"),
        ) { Text(stringResource(R.string.sessions_export_range_from, fromText)) }
        OutlinedButton(
            onClick = { picking = PICK_TO },
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .semantics { contentDescription = toDescription }
                .testTag("range_to"),
        ) { Text(stringResource(R.string.sessions_export_range_to, toText)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (format in ExportFormat.entries) {
            Button(
                onClick = {
                    val first = minOf(from, to)
                    val last = maxOf(from, to)
                    onExport(ExportRequest.Range(first, last, ZoneId.systemDefault(), format))
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag("range_export_${format.name}"),
            ) {
                Text(
                    stringResource(
                        if (format == ExportFormat.GPX) R.string.sessions_export_range_gpx else R.string.sessions_export_range_csv,
                    ),
                )
            }
        }
    }

    picking?.let { which ->
        val initial = if (which == PICK_FROM) from else to
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            if (which == PICK_FROM) fromDay = picked else toDay = picked
                        }
                        picking = null
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text(stringResource(R.string.sessions_date_picker_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.sessions_date_picker_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private const val PICK_FROM = "from"
private const val PICK_TO = "to"

/** Formats [epochMs] in the zone the session was captured in; falls back to the phone's zone for a bad id. */
fun formatSessionInstant(epochMs: Long, zoneId: String): String {
    val zone = try {
        ZoneId.of(zoneId)
    } catch (e: DateTimeException) {
        ZoneId.systemDefault()
    }
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

private fun SessionTrigger.labelRes(): Int = when (this) {
    SessionTrigger.SCHEDULE -> R.string.sessions_trigger_schedule
    SessionTrigger.MANUAL -> R.string.sessions_trigger_manual
}

private fun SessionEndReason?.labelRes(): Int = when (this) {
    null -> R.string.sessions_end_reason_none
    SessionEndReason.SCHEDULE_END -> R.string.sessions_end_schedule_end
    SessionEndReason.USER_STOP -> R.string.sessions_end_user_stop
    SessionEndReason.USER_PAUSE -> R.string.sessions_end_user_pause
    SessionEndReason.KILLED -> R.string.sessions_end_killed
    SessionEndReason.PERMISSION_LOST -> R.string.sessions_end_permission_lost
}
