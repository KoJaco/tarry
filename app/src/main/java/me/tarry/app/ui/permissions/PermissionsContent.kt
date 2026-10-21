package me.tarry.app.ui.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TarryCard
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
        state.items.forEach { item -> PermissionRow(item, actions) }
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

@Composable
private fun PermissionRow(item: PermissionItem, actions: PermissionsActions) {
    val step = item.step
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    val title = stringResource(step.titleRes())
    val statusText = stringResource(item.status.labelRes())
    val granted = item.status == StepStatus.GRANTED
    TarryCard(modifier = Modifier.fillMaxWidth().testTag("permission_row_${step.name}")) {
        Row(horizontalArrangement = Arrangement.spacedBy(space.m)) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.inset),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(step.iconRes()),
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                Text(stringResource(step.bodyRes()), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                if (step.requiredForScheduled) {
                    Text(
                        stringResource(R.string.permissions_required_note),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accentStrong,
                    )
                }
                val statusDescription = stringResource(R.string.permissions_status_description, title, statusText)
                val statusColor = if (granted) colors.success else colors.alert
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.xs),
                    modifier = Modifier
                        .padding(top = space.s),
                ) {
                    Icon(
                        painter = painterResource(if (granted) R.drawable.ic_check else R.drawable.ic_close),
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                        modifier = Modifier
                            .semantics { contentDescription = statusDescription }
                            .testTag("permission_status_${step.name}"),
                    )
                }
                if (item.status == StepStatus.SKIPPED) {
                    // A skipped step degrades visibly: say what is lost.
                    Text(
                        stringResource(step.degradedRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.alert,
                        modifier = Modifier.padding(top = space.xs),
                    )
                }
                if (item.actionable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(space.s), modifier = Modifier.padding(top = space.s)) {
                        val allow = stringResource(R.string.permissions_action_allow_description, title)
                        val grantModifier = Modifier
                            .semantics { contentDescription = allow }
                            .testTag("permission_grant_${step.name}")
                        val grantLabel = stringResource(step.grantLabelRes())
                        if (item.status == StepStatus.CURRENT) {
                            PrimaryButton(grantLabel, { actions.onGrant(step) }, grantModifier)
                        } else {
                            SecondaryButton(grantLabel, { actions.onGrant(step) }, grantModifier)
                        }
                        if (item.canSkip) {
                            val skip = stringResource(R.string.permissions_action_skip_description, title)
                            TertiaryButton(
                                text = stringResource(R.string.permissions_action_skip),
                                onClick = { actions.onSkip(step) },
                                modifier = Modifier
                                    .semantics { contentDescription = skip }
                                    .testTag("permission_skip_${step.name}"),
                            )
                        }
                    }
                } else if (granted) {
                    val change = stringResource(R.string.permissions_action_change_description, title)
                    SecondaryButton(
                        text = stringResource(R.string.permissions_action_change),
                        onClick = { actions.onChange(step) },
                        modifier = Modifier
                            .padding(top = space.s)
                            .semantics { contentDescription = change }
                            .testTag("permission_change_${step.name}"),
                    )
                }
            }
        }
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

/** Steps granted on a system settings page say "Open settings"; the rest show a runtime dialog. */
private fun PermissionStep.grantLabelRes(): Int = when (this) {
    PermissionStep.EXACT_ALARMS, PermissionStep.BATTERY_OPTIMISATION -> R.string.permissions_action_open_settings
    else -> R.string.permissions_action_allow
}
