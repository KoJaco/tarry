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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
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
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.TarryTheme
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
    segmentation: SegmentationUi = SegmentationUi(),
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val space = TarryTheme.space
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
                Text(
                    stringResource(R.string.sessions_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TarryTheme.colors.ink,
                )
                Text(
                    stringResource(R.string.sessions_export_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = TarryTheme.colors.muted,
                )
                ExportResultText(exportResult, actions.onDismissExportResult)
                SecondaryButton(
                    text = stringResource(
                        if (segmentation.running) R.string.sessions_segmentation_running else R.string.sessions_run_segmentation,
                    ),
                    onClick = actions.onRunSegmentation,
                    enabled = !segmentation.running,
                    modifier = Modifier.testTag("sessions_run_segmentation"),
                )
                SegmentationStatusText(segmentation)
                if (showTestWindow) {
                    Text(
                        stringResource(R.string.sessions_test_window_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TarryTheme.colors.muted,
                    )
                    SecondaryButton(
                        text = stringResource(R.string.sessions_test_window),
                        onClick = actions.onStartTestWindow,
                        modifier = Modifier.testTag("sessions_test_window"),
                    )
                }
                RangeExport(today, actions.onExport)
                if (sessions.isEmpty()) {
                    Text(
                        stringResource(R.string.sessions_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TarryTheme.colors.ink,
                    )
                }
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
            color = if (result == ExportResult.Failed) TarryTheme.colors.alert else TarryTheme.colors.ink,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("sessions_export_result"),
        )
        TertiaryButton(stringResource(R.string.sessions_date_picker_ok), onDismiss)
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
    TarryCard(modifier = Modifier.fillMaxWidth().testTag("session_row_${session.id}")) {
        Text(
            stringResource(R.string.sessions_row_title, start, end),
            style = MaterialTheme.typography.titleSmall,
            color = TarryTheme.colors.ink,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        Text(
            pluralStringResource(R.plurals.sessions_row_pings, session.pingCount, session.pingCount),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
            modifier = Modifier.testTag("session_pings_${session.id}"),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
            modifier = Modifier.padding(top = TarryTheme.space.s),
        ) {
            val gpx = stringResource(R.string.sessions_export_gpx_description, session.id)
            val csv = stringResource(R.string.sessions_export_csv_description, session.id)
            SecondaryButton(
                text = stringResource(R.string.sessions_export_gpx),
                onClick = { onExport(ExportRequest.Session(session.id, ExportFormat.GPX)) },
                modifier = Modifier
                    .semantics { contentDescription = gpx }
                    .testTag("session_export_gpx_${session.id}"),
            )
            SecondaryButton(
                text = stringResource(R.string.sessions_export_csv),
                onClick = { onExport(ExportRequest.Session(session.id, ExportFormat.CSV)) },
                modifier = Modifier
                    .semantics { contentDescription = csv }
                    .testTag("session_export_csv_${session.id}"),
            )
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
        color = TarryTheme.colors.ink,
        modifier = Modifier.padding(top = TarryTheme.space.l),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
        val fromText = from.format(dateFormat)
        val toText = to.format(dateFormat)
        val fromDescription = stringResource(R.string.sessions_export_range_from_description, fromText)
        val toDescription = stringResource(R.string.sessions_export_range_to_description, toText)
        SecondaryButton(
            text = stringResource(R.string.sessions_export_range_from, fromText),
            onClick = { picking = PICK_FROM },
            modifier = Modifier
                .semantics { contentDescription = fromDescription }
                .testTag("range_from"),
        )
        SecondaryButton(
            text = stringResource(R.string.sessions_export_range_to, toText),
            onClick = { picking = PICK_TO },
            modifier = Modifier
                .semantics { contentDescription = toDescription }
                .testTag("range_to"),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
        for (format in ExportFormat.entries) {
            SecondaryButton(
                text = stringResource(
                    if (format == ExportFormat.GPX) R.string.sessions_export_range_gpx else R.string.sessions_export_range_csv,
                ),
                onClick = {
                    val first = minOf(from, to)
                    val last = maxOf(from, to)
                    onExport(ExportRequest.Range(first, last, ZoneId.systemDefault(), format))
                },
                modifier = Modifier.testTag("range_export_${format.name}"),
            )
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

private val StatusTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** The last segmentation outcome: the error if the last attempt gave up (rule 6), else the last success time. */
@Composable
private fun SegmentationStatusText(state: SegmentationUi) {
    val error = state.error
    val text: String
    val color: Color
    when {
        error != null -> {
            text = stringResource(
                R.string.sessions_segmentation_failed,
                StatusTimeFormat.format(Instant.ofEpochMilli(error.timeMs).atZone(ZoneId.systemDefault())),
                error.message,
            )
            color = TarryTheme.colors.alert
        }
        state.lastSuccessMs != null -> {
            text = stringResource(
                R.string.sessions_segmentation_last_success,
                StatusTimeFormat.format(Instant.ofEpochMilli(state.lastSuccessMs).atZone(ZoneId.systemDefault())),
            )
            color = TarryTheme.colors.muted
        }
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("sessions_segmentation_status"),
    )
}
