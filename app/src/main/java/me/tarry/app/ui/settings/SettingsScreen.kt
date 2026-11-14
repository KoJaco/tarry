package me.tarry.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.remember
import me.tarry.app.reminder.ReviewReminderNotifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.ZoneOffset
import me.tarry.app.ui.drawer.TimePickerDialog
import me.tarry.app.ui.permissions.PermissionIntents
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.health.healthStatusRes
import me.tarry.app.ui.timeline.rememberTimeFormats
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.heading
import me.tarry.app.ui.theme.surfaceEdgeStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import me.tarry.app.BuildConfig
import me.tarry.app.R
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.access.AccessRow
import me.tarry.app.ui.access.AccessSummaryViewModel
import me.tarry.app.ui.about.versionLabel
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.schedule.WeekScheduleSummary
import me.tarry.app.ui.schedule.scheduleSummaryText
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.theme.surfaceEdge
import me.tarry.app.ui.components.TarrySheet
import me.tarry.core.data.settings.PowerMode
import me.tarry.core.data.settings.RoundingMode
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.ThemeMode

/** Hilt-wired entry point. */
@Composable
fun SettingsScreen(
    trackingState: TrackingState,
    onOpenTrackingHealth: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSchedule: () -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenPlaces: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenPaywall: () -> Unit = {},
    /** The low-battery switch has put capture in Saver right now (the Battery use row says so). */
    lowBatterySaverActive: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    accessViewModel: AccessSummaryViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val access by accessViewModel.row.collectAsStateWithLifecycle()
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
    // The setting stays on if the user refuses; the Reminders group then says notifications are off (AGENTS rule 6).
    var notificationsBlocked by remember { mutableStateOf(ReviewReminderNotifier.isBlocked(context)) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsBlocked = ReviewReminderNotifier.isBlocked(context)
    }
    // Back from the system notification settings (or the permission dialog): look again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsBlocked = ReviewReminderNotifier.isBlocked(context)
    }
    val startBackup = { createBackup.launch("tarry-backup-${LocalDate.now()}.json.gz") }
    SettingsContent(
        settings = settings,
        schedule = schedule,
        data = data,
        zone = viewModel.zone,
        onBackUp = startBackup,
        onRestore = { openBackup.launch(arrayOf("*/*")) },
        onConfirmRestore = viewModel::confirmRestore,
        onBackUpFirst = startBackup,
        onCancelRestore = viewModel::cancelRestore,
        onOpenSchedule = onOpenSchedule,
        trackingState = trackingState,
        onOpenTrackingHealth = onOpenTrackingHealth,
        onOpenSessions = onOpenSessions,
        onOpenJobs = onOpenJobs,
        onOpenPlaces = onOpenPlaces,
        onOpenAbout = onOpenAbout,
        access = access,
        onOpenPaywall = onOpenPaywall,
        onStayRadiusSelected = viewModel::setStayGeofenceRadiusM,
        onGraceSelected = viewModel::setWindowEndGraceMaxMin,
        onPowerModeSelected = viewModel::setPowerMode,
        onLowBatterySaverChange = viewModel::setLowBatterySaver,
        lowBatterySaverActive = lowBatterySaverActive,
        onThemeSelected = viewModel::setTheme,
        onRoundingIncrementSelected = viewModel::setRoundingIncrementMin,
        onRoundingModeSelected = viewModel::setRoundingMode,
        onMinBillableSelected = viewModel::setMinBillableMinutes,
        onRoundDailyTotalChange = viewModel::setRoundDailyTotalPerJob,
        onRetentionSelected = viewModel::setRawPingRetentionDays,
        onDeleteAll = viewModel::deleteAll,
        onReviewReminderChange = { enabled ->
            viewModel.setReviewReminderEnabled(enabled)
            if (enabled && needsNotificationPermission(context)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onReviewReminderTimeSelected = viewModel::setReviewReminderMinuteOfDay,
        notificationsBlocked = notificationsBlocked,
        onOpenNotificationSettings = { context.startActivity(PermissionIntents.notificationSettings(context)) },
        modifier = modifier,
    )
}

private enum class Picker { RADIUS, GRACE, POWER, THEME, ROUNDING, ROUND_TO, MIN_BILLABLE, RETENTION, REMINDER_TIME }

/** Android 13 and later ask for the notification permission at runtime; before that there is nothing to ask. */
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** Stateless sectioned settings; the stop radius, theme and the billing rounding rows are the editable ones. */
@Composable
fun SettingsContent(
    settings: Settings?,
    schedule: WeekScheduleSummary? = null,
    onOpenSchedule: () -> Unit = {},
    /** Drives the Tracking health row's subtitle (Working, Off, Paused, Needs attention). */
    trackingState: TrackingState = TrackingState.IDLE,
    onOpenTrackingHealth: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
    onOpenJobs: () -> Unit = {},
    onOpenPlaces: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    /** The access row (null hides it, while the trial has not started) and where it goes. */
    access: AccessRow? = null,
    onOpenPaywall: () -> Unit = {},
    onStayRadiusSelected: (Int) -> Unit = {},
    onGraceSelected: (Int) -> Unit = {},
    onPowerModeSelected: (PowerMode) -> Unit = {},
    onLowBatterySaverChange: (Boolean) -> Unit = {},
    /** Shown on the Battery use row; capture is in Saver because the battery is low. */
    lowBatterySaverActive: Boolean = false,
    onThemeSelected: (ThemeMode) -> Unit = {},
    onRoundingIncrementSelected: (Int) -> Unit = {},
    onRoundingModeSelected: (RoundingMode) -> Unit = {},
    onMinBillableSelected: (Int) -> Unit = {},
    onRoundDailyTotalChange: (Boolean) -> Unit = {},
    onRetentionSelected: (Int?) -> Unit = {},
    onDeleteAll: () -> Unit = {},
    onReviewReminderChange: (Boolean) -> Unit = {},
    onReviewReminderTimeSelected: (Int) -> Unit = {},
    /** Notifications are off for Tarry; with the reminder on, the Reminders group says so and links to the system settings. */
    notificationsBlocked: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    data: DataUiState = DataUiState(),
    zone: ZoneId = ZoneId.systemDefault(),
    onBackUp: () -> Unit = {},
    onRestore: () -> Unit = {},
    onConfirmRestore: () -> Unit = {},
    onBackUpFirst: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val on = stringResource(R.string.settings_on)
    val off = stringResource(R.string.settings_off)
    var pendingRetention by rememberSaveable { mutableStateOf<Int?>(null) }
    val currentRetention = settings?.let { retentionLabel(it.rawPingRetentionDays) }
    // Forever has no lines to lose, so it keeps the bare label.
    val retentionValue = if (settings?.rawPingRetentionDays == null) currentRetention
    else stringResource(R.string.settings_ping_retention_row, currentRetention.orEmpty())

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
            Picker.GRACE -> PickerSheet(
                title = stringResource(R.string.settings_grace),
                explanation = stringResource(R.string.settings_grace_explanation),
                options = Settings.ALLOWED_WINDOW_END_GRACE_MIN.map { it to graceLabel(it) },
                details = Settings.ALLOWED_WINDOW_END_GRACE_MIN.associateWith { graceDetail(it) },
                current = settings.windowEndGraceMaxMin,
                onSelect = {
                    onGraceSelected(it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
            Picker.POWER -> PickerSheet(
                title = stringResource(R.string.battery_mode_title),
                explanation = stringResource(R.string.battery_mode_explanation),
                options = PowerMode.entries.map { it to stringResource(it.labelRes()) },
                details = PowerMode.entries.associateWith { stringResource(it.useRes()) },
                current = settings.powerMode,
                onSelect = {
                    onPowerModeSelected(it)
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
            Picker.ROUNDING -> PickerSheet(
                title = stringResource(R.string.settings_rounding_increment),
                explanation = stringResource(R.string.settings_rounding_increment_explanation),
                options = Settings.ALLOWED_ROUNDING_INCREMENTS.sorted().map { it to minutesOrOff(it) },
                current = settings.roundingIncrementMin,
                onSelect = {
                    onRoundingIncrementSelected(it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
            Picker.ROUND_TO -> PickerSheet(
                title = stringResource(R.string.settings_rounding_mode),
                explanation = stringResource(R.string.settings_rounding_mode_explanation),
                options = RoundingMode.entries.map { it to stringResource(it.labelRes()) },
                details = RoundingMode.entries.associateWith { stringResource(it.detailRes()) },
                current = settings.roundingMode,
                onSelect = {
                    onRoundingModeSelected(it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
            Picker.MIN_BILLABLE -> PickerSheet(
                title = stringResource(R.string.settings_min_billable),
                explanation = stringResource(R.string.settings_min_billable_explanation),
                // A stored value outside the list still shows (and stays selected) until the user picks another.
                options = (Settings.ALLOWED_MIN_BILLABLE_MINUTES + settings.minBillableMinutes).sorted()
                    .map { it to minutesOrOff(it) },
                current = settings.minBillableMinutes,
                onSelect = {
                    onMinBillableSelected(it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
            Picker.RETENTION -> PickerSheet(
                title = stringResource(R.string.settings_ping_retention),
                explanation = stringResource(R.string.settings_ping_retention_explanation),
                options = RETENTION_OPTIONS.map { it to retentionLabel(it) },
                current = settings.rawPingRetentionDays,
                onSelect = {
                    if (isShorterRetention(it, settings.rawPingRetentionDays)) pendingRetention = it else onRetentionSelected(it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
            Picker.REMINDER_TIME -> TimePickerDialog(
                title = stringResource(R.string.settings_review_reminder_time),
                // Minutes since midnight are the same number in any zone, so UTC keeps the picker free of DST gaps.
                initialMs = settings.reviewReminderMinuteOfDay * 60_000L,
                zone = ZoneOffset.UTC,
                is24Hour = DateFormat.is24HourFormat(LocalContext.current),
                onConfirm = { hour, minute ->
                    onReviewReminderTimeSelected(hour * 60 + minute)
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
        if (access != null) {
            SectionHeader(stringResource(R.string.settings_section_access), Modifier.padding(top = space.m))
            val accessTitle = when (access) {
                is AccessRow.Trial -> pluralStringResource(R.plurals.access_trial_days_left, access.daysLeft, access.daysLeft)
                AccessRow.TrialEnded -> stringResource(R.string.settings_access_trial_ended)
                AccessRow.Pending -> stringResource(R.string.settings_access_pending)
                AccessRow.Owned -> stringResource(R.string.settings_access_owned)
                AccessRow.Revoked -> stringResource(R.string.settings_access_revoked)
            }
            SettingsGroup {
                row(accessTitle, onClick = onOpenPaywall, icon = if (access == AccessRow.Owned) R.drawable.ic_check else R.drawable.ic_lock)
            }
        }
        SectionHeader(stringResource(R.string.settings_section_tracking), Modifier.padding(top = space.m))
        val stayRadiusTitle = stringResource(R.string.settings_stay_radius)
        val graceTitle = stringResource(R.string.settings_grace)
        val graceValue = settings?.let { graceLabel(it.windowEndGraceMaxMin) }
        val stayRadiusValue = settings?.let { stringResource(R.string.settings_stay_radius_value, it.stayGeofenceRadiusM) }
        val powerTitle = stringResource(R.string.battery_mode_title)
        val powerValue = settings?.let {
            if (lowBatterySaverActive && it.lowBatterySaver) stringResource(R.string.battery_mode_saver_low) else stringResource(it.powerMode.labelRes())
        }
        val powerSwitchTitle = stringResource(R.string.battery_low_switch_title)
        val powerSwitchSubtitle = stringResource(R.string.battery_low_switch_subtitle)
        val scheduleTitle = stringResource(R.string.settings_schedule)
        val scheduleSubtitle = schedule?.let { scheduleRowSubtitle(it) }
        val healthTitle = stringResource(R.string.settings_tracking_health)
        val healthSubtitle = stringResource(trackingState.healthStatusRes())
        val sessionsTitle = stringResource(R.string.settings_sessions)
        SettingsGroup {
            row(scheduleTitle, onClick = onOpenSchedule, icon = R.drawable.ic_calendar, subtitle = scheduleSubtitle)
            if (stayRadiusValue != null) {
                row(stayRadiusTitle, onClick = { picker = Picker.RADIUS }, icon = R.drawable.ic_radius, subtitle = stayRadiusValue)
            }
            if (powerValue != null && settings != null) {
                row(powerTitle, onClick = { picker = Picker.POWER }, icon = R.drawable.ic_bolt, subtitle = powerValue)
                switchRow(
                    powerSwitchTitle, settings.lowBatterySaver, onLowBatterySaverChange,
                    subtitle = powerSwitchSubtitle, icon = R.drawable.ic_bolt,
                )
            }
            if (graceValue != null) {
                row(graceTitle, onClick = { picker = Picker.GRACE }, icon = R.drawable.ic_more_time, subtitle = graceValue)
            }
            row(healthTitle, onClick = onOpenTrackingHealth, icon = R.drawable.ic_permissions, subtitle = healthSubtitle)
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
            val roundingTitle = stringResource(R.string.settings_rounding_increment)
            val roundingValue = minutesOrOff(settings.roundingIncrementMin)
            val roundToTitle = stringResource(R.string.settings_rounding_mode)
            val roundingOff = settings.roundingIncrementMin == 0
            val roundToValue = if (roundingOff) stringResource(R.string.settings_rounding_is_off) else stringResource(settings.roundingMode.labelRes())
            val minTitle = stringResource(R.string.settings_min_billable)
            val minValue = minutesOrOff(settings.minBillableMinutes)
            val perDayTitle = stringResource(R.string.settings_round_daily_total)
            val perDaySubtitle = stringResource(R.string.settings_round_daily_total_subtitle)
            SettingsGroup {
                row(roundingTitle, onClick = { picker = Picker.ROUNDING }, icon = R.drawable.ic_timer, subtitle = roundingValue)
                row(roundToTitle, onClick = { picker = Picker.ROUND_TO }, icon = R.drawable.ic_swap_vert, subtitle = roundToValue, enabled = !roundingOff)
                row(minTitle, onClick = { picker = Picker.MIN_BILLABLE }, icon = R.drawable.ic_hourglass_bottom, subtitle = minValue)
                switchRow(
                    perDayTitle, settings.roundDailyTotalPerJob, onRoundDailyTotalChange,
                    subtitle = perDaySubtitle, icon = R.drawable.ic_event_repeat,
                )
            }
            SectionHeader(stringResource(R.string.settings_section_reminders), Modifier.padding(top = space.m))
            val reminderTitle = stringResource(R.string.settings_review_reminder)
            val reminderTimeTitle = stringResource(R.string.settings_review_reminder_time)
            val reminderTimeValue = rememberTimeFormats().time(settings.reviewReminderMinuteOfDay * 60_000L, ZoneOffset.UTC)
            val reminderSubtitle = if (settings.reviewReminderEnabled) {
                stringResource(R.string.settings_review_reminder_subtitle, reminderTimeValue)
            } else {
                null
            }
            val notificationsOffText = stringResource(R.string.settings_review_reminder_notifications_off)
            SettingsGroup {
                switchRow(
                    reminderTitle, settings.reviewReminderEnabled, onReviewReminderChange,
                    subtitle = reminderSubtitle, icon = R.drawable.ic_notifications,
                )
                row(
                    reminderTimeTitle, onClick = { picker = Picker.REMINDER_TIME }, icon = R.drawable.ic_schedule,
                    value = reminderTimeValue, enabled = settings.reviewReminderEnabled,
                )
                if (settings.reviewReminderEnabled && notificationsBlocked) {
                    row(notificationsOffText, onClick = onOpenNotificationSettings, icon = R.drawable.ic_notifications)
                }
            }
        }
        DataSection(settings?.lastBackupMs, retentionValue, data, zone, onBackUp, onRestore, onRetention = { picker = Picker.RETENTION }, onDeleteAll = { confirmDeleteAll = true })
        SectionHeader(stringResource(R.string.settings_section_about), Modifier.padding(top = space.m))
        val aboutTitle = stringResource(R.string.settings_about_row)
        val aboutVersion = versionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        SettingsGroup {
            row(aboutTitle, onClick = onOpenAbout, icon = R.drawable.ic_info, subtitle = aboutVersion)
        }
    }
    pendingRetention?.let { days ->
        ShortenRetentionDialog(
            label = retentionLabel(days),
            onConfirm = {
                pendingRetention = null
                onRetentionSelected(days)
            },
            onCancel = { pendingRetention = null },
        )
    }
    if (confirmDeleteAll) {
        DeleteAllDialog(
            onDelete = {
                confirmDeleteAll = false
                onDeleteAll()
            },
            onCancel = { confirmDeleteAll = false },
        )
    }
    // Hidden (not dropped) while "Back up current data first" runs, so it comes back when that finishes.
    if (data.prompt != null && data.busy == null) {
        RestoreDialog(data.prompt, zone, onReplace = onConfirmRestore, onBackUpFirst = onBackUpFirst, onCancel = onCancelRestore)
    }
}

/** Settings → Data: back up, restore, and a progress row while either runs. */
@Composable
private fun ColumnScope.DataSection(lastBackupMs: Long?, retentionValue: String?, data: DataUiState, zone: ZoneId, onBackUp: () -> Unit, onRestore: () -> Unit, onRetention: () -> Unit, onDeleteAll: () -> Unit) {
    val space = TarryTheme.space
    val busy = data.busy != null
    SectionHeader(stringResource(R.string.settings_section_data), Modifier.padding(top = space.m))
    val backUpTitle = stringResource(R.string.settings_backup_now)
    val restoreTitle = stringResource(R.string.settings_restore)
    val retentionTitle = stringResource(R.string.settings_ping_retention)
    val restorePrivacy = stringResource(R.string.settings_data_privacy)
    val lastBackup = lastBackupMs?.let { stringResource(R.string.settings_backup_last, formatBackupDate(it, zone)) }
    val working = data.busy?.let { stringResource(it.labelRes()) }
    SettingsGroup {
        row(backUpTitle, onClick = onBackUp, icon = R.drawable.ic_backup, subtitle = lastBackup, enabled = !busy)
        row(
            restoreTitle,
            onClick = onRestore,
            icon = R.drawable.ic_settings_backup_restore,
            subtitle = restorePrivacy,
            enabled = !busy,
        )
        if (retentionValue != null) row(retentionTitle, onClick = onRetention, icon = R.drawable.ic_history, subtitle = retentionValue, enabled = !busy)
        if (working != null) item { WorkingRow(working) }
    }
    DestructiveButton(
        stringResource(R.string.delete_all_title),
        onClick = onDeleteAll,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
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
private fun RestoreDialog(prompt: RestorePrompt, zone: ZoneId, onReplace: () -> Unit, onBackUpFirst: () -> Unit, onCancel: () -> Unit) {
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    val resources = LocalContext.current.resources
    AlertDialog(
        modifier = Modifier.surfaceEdge(TarryTheme.shapes.container),
        onDismissRequest = onCancel,
        shape = TarryTheme.shapes.container,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.restore_dialog_title), style = MaterialTheme.typography.titleLarge, color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
                Text(stringResource(R.string.restore_dialog_body), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                Text(
                    stringResource(R.string.restore_dialog_backup_counts, formatBackupDate(prompt.backupCreatedAt, zone), formatCounts(resources, prompt.backup)),
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

/** Type-to-confirm before everything recorded is removed. The confirm button stays off until the word matches. */
@Composable
private fun DeleteAllDialog(onDelete: () -> Unit, onCancel: () -> Unit) {
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    val word = stringResource(R.string.delete_all_word)
    val fieldLabel = stringResource(R.string.delete_all_field_label)
    var typed by rememberSaveable { mutableStateOf("") }
    // Our own window layout instead of AlertDialog: the dialog window is edge to edge and its root keeps clear of the system bars and
    // the keyboard (imePadding), so the card can only be as tall as the visible area and its content scrolls. With the platform
    // default the window was only panned, which at large font sizes pushed the buttons behind the keyboard.
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = space.xxl, vertical = space.l),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = TarryTheme.shapes.container,
                color = colors.card,
                border = surfaceEdgeStroke(),
                modifier = Modifier.widthIn(max = 560.dp),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(space.xxl),
                    verticalArrangement = Arrangement.spacedBy(space.s),
                ) {
                    Text(
                        stringResource(R.string.delete_all_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.delete_all_body), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                    // The label is plain text above the field: a floating label would sit on the border and overlap the body at large font sizes.
                    Text(fieldLabel, style = MaterialTheme.typography.labelLarge, color = colors.ink)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = fieldLabel },
                        shape = TarryTheme.shapes.container,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.ink,
                            unfocusedTextColor = colors.ink,
                            focusedBorderColor = colors.ink,
                            unfocusedBorderColor = colors.outline,
                            focusedLabelColor = colors.muted,
                            unfocusedLabelColor = colors.muted,
                            cursorColor = colors.ink,
                        ),
                    )
                    Column(Modifier.padding(top = space.s), verticalArrangement = Arrangement.spacedBy(space.s)) {
                        DestructiveButton(
                            stringResource(R.string.delete_all_title),
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = typed.trim().equals(word, ignoreCase = true),
                        )
                        TertiaryButton(stringResource(R.string.edit_dialog_cancel), onClick = onCancel, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Forever counts as the longest. */
internal fun isShorterRetention(new: Int?, current: Int?): Boolean = new != null && (current == null || new < current)

/** Pruning cannot be undone, so shortening the retention asks first. */
@Composable
private fun ShortenRetentionDialog(label: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = TarryTheme.colors
    val space = TarryTheme.space
    AlertDialog(
        modifier = Modifier.surfaceEdge(TarryTheme.shapes.container),
        onDismissRequest = onCancel,
        shape = TarryTheme.shapes.container,
        containerColor = colors.card,
        title = { Text(stringResource(R.string.retention_shorten_title), style = MaterialTheme.typography.titleLarge, color = colors.ink) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(space.s),
            ) {
                Text(stringResource(R.string.retention_shorten_body, label), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                Column(Modifier.padding(top = space.s), verticalArrangement = Arrangement.spacedBy(space.s)) {
                    DestructiveButton(stringResource(R.string.retention_shorten_confirm), onClick = onConfirm, modifier = Modifier.fillMaxWidth())
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
    DataBusy.DELETING -> R.string.data_working_delete
}

/** "2 Nov 2026" in the phone's language and the capture clock's zone. */
private fun formatBackupDate(epochMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(epochMs).atZone(zone))

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
    DataEvent.Deleted -> resources.getString(R.string.data_deleted)
    is DataEvent.Failed -> resources.getString(
        when (event.error) {
            BackupError.CANNOT_OPEN -> R.string.data_error_cannot_open
            BackupError.NOT_A_BACKUP -> R.string.data_error_not_a_backup
            BackupError.TOO_NEW -> R.string.data_error_too_new
            BackupError.DAMAGED -> R.string.data_error_damaged
            BackupError.WRITE_FAILED -> R.string.data_error_write
            BackupError.RESTORE_FAILED -> R.string.data_error_restore
            BackupError.DELETE_FAILED -> R.string.data_error_delete
        },
    )
}

private val RETENTION_OPTIONS: List<Int?> = Settings.ALLOWED_RETENTION_DAYS.sorted() + listOf(null)

/** "30 days", "90 days", "1 year", "Forever". */
@Composable
private fun retentionLabel(days: Int?): String = when (days) {
    null -> stringResource(R.string.settings_forever)
    365 -> stringResource(R.string.settings_one_year)
    else -> pluralStringResource(R.plurals.settings_days_value, days, days)
}

@Composable
private fun minutesOrOff(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_minutes_value, minutes)

internal fun PowerMode.labelRes(): Int = when (this) {
    PowerMode.PRECISE -> R.string.battery_mode_precise
    PowerMode.BALANCED -> R.string.battery_mode_balanced
    PowerMode.SAVER -> R.string.battery_mode_saver
}

private fun PowerMode.useRes(): Int = when (this) {
    PowerMode.PRECISE -> R.string.battery_mode_precise_use
    PowerMode.BALANCED -> R.string.battery_mode_balanced_use
    PowerMode.SAVER -> R.string.battery_mode_saver_use
}

private fun RoundingMode.labelRes(): Int = when (this) {
    RoundingMode.NEAREST -> R.string.settings_rounding_mode_nearest
    RoundingMode.UP -> R.string.settings_rounding_mode_up
}

private fun RoundingMode.detailRes(): Int = when (this) {
    RoundingMode.NEAREST -> R.string.settings_rounding_mode_nearest_detail
    RoundingMode.UP -> R.string.settings_rounding_mode_up_detail
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@Composable
private fun graceLabel(minutes: Int): String = stringResource(
    when (minutes) {
        0 -> R.string.settings_grace_0
        15 -> R.string.settings_grace_15
        30 -> R.string.settings_grace_30
        else -> R.string.settings_grace_60
    },
)

@Composable
private fun graceDetail(minutes: Int): String = stringResource(
    when (minutes) {
        0 -> R.string.settings_grace_use_0
        60 -> R.string.settings_grace_use_60
        else -> R.string.settings_grace_use_keep
    },
)

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
    TarrySheet(
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
