package me.tarry.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.R
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.ThemeMode

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
        onThemeSelected = viewModel::setTheme,
        modifier = modifier,
    )
}

private enum class Picker { RADIUS, THEME }

/** Stateless sectioned settings; the stop radius and theme are the editable ones. */
@Composable
fun SettingsContent(
    settings: Settings?,
    onOpenPermissions: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
    onStayRadiusSelected: (Int) -> Unit = {},
    onThemeSelected: (ThemeMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    val on = stringResource(R.string.settings_on)
    val off = stringResource(R.string.settings_off)
    val billing: List<Pair<String, String>> = settings?.let { s ->
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
        )
    } ?: emptyList()

    if (settings != null) {
        when (picker) {
            Picker.RADIUS -> PickerSheet(
                title = stringResource(R.string.settings_stay_radius),
                explanation = stringResource(R.string.settings_stay_radius_explanation),
                options = Settings.ALLOWED_STAY_RADII_M.sorted()
                    .map { it to stringResource(R.string.settings_stay_radius_value, it) },
                details = Settings.ALLOWED_STAY_RADII_M.associateWith { stayRadiusDetail(it) },
                current = settings.stayGeofenceRadiusM,
                onSelect = {
                    onStayRadiusSelected(it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
            Picker.THEME -> PickerSheet(
                title = stringResource(R.string.settings_theme),
                explanation = null,
                options = ThemeMode.entries.map { it to stringResource(it.labelRes()) },
                current = settings.theme,
                onSelect = {
                    onThemeSelected(it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
            null -> Unit
        }
    }

    val space = TarryTheme.space
    Column(
        modifier = modifier
            .fillMaxSize()
                .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(
            text = stringResource(R.string.nav_settings),
            style = MaterialTheme.typography.headlineMedium,
            color = TarryTheme.colors.ink,
        )
        SectionHeader(stringResource(R.string.settings_section_tracking), Modifier.padding(top = space.m))
        val stayRadiusTitle = stringResource(R.string.settings_stay_radius)
        val stayRadiusValue = settings?.let { stringResource(R.string.settings_stay_radius_value, it.stayGeofenceRadiusM) }
        val permissionsTitle = stringResource(R.string.settings_permissions)
        val sessionsTitle = stringResource(R.string.settings_sessions)
        SettingsGroup {
            if (stayRadiusValue != null) {
                row(stayRadiusTitle, onClick = { picker = Picker.RADIUS }, icon = R.drawable.ic_radius, subtitle = stayRadiusValue)
            }
            row(permissionsTitle, onClick = onOpenPermissions, icon = R.drawable.ic_permissions)
            row(sessionsTitle, onClick = onOpenSessions, icon = R.drawable.ic_sessions)
        }
        if (settings != null) {
            SectionHeader(stringResource(R.string.settings_section_appearance), Modifier.padding(top = space.m))
            val themeTitle = stringResource(R.string.settings_theme)
            val themeValue = stringResource(settings.theme.labelRes())
            SettingsGroup {
                row(themeTitle, onClick = { picker = Picker.THEME }, icon = R.drawable.ic_theme, subtitle = themeValue)
            }
            SectionHeader(stringResource(R.string.settings_section_billing), Modifier.padding(top = space.m))
            SettingsGroup {
                billing.forEach { (label, value) -> row(label, value = value) }
            }
        }
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/** One-line use case for a picker option, plus an optional warning shown beneath it. */
private data class OptionDetail(val useCase: String, val warning: String? = null)

@Composable
private fun stayRadiusDetail(meters: Int): OptionDetail = when (meters) {
    50 -> OptionDetail(stringResource(R.string.settings_stay_radius_use_50), stringResource(R.string.settings_stay_radius_small_warning))
    75 -> OptionDetail(stringResource(R.string.settings_stay_radius_use_75), stringResource(R.string.settings_stay_radius_small_warning))
    100 -> OptionDetail(stringResource(R.string.settings_stay_radius_use_100))
    150 -> OptionDetail(stringResource(R.string.settings_stay_radius_use_150))
    else -> OptionDetail(stringResource(R.string.settings_stay_radius_use_200))
}

/** Bottom sheet of selectable rows (radio semantics, the row is the target). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PickerSheet(
    title: String,
    explanation: String?,
    options: List<Pair<T, String>>,
    current: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    details: Map<T, OptionDetail> = emptyMap(),
) {
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    val shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = colors.card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = space.gutter)
                .padding(bottom = space.l),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.ink)
            if (explanation != null) {
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    modifier = Modifier.padding(top = space.xs, bottom = space.s),
                )
            }
            Column(Modifier.selectableGroup()) {
                for ((value, label) in options) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = value == current, role = Role.RadioButton) { onSelect(value) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The row is the selectable target; the button only shows state.
                        RadioButton(
                            selected = value == current,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = colors.ink, unselectedColor = colors.outline),
                        )
                        Column(Modifier.padding(start = space.l, top = space.xs, bottom = space.xs)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                            val detail = details[value]
                            if (detail != null) {
                                Text(detail.useCase, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                                if (detail.warning != null) {
                                    Text(
                                        detail.warning,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onAlertSoft,
                                        modifier = Modifier
                                            .padding(top = space.xs)
                                            .background(colors.alertSoft, TarryTheme.shapes.container)
                                            .padding(space.s),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
