package me.tarry.app.ui.shell

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.PauseKind
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.core.data.model.ManualOverrideEndMode
import java.time.Instant
import java.util.Date

private const val MINUTES_PER_HOUR = 60
private val SheetRowMinHeight = 48.dp

/** What the sheet can ask for. The shell wires these to the controller and to system settings. */
data class TrackingSheetActions(
    val onStart: (ManualOverrideEndMode, Int?) -> Unit,
    val onPause: (PauseKind) -> Unit,
    val onResume: () -> Unit,
    val onStop: () -> Unit,
    val onOpenLocationSettings: () -> Unit,
    /** Permission causes: the shell navigates to the permissions screen. */
    val onOpenPermissionsScreen: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/** Minimal per-state bottom sheet opened by the toggle. Phase 6 polishes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingSheet(
    state: TrackingState,
    details: TrackingDetails,
    startDurationsMin: List<Int>,
    actions: TrackingSheetActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TrackingSheetContent(state, details, startDurationsMin, actions, onDismiss)
    }
}

/** Sheet body without the sheet chrome, so it can be tested directly. Every action also calls [onDone]. */
@Composable
fun TrackingSheetContent(
    state: TrackingState,
    details: TrackingDetails,
    startDurationsMin: List<Int>,
    actions: TrackingSheetActions,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state) {
            TrackingState.IDLE -> {
                SheetTitle(R.string.tracking_sheet_idle_title)
                SheetRow(stringResource(R.string.tracking_sheet_start_until_stopped), onDone) {
                    actions.onStart(ManualOverrideEndMode.UNTIL_STOPPED, null)
                }
                SheetRow(stringResource(R.string.tracking_sheet_start_end_of_day), onDone) {
                    actions.onStart(ManualOverrideEndMode.END_OF_DAY, null)
                }
                for (minutes in startDurationsMin) {
                    SheetRow(durationLabel(minutes), onDone) {
                        actions.onStart(ManualOverrideEndMode.DURATION, minutes)
                    }
                }
            }
            TrackingState.TRACKING -> {
                SheetTitle(R.string.tracking_sheet_tracking_title)
                SheetRow(stringResource(R.string.tracking_sheet_pause_30m), onDone) { actions.onPause(PauseKind.PAUSE_30M) }
                SheetRow(stringResource(R.string.tracking_sheet_pause_1h), onDone) { actions.onPause(PauseKind.PAUSE_1H) }
                SheetRow(stringResource(R.string.tracking_sheet_pause_next_window), onDone) {
                    actions.onPause(PauseKind.UNTIL_NEXT_WINDOW)
                }
                SheetRow(stringResource(R.string.tracking_sheet_stop), onDone) { actions.onStop() }
            }
            TrackingState.PAUSED -> {
                SheetTitle(R.string.tracking_sheet_paused_title)
                Text(
                    text = pausedText(details.pausedUntil),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                SheetRow(stringResource(R.string.tracking_sheet_resume), onDone) { actions.onResume() }
            }
            TrackingState.ATTENTION -> {
                SheetTitle(R.string.tracking_sheet_attention_title)
                Text(
                    text = stringResource(details.attentionCause.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                val fix = details.attentionCause.fix(actions)
                SheetRow(stringResource(fix.labelRes), onDone) { fix.action() }
            }
        }
    }
}

@Composable
private fun SheetTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun SheetRow(label: String, onDone: () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SheetRowMinHeight)
            .clickable(role = Role.Button) {
                onClick()
                onDone()
            }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun durationLabel(minutes: Int): String =
    if (minutes % MINUTES_PER_HOUR == 0) {
        val hours = minutes / MINUTES_PER_HOUR
        pluralStringResource(R.plurals.tracking_sheet_start_hours, hours, hours)
    } else {
        stringResource(R.string.tracking_sheet_start_minutes, minutes)
    }

@Composable
private fun pausedText(until: Instant?): String =
    if (until == null) {
        stringResource(R.string.tracking_sheet_paused_open_ended)
    } else {
        val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(until.toEpochMilli()))
        stringResource(R.string.tracking_sheet_paused_until, time)
    }

private fun AttentionCause?.messageRes(): Int = when (this) {
    AttentionCause.NO_FINE_LOCATION -> R.string.tracking_attention_no_fine_location
    AttentionCause.NO_BACKGROUND_LOCATION -> R.string.tracking_attention_no_background_location
    AttentionCause.LOCATION_OFF -> R.string.tracking_attention_location_off
    AttentionCause.SERVICE_NOT_RUNNING -> R.string.tracking_attention_service_not_running
    AttentionCause.NO_FIX_WHILE_MOVING -> R.string.tracking_attention_no_fix_while_moving
    AttentionCause.EXACT_ALARM_DENIED -> R.string.tracking_attention_exact_alarm_denied
    null -> R.string.tracking_attention_unknown
}

private class AttentionFix(val labelRes: Int, val action: () -> Unit)

private fun AttentionCause?.fix(actions: TrackingSheetActions): AttentionFix = when (this) {
    AttentionCause.LOCATION_OFF ->
        AttentionFix(R.string.tracking_sheet_fix_open_location_settings, actions.onOpenLocationSettings)
    AttentionCause.NO_FINE_LOCATION, AttentionCause.NO_BACKGROUND_LOCATION, AttentionCause.EXACT_ALARM_DENIED ->
        AttentionFix(R.string.tracking_sheet_fix_open_permissions, actions.onOpenPermissionsScreen)
    AttentionCause.SERVICE_NOT_RUNNING, AttentionCause.NO_FIX_WHILE_MOVING, null ->
        AttentionFix(R.string.tracking_sheet_fix_open_app_settings, actions.onOpenAppSettings)
}
