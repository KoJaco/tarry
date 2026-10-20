package me.tarry.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.R
import me.tarry.core.data.settings.Settings

/** Hilt-wired entry point. */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(settings, modifier)
}

/** Stateless, read-only list of the current settings. */
@Composable
fun SettingsContent(settings: Settings?, modifier: Modifier = Modifier) {
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
                (s.rawPingRetentionDays?.let { stringResource(R.string.settings_days_value, it) }
                    ?: stringResource(R.string.settings_forever)),
            stringResource(R.string.settings_review_reminder) to if (s.reviewReminderEnabled) on else off,
            stringResource(R.string.settings_review_reminder_time) to
                "%02d:%02d".format(s.reviewReminderMinuteOfDay / 60, s.reviewReminderMinuteOfDay % 60),
            stringResource(R.string.settings_theme) to s.theme.name,
        )
    } ?: emptyList()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn {
            items(rows) { (label, value) -> SettingRow(label, value) }
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
