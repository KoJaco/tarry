package me.tarry.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.R
import me.tarry.core.data.settings.Settings

/** Hilt-wired entry point. */
@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onOpenPermissions = onOpenPermissions,
        onOpenSessions = onOpenSessions,
        onStayRadiusSelected = viewModel::setStayGeofenceRadiusM,
        modifier = modifier,
    )
}

/** Stateless list of the current settings; the stop radius is the only editable one. */
@Composable
fun SettingsContent(
    settings: Settings?,
    onOpenPermissions: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
    onStayRadiusSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showRadiusDialog by rememberSaveable { mutableStateOf(false) }
    val on = stringResource(R.string.settings_on)
    val off = stringResource(R.string.settings_off)
    val rows: List<Pair<String, String>> = settings?.let { s ->
        listOf(
            stringResource(R.string.settings_rounding_increment) to
                if (s.roundingIncrementMin == 0) off
                else stringResource(R.string.settings_minutes_value, s.roundingIncrementMin),
            stringResource(R.string.settings_rounding_mode) to s.roundingMode.name,
            stringResource(R.string.settings_min_billable) to
                stringResource(R.string.settings_minutes_value, s.minBillableMinutes),
            stringResource(R.string.settings_round_daily_total) to if (s.roundDailyTotalPerJob) on else off,
            stringResource(R.string.settings_travel_attribution) to s.travelAttribution.name,
            stringResource(R.string.settings_ping_retention) to
                (s.rawPingRetentionDays?.let { pluralStringResource(R.plurals.settings_days_value, it, it) }
                    ?: stringResource(R.string.settings_forever)),
            stringResource(R.string.settings_review_reminder) to if (s.reviewReminderEnabled) on else off,
            stringResource(R.string.settings_review_reminder_time) to
                "%02d:%02d".format(s.reviewReminderMinuteOfDay / 60, s.reviewReminderMinuteOfDay % 60),
            stringResource(R.string.settings_theme) to s.theme.name,
        )
    } ?: emptyList()

    if (showRadiusDialog && settings != null) {
        StayRadiusDialog(
            current = settings.stayGeofenceRadiusM,
            onSelect = {
                onStayRadiusSelected(it)
                showRadiusDialog = false
            },
            onDismiss = { showRadiusDialog = false },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp),
        )
        ClickableRow(stringResource(R.string.settings_permissions), onOpenPermissions)
        ClickableRow(stringResource(R.string.settings_sessions), onOpenSessions)
        if (settings != null) {
            ClickableRow(
                label = stringResource(R.string.settings_stay_radius),
                value = stringResource(R.string.settings_stay_radius_value, settings.stayGeofenceRadiusM),
                onClick = { showRadiusDialog = true },
            )
        }
        LazyColumn {
            items(rows) { (label, value) -> SettingRow(label, value) }
        }
    }
}

@Composable
private fun ClickableRow(label: String, onClick: () -> Unit, value: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StayRadiusDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_stay_radius)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_stay_radius_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(Modifier.selectableGroup()) {
                    for (meters in Settings.ALLOWED_STAY_RADII_M.sorted()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = meters == current, role = Role.RadioButton) { onSelect(meters) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The row is the selectable target; the button only shows state.
                            RadioButton(selected = meters == current, onClick = null)
                            Text(
                                stringResource(R.string.settings_stay_radius_value, meters),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dialog_cancel)) } },
    )
}
