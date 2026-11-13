package me.tarry.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tarry.app.export.backup.BackupError
import me.tarry.app.export.backup.BackupException
import me.tarry.app.export.backup.DataBackup
import me.tarry.app.export.backup.RestorePreview
import me.tarry.app.reminder.ReviewReminderScheduler
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.ReconcileTrigger
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.ui.schedule.WeekScheduleSummary
import me.tarry.app.ui.schedule.mondayOf
import me.tarry.app.ui.schedule.summarize
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.settings.PowerMode
import me.tarry.core.data.settings.RoundingMode
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.core.data.settings.ThemeMode
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    schedules: ScheduleRepository,
    private val clock: TrackingClock,
    private val backup: DataBackup,
    private val reconcile: ReconcileTrigger,
    // The reconcile after a grace change must finish even if the user leaves Settings right after picking.
    @param:CaptureScope private val captureScope: CoroutineScope,
    private val reviewReminder: ReviewReminderScheduler,
) : ViewModel() {
    /** Null until the first DataStore read completes. */
    val settings: StateFlow<Settings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** This week's effective schedule (its own, else the every-week one); the week is read each time the screen is observed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val schedule: StateFlow<WeekScheduleSummary?> = flow { emit(Instant.ofEpochMilli(clock.nowMs()).atZone(clock.zone()).toLocalDate()) }
        .flatMapLatest { today ->
            combine(schedules.observeTemplate(), schedules.observeOverride(mondayOf(today).toString())) { template, override ->
                WeekScheduleSummary(summarize(override ?: template ?: Schedule.MON_FRI_7_TO_17), hasOverride = override != null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPowerMode(mode: PowerMode) {
        viewModelScope.launch { repository.setPowerMode(mode) }
    }

    fun setLowBatterySaver(enabled: Boolean) {
        viewModelScope.launch { repository.setLowBatterySaver(enabled) }
    }

    /** Applies from the next stop; a stop already being tracked keeps its radius. */
    fun setStayGeofenceRadiusM(meters: Int) {
        viewModelScope.launch { repository.setStayGeofenceRadiusM(meters) }
    }

    /**
     * Saves the cap, then reconciles at once so a running grace and its cap alarm follow the new value. Both run in the
     * app-lifetime capture scope: cancelled half-way, a reconcile could stop capture without re-arming the next window.
     */
    fun setWindowEndGraceMaxMin(minutes: Int) {
        captureScope.launch {
            repository.setWindowEndGraceMaxMin(minutes)
            reconcile.reconcileNow()
        }
    }

    /** Saves the switch and re-arms (or cancels) the reminder alarm; app-lifetime scope so a quick back-out cannot leave them out of step. */
    fun setReviewReminderEnabled(enabled: Boolean) {
        captureScope.launch {
            repository.setReviewReminderEnabled(enabled)
            reviewReminder.rearm()
        }
    }

    fun setReviewReminderMinuteOfDay(minuteOfDay: Int) {
        captureScope.launch {
            repository.setReviewReminderMinuteOfDay(minuteOfDay)
            reviewReminder.rearm()
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { repository.setTheme(mode) }
    }

    fun setRoundingIncrementMin(minutes: Int) {
        viewModelScope.launch { repository.setRoundingIncrementMin(minutes) }
    }

    fun setRoundingMode(mode: RoundingMode) {
        viewModelScope.launch { repository.setRoundingMode(mode) }
    }

    fun setMinBillableMinutes(minutes: Int) {
        viewModelScope.launch { repository.setMinBillableMinutes(minutes) }
    }

    fun setRoundDailyTotalPerJob(enabled: Boolean) {
        viewModelScope.launch { repository.setRoundDailyTotalPerJob(enabled) }
    }

    /** Null keeps raw locations forever. Pruning itself runs daily (RetentionWorker). */
    fun setRawPingRetentionDays(days: Int?) {
        viewModelScope.launch { repository.setRawPingRetentionDays(days) }
    }

    /** The zone dates are shown in (the capture clock's), not a second opinion from the system default. */
    val zone: java.time.ZoneId get() = clock.zone()

    private val _data = MutableStateFlow(DataUiState())
    val data: StateFlow<DataUiState> = _data.asStateFlow()

    private val _events = Channel<DataEvent>(Channel.BUFFERED)
    val events: Flow<DataEvent> = _events.receiveAsFlow()

    /** The decoded, validated backup behind the open confirm dialog; in memory only. */
    private var pending: RestorePreview? = null

    /** Writes a backup to [uri] (the document the user picked). Ignored while another operation runs. */
    fun backUp(uri: Uri) {
        if (_data.value.busy != null) return
        viewModelScope.launch {
            _data.update { it.copy(busy = DataBusy.BACKING_UP) }
            try {
                _events.send(DataEvent.BackedUp(backup.backUp(uri)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(DataEvent.Failed((e as? BackupException)?.error ?: BackupError.WRITE_FAILED))
            } finally {
                _data.update { it.copy(busy = null) }
            }
        }
    }

    /** Reads and checks the backup at [uri], then asks for confirmation. Changes nothing. */
    fun openBackup(uri: Uri) {
        if (_data.value.busy != null) return
        viewModelScope.launch {
            _data.update { it.copy(busy = DataBusy.READING) }
            try {
                val preview = backup.preview(uri)
                pending = preview
                val prompt = RestorePrompt(preview.createdAt, preview.counts, backup.currentCounts())
                _data.update { it.copy(prompt = prompt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(DataEvent.Failed((e as? BackupException)?.error ?: BackupError.CANNOT_OPEN))
            } finally {
                _data.update { it.copy(busy = null) }
            }
        }
    }

    /** "Replace": applies the pending backup. */
    fun confirmRestore() {
        val preview = pending ?: return
        if (_data.value.busy != null) return
        pending = null
        viewModelScope.launch {
            _data.update { DataUiState(busy = DataBusy.RESTORING) }
            try {
                _events.send(DataEvent.Restored(backup.restore(preview)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(DataEvent.Failed((e as? BackupException)?.error ?: BackupError.RESTORE_FAILED))
            } finally {
                _data.update { it.copy(busy = null) }
            }
        }
    }

    /** "Delete all data", after the typed confirmation. Ignored while another operation runs. */
    fun deleteAll() {
        if (_data.value.busy != null) return
        viewModelScope.launch {
            _data.update { it.copy(busy = DataBusy.DELETING) }
            try {
                backup.deleteAll()
                _events.send(DataEvent.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(DataEvent.Failed((e as? BackupException)?.error ?: BackupError.DELETE_FAILED))
            } finally {
                _data.update { it.copy(busy = null) }
            }
        }
    }

    /** "Cancel": forgets the pending backup. */
    fun cancelRestore() {
        pending = null
        _data.update { it.copy(prompt = null) }
    }
}
