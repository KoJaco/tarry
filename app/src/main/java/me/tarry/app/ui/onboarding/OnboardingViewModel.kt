package me.tarry.app.ui.onboarding

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.ScheduleChange
import me.tarry.app.tracking.ScheduleWindowChecker
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.ui.permissions.PermissionChecker
import me.tarry.app.ui.permissions.PermissionsUiState
import me.tarry.app.ui.permissions.StepStatus
import me.tarry.app.ui.permissions.buildPermissionsUiState
import me.tarry.app.ui.schedule.ScheduleSaver
import me.tarry.app.ui.schedule.toggle
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.repository.ScheduleRepository
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Backs first-run onboarding. The saves and the reconcile run on the capture scope, so they finish even if the user
 * leaves right after tapping. Saving the hours is the step that moves to Done; reaching Done stamps the completion time
 * and the schedule save (which reconciles) starts tracking when the user is inside a window now.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    permissions: PermissionChecker,
    private val saver: ScheduleSaver,
    private val gate: OnboardingGate,
    private val scheduleRepo: ScheduleRepository,
    private val windows: ScheduleWindowChecker,
    private val clock: TrackingClock,
    private val config: TrackingConfig,
    @param:CaptureScope private val captureScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(
        OnboardingUiState(
            pages = onboardingPages(
                buildPermissionsUiState(permissions.check(), emptySet()).items.filter { it.status != StepStatus.GRANTED }.map { it.step },
            ),
        ),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    @Volatile private var edited = false

    init {
        // A stored template (a reinstall over kept data, or the one the app seeds early) is the starting grid, never the preset.
        captureScope.launch {
            val stored = try {
                scheduleRepo.template()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (stored != null) _state.update { if (edited || it.saving) it else it.copy(schedule = stored) }
        }
    }

    fun next() {
        _state.update { if (it.saving || it.index >= it.pages.lastIndex) it else it.copy(index = it.index + 1) }
    }

    /** Back skips permission pages that are already granted ([permissions] is the platform state now). */
    fun back(permissions: PermissionsUiState) {
        _state.update { if (it.canGoBack) it.copy(index = it.previousIndex(permissions)) else it }
    }

    fun toggleCell(day: DayOfWeek, slot: Int) = edit { toggle(it, day, slot) }

    /** A paint stroke in progress or finished; [schedule] is the whole grid. */
    fun setSchedule(schedule: Schedule) = edit { schedule }

    private fun edit(change: (Schedule) -> Schedule) {
        edited = true
        _state.update { if (it.saving) it else it.copy(schedule = change(it.schedule), saveFailed = false) }
    }

    /** Writes the template even when it is unchanged (a fresh install has none stored), then moves to Done. */
    fun saveHours() {
        val s = _state.value
        if (s.saving || s.page != OnboardingPage.Schedule) return
        _state.update { it.copy(saving = true, saveFailed = false) }
        captureScope.launch {
            try {
                saver.save(ScheduleChange.SaveTemplate(s.schedule))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            // The flag first: from here on onboarding is done even if the app is killed before "Open Tarry".
            runCatching { gate.markCompleted() }
            val now = Instant.ofEpochMilli(clock.nowMs())
            val outcome = runCatching {
                doneOutcome(
                    windows.activeIntervals(now, now.plus(Duration.ofDays(config.scheduleLookAheadDays))), now, clock.zone().id,
                )
            }.getOrDefault(DoneOutcome.NoHours)
            _state.update { it.copy(saving = false, index = it.pages.lastIndex, outcome = outcome) }
        }
    }

    /** "Not now" on the location page: the remaining permission pages are skipped; the user still sets their hours. */
    fun skipPermissions() {
        _state.update { if (it.saving || it.page !is OnboardingPage.Permission) it else it.copy(index = it.scheduleIndex) }
    }
}
