package me.tarry.app.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.ui.timeline.dayBounds
import me.tarry.app.ui.timeline.observeNeedsReviewCount
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineRepository
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/** What the shell needs besides tracking state: today's needs-review count (nav badge) and the undo snackbar feed. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ShellStatusViewModel internal constructor(
    timeline: TimelineRepository,
    places: PlaceRepository,
    jobs: JobRepository,
    zone: ZoneId,
    today: Flow<LocalDate>,
    val undoEvents: SharedFlow<UndoEvent>,
    private val performUndo: suspend (UndoEvent) -> Unit,
) : ViewModel() {
    @Inject
    constructor(timeline: TimelineRepository, places: PlaceRepository, jobs: JobRepository, undo: UndoEvents) :
        this(timeline, places, jobs, ZoneId.systemDefault(), todayFlow(ZoneId.systemDefault()), undo.events, undo::undo)

    /** Visits today that need review. */
    val needsReviewToday: StateFlow<Int> = today
        .flatMapLatest { date ->
            val (from, to) = dayBounds(date, zone)
            observeNeedsReviewCount(timeline, places, jobs, from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), 0)

    fun undo(event: UndoEvent) {
        viewModelScope.launch { performUndo(event) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/** Today's date in [zone], re-emitted just after each midnight while collected. UI-only; nothing is scheduled. */
internal fun todayFlow(zone: ZoneId): Flow<LocalDate> = flow {
    while (true) {
        val now = ZonedDateTime.now(zone)
        emit(now.toLocalDate())
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        delay(Duration.between(now, nextMidnight).toMillis() + 1_000L)
    }
}
