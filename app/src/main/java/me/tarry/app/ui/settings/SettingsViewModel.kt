package me.tarry.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.ui.schedule.WeekScheduleSummary
import me.tarry.app.ui.schedule.mondayOf
import me.tarry.app.ui.schedule.summarize
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.core.data.settings.ThemeMode
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    schedules: ScheduleRepository,
    clock: TrackingClock,
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

    /** Applies from the next stop; a stop already being tracked keeps its radius. */
    fun setStayGeofenceRadiusM(meters: Int) {
        viewModelScope.launch { repository.setStayGeofenceRadiusM(meters) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { repository.setTheme(mode) }
    }
}
