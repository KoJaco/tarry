package me.tarry.app.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R

/** What the permission screen can ask for. The stateful wrapper wires these to launchers and the ViewModel. */
data class PermissionsActions(
    val onGrant: (PermissionStep) -> Unit,
    val onSkip: (PermissionStep) -> Unit,
    val onConfirmBackgroundDisclosure: () -> Unit,
    val onDismissBackgroundDisclosure: () -> Unit,
    val onDone: () -> Unit,
)

private val MinTouchTarget = 48.dp

/** Stateless permission list; renders every [StepStatus] so it can be Compose-tested without Hilt. */
@Composable
fun PermissionsContent(
    state: PermissionsUiState,
    actions: PermissionsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.permissions_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        state.items.forEach { item -> PermissionRow(item, actions) }
        if (state.missingRequired.isNotEmpty()) {
            Text(
                stringResource(R.string.permissions_missing_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp).testTag("permissions_missing_required"),
            )
        }
        Button(
            onClick = actions.onDone,
            modifier = Modifier.padding(top = 8.dp).heightIn(min = MinTouchTarget).testTag("permissions_done"),
        ) {
            Text(stringResource(R.string.permissions_action_done))
        }
    }
    if (state.showBackgroundDisclosure) {
        BackgroundDisclosureDialog(actions.onConfirmBackgroundDisclosure, actions.onDismissBackgroundDisclosure)
    }
}

@Composable
private fun PermissionRow(item: PermissionItem, actions: PermissionsActions) {
    val step = item.step
    val title = stringResource(step.titleRes())
    val statusText = stringResource(item.status.labelRes())
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("permission_row_${step.name}")) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(step.bodyRes()), style = MaterialTheme.typography.bodyMedium)
        if (step.requiredForScheduled) {
            Text(
                stringResource(R.string.permissions_required_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val statusDescription = stringResource(R.string.permissions_status_description, title, statusText)
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelLarge,
            color = if (item.status == StepStatus.GRANTED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics { contentDescription = statusDescription }
                .testTag("permission_status_${step.name}"),
        )
        if (item.status == StepStatus.SKIPPED) {
            // A skipped step degrades visibly: say what is lost.
            Text(
                stringResource(step.degradedRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (item.actionable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                val allow = stringResource(R.string.permissions_action_allow_description, title)
                Button(
                    onClick = { actions.onGrant(step) },
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .semantics { contentDescription = allow }
                        .testTag("permission_grant_${step.name}"),
                ) {
                    Text(stringResource(step.grantLabelRes()))
                }
                if (item.canSkip) {
                    val skip = stringResource(R.string.permissions_action_skip_description, title)
                    TextButton(
                        onClick = { actions.onSkip(step) },
                        modifier = Modifier
                            .heightIn(min = MinTouchTarget)
                            .semantics { contentDescription = skip }
                            .testTag("permission_skip_${step.name}"),
                    ) {
                        Text(stringResource(R.string.permissions_action_skip))
                    }
                }
            }
        }
    }
}

/** Prominent disclosure shown before the background-location system flow (Play policy; AGENTS rule 2). */
@Composable
fun BackgroundDisclosureDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permissions_disclosure_title)) },
        text = { Text(stringResource(R.string.permissions_disclosure_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.permissions_disclosure_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.permissions_disclosure_cancel))
            }
        },
    )
}

private fun StepStatus.labelRes(): Int = when (this) {
    StepStatus.GRANTED -> R.string.permissions_status_granted
    StepStatus.CURRENT -> R.string.permissions_status_not_granted
    StepStatus.SKIPPED -> R.string.permissions_status_skipped
    StepStatus.LOCKED -> R.string.permissions_status_locked
}

private fun PermissionStep.titleRes(): Int = when (this) {
    PermissionStep.LOCATION -> R.string.permissions_step_location
    PermissionStep.BACKGROUND_LOCATION -> R.string.permissions_step_background
    PermissionStep.ACTIVITY_RECOGNITION -> R.string.permissions_step_activity
    PermissionStep.NOTIFICATIONS -> R.string.permissions_step_notifications
    PermissionStep.EXACT_ALARMS -> R.string.permissions_step_exact_alarms
    PermissionStep.BATTERY_OPTIMISATION -> R.string.permissions_step_battery
}

private fun PermissionStep.bodyRes(): Int = when (this) {
    PermissionStep.LOCATION -> R.string.permissions_step_location_body
    PermissionStep.BACKGROUND_LOCATION -> R.string.permissions_step_background_body
    PermissionStep.ACTIVITY_RECOGNITION -> R.string.permissions_step_activity_body
    PermissionStep.NOTIFICATIONS -> R.string.permissions_step_notifications_body
    PermissionStep.EXACT_ALARMS -> R.string.permissions_step_exact_alarms_body
    PermissionStep.BATTERY_OPTIMISATION -> R.string.permissions_step_battery_body
}

private fun PermissionStep.degradedRes(): Int = when (this) {
    PermissionStep.LOCATION -> R.string.permissions_step_location_degraded
    PermissionStep.BACKGROUND_LOCATION -> R.string.permissions_step_background_degraded
    PermissionStep.ACTIVITY_RECOGNITION -> R.string.permissions_step_activity_degraded
    PermissionStep.NOTIFICATIONS -> R.string.permissions_step_notifications_degraded
    PermissionStep.EXACT_ALARMS -> R.string.permissions_step_exact_alarms_degraded
    PermissionStep.BATTERY_OPTIMISATION -> R.string.permissions_step_battery_degraded
}

/** Steps granted on a system settings page say "Open settings"; the rest show a runtime dialog. */
private fun PermissionStep.grantLabelRes(): Int = when (this) {
    PermissionStep.EXACT_ALARMS, PermissionStep.BATTERY_OPTIMISATION -> R.string.permissions_action_open_settings
    else -> R.string.permissions_action_allow
}
