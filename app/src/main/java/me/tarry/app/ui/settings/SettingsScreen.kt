package me.tarry.app.ui.settings

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import me.tarry.app.export.backup.BackupError
import me.tarry.app.ui.components.DestructiveButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.core.data.db.BackupCounts
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
import me.tarry.app.ui.schedule.WeekScheduleSummary
import me.tarry.app.ui.schedule.scheduleSummaryText
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.ThemeMode

/** Hilt-wired entry point. */
@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSchedule: () -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenPlaces: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Both go through the system file picker (SAF): nothing is read or written unless the user picks a document.
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.backUp(uri)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openBackup(uri)
    }
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event -> snackbarHostState?.showSnackbar(dataEventMessage(context.resources, event)) }
    }
    val startBackup = { createBackup.launch("tarry-backup-${LocalDate.now()}.json.gz") }
    SettingsContent(
        settings = settings,
        schedule = schedule,
        data = data,
        onBackUp = startBackup,
        onRestore = { openBackup.launch(arrayOf("*/*")) },
        onConfirmRestore = viewModel::confirmRestore,
        onBackUpFirst = startBackup,
        onCancelRestore = viewModel::cancelRestore,
        onOpenSchedule = onOpenSchedule,
        onOpenPermissions = onOpenPermissions,
        onOpenSessions = onOpenSessions,
        onOpenJobs = onOpenJobs,
        onOpenPlaces = onOpenPlaces,
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
    schedule: WeekScheduleSummary? = null,
    onOpenSchedule: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenPlaces: () -> Unit = {},
    onStayRadiusSelected: (Int) -> Unit = {},
    onThemeSelected: (ThemeMode) -> Unit = {},
    data: DataUiState = DataUiState(),
    onBackUp: () -> Unit = {},
    onRestore: () -> Unit = {},
    onConfirmRestore: () -> Unit = {},
    onBackUpFirst: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
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
                warning = stringResource(R.string.settings_stay_radius_small_warning),
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
        val scheduleTitle = stringResource(R.string.settings_schedule)
        val scheduleSubtitle = schedule?.let { scheduleRowSubtitle(it) }
        val permissionsTitle = stringResource(R.string.settings_permissions)
        val sessionsTitle = stringResource(R.string.settings_sessions)
        SettingsGroup {
            row(scheduleTitle, onClick = onOpenSchedule, icon = R.drawable.ic_calendar, subtitle = scheduleSubtitle)
            if (stayRadiusValue != null) {
                row(stayRadiusTitle, onClick = { picker = Picker.RADIUS }, icon = R.drawable.ic_radius, subtitle = stayRadiusValue)
            }
            row(permissionsTitle, onClick = onOpenPermissions, icon = R.drawable.ic_permissions)
            row(sessionsTitle, onClick = onOpenSessions, icon = R.drawable.ic_sessions)
        }
        SectionHeader(stringResource(R.string.settings_section_labels), Modifier.padding(top = space.m))
        val jobsTitle = stringResource(R.string.settings_jobs)
        val placesTitle = stringResource(R.string.settings_places)
        SettingsGroup {
            row(jobsTitle, onClick = onOpenJobs, icon = R.drawable.ic_work)
            row(placesTitle, onClick = onOpenPlaces, icon = R.drawable.ic_location_pin)
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
        DataSection(settings?.lastBackupMs, data, onBackUp, onRestore)
    }
    // Hidden (not dropped) while "Back up current data first" runs, so it comes back when that finishes.
    if (data.prompt != null && data.busy == null) {
        RestoreDialog(data.prompt, onReplace = onConfirmRestore, onBackUpFirst = onBackUpFirst, onCancel = onCancelRestore)
    }
}

/** Settings → Data: back up, restore, and a progress row while either runs. */
@Composable
private fun ColumnScope.DataSection(lastBackupMs: Long?, data: DataUiState, onBackUp: () -> Unit, onRestore: () -> Unit) {
    val space = TarryTheme.space
    val busy = data.busy != null
    SectionHeader(stringResource(R.string.settings_section_data), Modifier.padding(top = space.m))
    val backUpTitle = stringResource(R.string.settings_backup_now)
    val restoreTitle = stringResource(R.string.settings_restore)
    val lastBackup = lastBackupMs?.let { stringResource(R.string.settings_backup_last, formatBackupDate(it)) }
    val working = data.busy?.let { stringResource(it.labelRes()) }
    SettingsGroup {
        // A row without a click handler is read-only: that is how the rows are disabled while an operation runs.
        row(backUpTitle, onClick = if (busy) null else onBackUp, subtitle = lastBackup)
        row(restoreTitle, onClick = if (busy) null else onRestore)
        if (working != null) item { WorkingRow(working) }
    }
    Text(
        text = stringResource(R.string.settings_data_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = TarryTheme.colors.muted,
    )
}

@Composable
private fun WorkingRow(label: String) {
    val space = TarryTheme.space
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = space.l, vertical = space.m)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.ink)
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = TarryTheme.colors.ink,
            trackColor = TarryTheme.colors.inset,
        )
    }
}

/** The backup's date and counts against this phone's, then Replace / Back up first / Cancel. */
@Composable
private fun RestoreDialog(prompt: RestorePrompt, onReplace: () -> Unit, onBackUpFirst: () -> Unit, onCancel: () -> Unit) {
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    val resources = LocalContext.current.resources
    AlertDialog(
        onDismissRequest = onCancel,
        shape = TarryTheme.shapes.container,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.restore_dialog_title), style = MaterialTheme.typography.titleLarge, color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
                Text(stringResource(R.string.restore_dialog_body), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                Text(
                    stringResource(R.string.restore_dialog_backup_counts, formatBackupDate(prompt.backupCreatedAt), formatCounts(resources, prompt.backup)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
                Text(
                    stringResource(R.string.restore_dialog_phone_counts, formatCounts(resources, prompt.phone)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
                Column(Modifier.padding(top = space.s), verticalArrangement = Arrangement.spacedBy(space.s)) {
                    DestructiveButton(stringResource(R.string.restore_dialog_replace), onClick = onReplace, modifier = Modifier.fillMaxWidth())
                    SecondaryButton(stringResource(R.string.restore_dialog_backup_first), onClick = onBackUpFirst, modifier = Modifier.fillMaxWidth())
                    TertiaryButton(stringResource(R.string.edit_dialog_cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}

private const val BACKUP_MIME_TYPE = "application/gzip"

private fun DataBusy.labelRes(): Int = when (this) {
    DataBusy.BACKING_UP -> R.string.data_working_backup
    DataBusy.READING -> R.string.data_working_read
    DataBusy.RESTORING -> R.string.data_working_restore
}

/** "2 Nov 2026" in the phone's language and time zone. */
private fun formatBackupDate(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** "77 visits, 10,952 locations". */
internal fun formatCounts(resources: Resources, counts: BackupCounts): String {
    val number = NumberFormat.getIntegerInstance()
    return resources.getString(
        R.string.data_counts,
        resources.getQuantityString(R.plurals.data_visits, counts.visits, number.format(counts.visits)),
        resources.getQuantityString(R.plurals.data_locations, counts.locations, number.format(counts.locations)),
    )
}

internal fun dataEventMessage(resources: Resources, event: DataEvent): String = when (event) {
    is DataEvent.BackedUp -> resources.getString(R.string.data_backed_up, formatCounts(resources, event.counts))
    is DataEvent.Restored -> resources.getString(R.string.data_restored, formatCounts(resources, event.counts))
    is DataEvent.Failed -> resources.getString(
        when (event.error) {
            BackupError.CANNOT_OPEN -> R.string.data_error_cannot_open
            BackupError.NOT_A_BACKUP -> R.string.data_error_not_a_backup
            BackupError.TOO_NEW -> R.string.data_error_too_new
            BackupError.DAMAGED -> R.string.data_error_damaged
            BackupError.WRITE_FAILED -> R.string.data_error_write
            BackupError.RESTORE_FAILED -> R.string.data_error_restore
        },
    )
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/** One-line use case for a stop radius option. */
@Composable
private fun stayRadiusDetail(meters: Int): String = when (meters) {
    50 -> stringResource(R.string.settings_stay_radius_use_50)
    75 -> stringResource(R.string.settings_stay_radius_use_75)
    100 -> stringResource(R.string.settings_stay_radius_use_100)
    150 -> stringResource(R.string.settings_stay_radius_use_150)
    else -> stringResource(R.string.settings_stay_radius_use_200)
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
    details: Map<T, String> = emptyMap(),
    warning: String? = null,
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
            if (warning != null) {
                Text(
                    warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onAlertSoft,
                    modifier = Modifier
                        .padding(bottom = space.s)
                        .background(colors.alertSoft, TarryTheme.shapes.container)
                        .padding(horizontal = space.m, vertical = space.m),
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
                            details[value]?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Mon–Fri 07:00–17:00", or "This week: Mon–Fri 07:00–17:00 (own schedule)" when this week has its own. */
@Composable
private fun scheduleRowSubtitle(week: WeekScheduleSummary): String {
    val text = scheduleSummaryText(week.summary)
    return if (week.hasOverride) stringResource(R.string.settings_schedule_this_week_own, text) else text
}
