package me.tarry.app.ui.permissions

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
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.Chevron
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.TarryTheme

/** What the permission screen can ask for. The stateful wrapper wires these to launchers and the ViewModel. */
data class PermissionsActions(
    val onGrant: (PermissionStep) -> Unit,
    val onSkip: (PermissionStep) -> Unit,
    val onConfirmBackgroundDisclosure: () -> Unit,
    val onDismissBackgroundDisclosure: () -> Unit,
    val onDone: () -> Unit,
    /** Opens the system page where a granted permission can be changed. */
    val onChange: (PermissionStep) -> Unit = {},
)

private val MinTouchTarget = 48.dp
private val PermissionRowMinHeight = 64.dp

/** Stateless permission list; renders every [StepStatus] so it can be Compose-tested without Hilt. */
@Composable
fun PermissionsContent(
    state: PermissionsUiState,
    actions: PermissionsActions,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
        Text(
            stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TarryTheme.colors.ink,
        )
        Text(
            stringResource(R.string.permissions_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
            modifier = Modifier.padding(bottom = space.s),
        )
        state.items.forEach { entry -> PermissionRow(entry, actions) }
        if (state.missingRequired.isNotEmpty()) {
            Text(
                stringResource(R.string.permissions_missing_required),
                style = MaterialTheme.typography.bodyMedium,
                color = TarryTheme.colors.alert,
                modifier = Modifier.padding(vertical = space.s).testTag("permissions_missing_required"),
            )
        }
        PrimaryButton(
            text = stringResource(R.string.permissions_action_done),
            onClick = actions.onDone,
            modifier = Modifier.padding(top = space.s).testTag("permissions_done"),
        )
    }
    if (state.showBackgroundDisclosure) {
        BackgroundDisclosureDialog(actions.onConfirmBackgroundDisclosure, actions.onDismissBackgroundDisclosure)
    }
}

/**
 * One permission on a `card` surface in the Sessions export-row style: a leading 24 dp icon, a chevron on the right,
 * and the whole surface is the button. Stacked: the status (icon and word), the title, the description, then the
 * required / skipped warning. Tapping runs the action: [PermissionsActions.onGrant] when not granted,
 * [PermissionsActions.onChange] (the system settings page) when granted; a locked step is not tappable.
 */
@Composable
private fun PermissionRow(entry: PermissionItem, actions: PermissionsActions) {
    val step = entry.step
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    val granted = entry.status == StepStatus.GRANTED
    val onClick: (() -> Unit)? = when {
        granted -> ({ actions.onChange(step) })
        entry.actionable -> ({ actions.onGrant(step) })
        else -> null
    }
    val clickLabel = stringResource(if (granted) R.string.permissions_action_change else R.string.permissions_action_grant)
    val frame = if (onClick != null) {
        Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    SettingsGroup {
        item {
            Row(
                modifier = frame
                    .fillMaxWidth()
                    .heightIn(min = PermissionRowMinHeight)
                    .padding(horizontal = space.l, vertical = space.m)
                    .testTag("permission_row_${step.name}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.l),
            ) {
                Icon(
                    painter = painterResource(step.iconRes()),
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.xs)) {
                    val statusColor = if (granted) colors.success else colors.alert
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.xs)) {
                        Icon(
                            painter = painterResource(if (granted) R.drawable.ic_check else R.drawable.ic_close),
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(entry.status.labelRes()),
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                            modifier = Modifier.testTag("permission_status_${step.name}"),
                        )
                    }
                    Text(stringResource(step.titleRes()), style = MaterialTheme.typography.titleSmall, color = colors.ink)
                    Text(stringResource(step.bodyRes()), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    if (step.requiredForScheduled) {
                        // The amber warning only while the permission is missing; once granted it is a quiet reminder.
                        val granted = entry.status == StepStatus.GRANTED
                        Text(
                            stringResource(if (granted) R.string.permissions_required_note_granted else R.string.permissions_required_note),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (granted) colors.muted else colors.accentStrong,
                            modifier = Modifier.testTag("permission_required_note_${step.name}"),
                        )
                    }
                    if (entry.status == StepStatus.SKIPPED) {
                        // A skipped step degrades visibly: say what is lost.
                        Text(stringResource(step.degradedRes()), style = MaterialTheme.typography.bodySmall, color = colors.alert)
                    }
                }
                if (onClick != null) Chevron()
            }
        }
    }
    if (entry.canSkip) {
        // Outside the card, so the card stays one button. Steps unlock in order: without Skip a declined optional step blocks the rest.
        val skip = stringResource(R.string.permissions_action_skip_description, stringResource(step.titleRes()))
        TertiaryButton(
            text = stringResource(R.string.permissions_action_skip),
            onClick = { actions.onSkip(step) },
            modifier = Modifier
                .semantics { contentDescription = skip }
                .testTag("permission_skip_${step.name}"),
        )
    }
}


private fun PermissionStep.iconRes(): Int = when (this) {
    PermissionStep.LOCATION -> R.drawable.ic_location_pin
    PermissionStep.BACKGROUND_LOCATION -> R.drawable.ic_radius
    PermissionStep.ACTIVITY_RECOGNITION -> R.drawable.ic_walk
    PermissionStep.NOTIFICATIONS -> R.drawable.ic_notifications
    PermissionStep.EXACT_ALARMS -> R.drawable.ic_timeline
    PermissionStep.BATTERY_OPTIMISATION -> R.drawable.ic_bolt
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
