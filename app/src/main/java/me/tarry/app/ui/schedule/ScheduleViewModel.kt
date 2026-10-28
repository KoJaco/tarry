package me.tarry.app.ui.schedule

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.ScheduleChange
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.repository.ScheduleRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** Which schedule the editor changes: the every-week template, or one week's own schedule. */
enum class ScheduleMode { TEMPLATE, WEEK }

/** A mode or week change held back until the user has agreed to drop unsaved edits. */
sealed interface PendingNavigation {
    data class ToMode(val mode: ScheduleMode) : PendingNavigation
    data class ShiftWeek(val weeks: Int) : PendingNavigation
}

@Immutable
data class ScheduleUiState(
    val loaded: Boolean,
    val mode: ScheduleMode,
    /** Monday of the week shown in [ScheduleMode.WEEK] (never before the current week). */
    val viewedWeek: LocalDate,
    val currentWeek: LocalDate,
    val today: LocalDate,
    /** What is stored for the shown schedule. */
    val saved: Schedule,
    /** What the grid shows; differs from [saved] while there are unsaved edits. */
    val draft: Schedule,
    /** The viewed week has its own schedule (only meaningful in [ScheduleMode.WEEK]). */
    val hasOverride: Boolean,
    val currentWeekHasOverride: Boolean,
    val pending: PendingNavigation? = null,
    val saving: Boolean = false,
    /** The last save failed; the draft is kept so nothing is lost. Cleared by the next edit or save. */
    val saveFailed: Boolean = false,
    /** The mode and week whose stored schedule [saved] holds; lets callers tell when a reload has landed. */
    val loadedFor: LoadedFor? = null,
) {
    val dirty: Boolean get() = draft != saved
    val canGoPrevious: Boolean get() = viewedWeek.isAfter(currentWeek)
}

/** The mode and Monday a load result belongs to. */
data class LoadedFor(val mode: ScheduleMode, val week: LocalDate)

/** Monday of the ISO week containing [date]. */
internal fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * Backs the Schedule screen. Edits go to a draft; Save writes through [TrackingController.updateSchedule] (one re-arm and
 * reconcile) on the capture scope, so it finishes even if the user leaves right after tapping.
 */
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val schedules: ScheduleRepository,
    private val controller: TrackingController,
    private val trackingState: TrackingStateRepository,
    private val clock: TrackingClock,
    @param:CaptureScope private val captureScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    private fun initialState(): ScheduleUiState {
        val today = clock.today()
        val week = mondayOf(today)
        return ScheduleUiState(
            loaded = false, mode = ScheduleMode.TEMPLATE, viewedWeek = week, currentWeek = week, today = today,
            saved = Schedule.MON_FRI_7_TO_17, draft = Schedule.MON_FRI_7_TO_17, hasOverride = false, currentWeekHasOverride = false,
        )
    }

    /** Reads the stored schedules for the current mode and viewed week; the draft restarts from them. */
    private fun load() {
        loadJob?.cancel()
        val (mode, week) = _state.value.let { it.mode to it.viewedWeek }
        loadJob = viewModelScope.launch { reload(mode, week) }
    }

    /** Loads [mode] / [week] and applies the result only if the view is still on them, so navigation is never clobbered. */
    private suspend fun reload(mode: ScheduleMode, week: LocalDate) {
        val today = clock.today()
        val currentWeek = mondayOf(today)
        // A week change since the screen opened must not leave the view in the past.
        val viewed = if (week.isBefore(currentWeek)) currentWeek else week
        val template = schedules.template() ?: Schedule.MON_FRI_7_TO_17
        val viewedOverride = schedules.override(viewed.toString())
        val currentOverride = if (viewed == currentWeek) viewedOverride else schedules.override(currentWeek.toString())
        val shown = if (mode == ScheduleMode.TEMPLATE) template else viewedOverride ?: template
        _state.update {
            if (it.mode != mode || it.viewedWeek != week) {
                it
            } else {
                it.copy(
                    loaded = true, viewedWeek = viewed, currentWeek = currentWeek, today = today, saved = shown, draft = shown,
                    hasOverride = viewedOverride != null, currentWeekHasOverride = currentOverride != null, pending = null,
                    saving = false, saveFailed = false, loadedFor = LoadedFor(mode, viewed),
                )
            }
        }
    }

    fun toggleCell(day: DayOfWeek, slot: Int) = edit { toggle(it, day, slot) }

    /** A paint stroke in progress or finished; [schedule] is the whole draft. */
    fun setDraft(schedule: Schedule) = edit { schedule }

    fun clearAll() = edit { Schedule.EMPTY }

    /** Makes [day] active from [startSlot] up to [endSlotExclusive] (48 = midnight) and everything else on it inactive. */
    fun setDayHours(day: DayOfWeek, startSlot: Int, endSlotExclusive: Int) = edit {
        it.clearDay(day).withSlots(day, startSlot until endSlotExclusive, true)
    }

    fun copyDay(from: DayOfWeek, to: Set<DayOfWeek>) = edit { it.copyDay(from, to - from) }

    fun clearDay(day: DayOfWeek) = edit { it.clearDay(day) }

    private fun edit(change: (Schedule) -> Schedule) {
        _state.update { if (it.saving) it else it.copy(draft = change(it.draft), saveFailed = false) }
    }

    fun discard() {
        _state.update { if (it.saving) it else it.copy(draft = it.saved, saveFailed = false) }
    }

    fun save() {
        val s = _state.value
        if (!s.dirty || s.saving) return
        val change = when (s.mode) {
            ScheduleMode.TEMPLATE -> ScheduleChange.SaveTemplate(s.draft)
            ScheduleMode.WEEK -> ScheduleChange.SaveWeek(s.viewedWeek, s.draft)
        }
        apply(change)
    }

    /** Drops the viewed week's own schedule so the every-week schedule applies again. */
    fun useEveryWeekSchedule() {
        val s = _state.value
        if (s.mode != ScheduleMode.WEEK || !s.hasOverride || s.saving) return
        apply(ScheduleChange.ClearWeek(s.viewedWeek))
    }

    private fun apply(change: ScheduleChange) {
        _state.update { it.copy(saving = true, saveFailed = false) }
        captureScope.launch {
            try {
                controller.updateSchedule(change)
                trackingState.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the draft (still dirty) and say so; the user can retry.
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            load()
        }
    }

    fun setMode(mode: ScheduleMode) {
        val s = _state.value
        if (mode == s.mode || s.saving) return
        if (s.dirty) _state.update { it.copy(pending = PendingNavigation.ToMode(mode)) } else goTo(PendingNavigation.ToMode(mode))
    }

    fun shiftWeek(weeks: Int) {
        val s = _state.value
        if (s.saving || (weeks < 0 && !s.canGoPrevious)) return
        if (s.dirty) _state.update { it.copy(pending = PendingNavigation.ShiftWeek(weeks)) } else goTo(PendingNavigation.ShiftWeek(weeks))
    }

    /** The user agreed to drop unsaved edits for the held-back change. */
    fun confirmPending() {
        val s = _state.value
        if (s.saving) return
        goTo(s.pending ?: return)
    }

    fun cancelPending() {
        _state.update { it.copy(pending = null) }
    }

    private fun goTo(target: PendingNavigation) {
        _state.update {
            when (target) {
                is PendingNavigation.ToMode -> it.copy(mode = target.mode, pending = null)
                is PendingNavigation.ShiftWeek -> it.copy(viewedWeek = maxOf(it.currentWeek, it.viewedWeek.plusWeeks(target.weeks.toLong())), pending = null)
            }
        }
        load()
    }
}

private fun TrackingClock.today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate()
