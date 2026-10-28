package me.tarry.app.ui.shell

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.PauseKind
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.ManualOverrideEndMode
import java.time.Instant
import java.util.Date

private const val MINUTES_PER_HOUR = 60

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

/** Per-state bottom sheet opened by the toggle: container-radius top corners, pill action buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingSheet(
    state: TrackingState,
    details: TrackingDetails,
    startDurationsMin: List<Int>,
    actions: TrackingSheetActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = TarryTheme.colors.paper,
    ) {
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
            .padding(horizontal = TarryTheme.space.gutter)
            .padding(bottom = TarryTheme.space.l),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        when (state) {
            TrackingState.IDLE -> {
                SheetTitle(R.string.tracking_sheet_idle_title)
                SheetRow(stringResource(R.string.tracking_sheet_start_until_stopped), onDone, R.drawable.ic_play_arrow) {
                    actions.onStart(ManualOverrideEndMode.UNTIL_STOPPED, null)
                }
                SheetRow(stringResource(R.string.tracking_sheet_start_end_of_day), onDone, R.drawable.ic_play_arrow) {
                    actions.onStart(ManualOverrideEndMode.END_OF_DAY, null)
                }
                for (minutes in startDurationsMin) {
                    SheetRow(durationLabel(minutes), onDone, R.drawable.ic_play_arrow) {
                        actions.onStart(ManualOverrideEndMode.DURATION, minutes)
                    }
                }
            }
            TrackingState.TRACKING -> {
                SheetTitle(R.string.tracking_sheet_tracking_title)
                if (details.finishingUp) {
                    Text(
                        text = stringResource(R.string.tracking_notification_finishing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TarryTheme.colors.muted,
                    )
                }
                SheetRow(stringResource(R.string.tracking_sheet_pause_30m), onDone, R.drawable.ic_pause) { actions.onPause(PauseKind.PAUSE_30M) }
                SheetRow(stringResource(R.string.tracking_sheet_pause_1h), onDone, R.drawable.ic_pause) { actions.onPause(PauseKind.PAUSE_1H) }
                SheetRow(stringResource(R.string.tracking_sheet_pause_next_window), onDone, R.drawable.ic_pause) {
                    actions.onPause(PauseKind.UNTIL_NEXT_WINDOW)
                }
                SheetRow(stringResource(R.string.tracking_sheet_stop), onDone, R.drawable.ic_stop) { actions.onStop() }
            }
            TrackingState.PAUSED -> {
                SheetTitle(R.string.tracking_sheet_paused_title)
                Text(
                    text = pausedText(details.pausedUntil),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TarryTheme.colors.muted,
                )
                SheetRow(stringResource(R.string.tracking_sheet_resume), onDone, R.drawable.ic_play_arrow) { actions.onResume() }
            }
            TrackingState.ATTENTION -> {
                SheetTitle(R.string.tracking_sheet_attention_title)
                Text(
                    text = stringResource(details.attentionCause.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TarryTheme.colors.muted,
                )
                val fix = details.attentionCause.fix(actions)
                SheetRow(stringResource(fix.labelRes), onDone, R.drawable.ic_settings) { fix.action() }
            }
        }
    }
}

@Composable
internal fun SheetTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleLarge,
        color = TarryTheme.colors.ink,
    )
}

@Composable
internal fun SheetRow(label: String, onDone: () -> Unit, @DrawableRes icon: Int, onClick: () -> Unit) {
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TarryTheme.shapes.card)
            .clickable(role = Role.Button) {
                onClick()
                onDone()
            }
            .background(colors.card)
            .heightIn(min = 56.dp)
            .padding(horizontal = space.m, vertical = space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.m),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.inset),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.ink)
        }
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
internal fun pausedText(until: Instant?): String =
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
