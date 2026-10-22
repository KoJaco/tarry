package me.tarry.app.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.R
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.TimelineEditor
import me.tarry.segmentation.SegmentationParams
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** One row of the day list. Times are UTC epoch ms; the UI formats them in the system zone. */
sealed interface TimelineItem {
    val id: String
    val start: Long
    val end: Long

    /** [placeLabel] is null when the visit has no (known) place. */
    data class Visit(
        override val id: String,
        override val start: Long,
        override val end: Long,
        val placeLabel: String?,
        /** True when [placeLabel] is only the raw coordinates, so it should be spoken as an unknown place. */
        val labelIsCoordinates: Boolean = false,
        /**
         * Name and colour of the visit's resolved job (its own job, else its place's default job); both null when
         * neither resolves or the job is unknown or deleted (deleted jobs are deliberately not shown).
         */
        val jobName: String? = null,
        val jobColorArgb: Int? = null,
        /** Id of that job; seeds the fallback colour when [jobColorArgb] names no palette slot. Null with [jobName]. */
        val jobId: String? = null,
        val placeId: String? = null,
        /** The user has edited or annotated this visit; segmentation never touches it. */
        val frozen: Boolean = false,
        val billable: Boolean? = null,
        val origin: VisitOrigin = VisitOrigin.DETECTED,
        /** True when the resolved job came from the place's default, not from the visit itself. */
        val jobInherited: Boolean = false,
        /** The visit's place is ignored, so the visit never asks for review. */
        val placeIgnored: Boolean = false,
    ) : TimelineItem {
        /** A visit with no resolved job, at a place that is not ignored. */
        val needsReview: Boolean get() = jobName == null && !placeIgnored
    }

    data class Trip(
        override val id: String,
        override val start: Long,
        override val end: Long,
        val distanceM: Float,
        val mode: TripMode,
    ) : TimelineItem

    data class Gap(
        override val id: String,
        override val start: Long,
        override val end: Long,
        val cause: GapCause,
    ) : TimelineItem
}

/** `[date 00:00, date+1 00:00)` in [zone], as epoch ms. A DST day is 23 or 25 hours long. */
fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
    date.atStartOfDay(zone).toInstant().toEpochMilli() to
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Place name, else a non-blank address, else "lat, lng" to 5 decimal places.
 * An empty address means the geocoder looked and found nothing, so it is skipped like null.
 */
fun Place.displayLabel(): String =
    name?.takeIf { it.isNotBlank() }
        ?: address?.takeIf { it.isNotBlank() }
        ?: String.format(Locale.US, "%.5f, %.5f", centroidLat, centroidLng)

/** True when [displayLabel] would fall back to raw coordinates. */
fun Place.labelIsCoordinates(): Boolean = name.isNullOrBlank() && address.isNullOrBlank()

/** gap < visit < trip when rows start at the same time. */
private fun TimelineItem.typeOrder(): Int = when (this) {
    is TimelineItem.Gap -> 0
    is TimelineItem.Visit -> 1
    is TimelineItem.Trip -> 2
}

private const val DEFAULT_MIN_STAY_MS = 5 * 60_000L

private val RowOrder = compareBy<TimelineItem>({ it.start }, { it.typeOrder() })

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel internal constructor(
    timeline: TimelineRepository,
    places: PlaceRepository,
    jobs: JobRepository,
    zone: ZoneId,
    private val todayProvider: () -> LocalDate,
    private val resize: suspend (id: String, start: Long, end: Long) -> EditResult = { _, _, _ -> EditResult.NotFound },
    private val offerUndo: (UndoEvent) -> Unit = {},
    private val onEdited: () -> Unit = {},
    /** Shortest a visit may be resized to; `SegmentationParams.minStayDurationMs`. */
    val minStayMs: Long = DEFAULT_MIN_STAY_MS,
) : ViewModel() {
    @Inject
    constructor(
        timeline: TimelineRepository,
        places: PlaceRepository,
        jobs: JobRepository,
        editor: TimelineEditor,
        undoEvents: UndoEvents,
        scheduler: SegmentationScheduler,
        params: SegmentationParams,
    ) :
        this(
            timeline, places, jobs, ZoneId.systemDefault(), { LocalDate.now(ZoneId.systemDefault()) },
            { id, start, end -> editor.resize(id, start, end, params.minStayDurationMs) },
            undoEvents::offer, scheduler::enqueueNow, params.minStayDurationMs,
        )

    private val _today = MutableStateFlow(todayProvider())

    /** The current date; changes only through [refreshToday]. The selected date can never be after it. */
    val today: StateFlow<LocalDate> = _today

    private val _selectedDate = MutableStateFlow(_today.value)
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    /** Null until the first emission for the selected date (loading); an empty list is a loaded, empty day. */
    val items: StateFlow<List<TimelineItem>?> = _selectedDate
        .flatMapLatest { date ->
            val (from, to) = dayBounds(date, zone)
            flow {
                emit(null)
                emitAll(
                    combine(
                        timeline.observeVisits(from, to),
                        timeline.observeTrips(from, to),
                        timeline.observeGaps(from, to),
                        places.observeAll(),
                        jobs.observeAll(), // excludes deleted jobs
                    ) { visits, trips, gaps, allPlaces, allJobs ->
                        val byId = allPlaces.associateBy { it.id }
                        val jobsById = allJobs.associateBy { it.id }
                        val rows: List<TimelineItem> = visits.map {
                            val place = it.placeId?.let(byId::get)
                            val own = it.jobId?.let(jobsById::get)
                            val job = own ?: place?.defaultJobId?.let(jobsById::get)
                            TimelineItem.Visit(
                                it.id, it.start, it.end, place?.displayLabel(), place?.labelIsCoordinates() ?: false,
                                job?.name, job?.colorArgb, job?.id,
                                placeId = it.placeId, frozen = it.frozen, billable = it.billable, origin = it.origin,
                                jobInherited = own == null && job != null,
                                placeIgnored = place?.ignored ?: false,
                            )
                        } +
                            trips.map { TimelineItem.Trip(it.id, it.start, it.end, it.distanceM, it.mode) } +
                            gaps.map { TimelineItem.Gap(it.id, it.start, it.end, it.cause) }
                        rows.sortedWith(RowOrder)
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /**
     * Re-derives today (call on resume). If the user was looking at the old today, the selection follows to the new
     * one; a day they navigated to stays.
     */
    fun refreshToday() {
        val now = todayProvider()
        val old = _today.value
        if (now == old) return
        _today.value = now
        if (_selectedDate.value == old) _selectedDate.value = now
    }

    fun previousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun nextDay() {
        if (_selectedDate.value < _today.value) _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    /** Jumps to [date] (week strip, date picker). A date after today is clamped to today. */
    fun selectDate(date: LocalDate) {
        _selectedDate.value = if (date > _today.value) _today.value else date
    }

    /**
     * A visit edge was dragged and released (phase 3 R2). Trims neighbours in one edit, offers "Visit times changed"
     * with Undo, and re-runs segmentation over the affected range.
     */
    fun resizeVisit(id: String, start: Long, end: Long) {
        viewModelScope.launch {
            val result = resize(id, start, end)
            if (result is EditResult.Ok) {
                result.undo?.let { offerUndo(UndoEvent(R.string.timeline_undo_visit_times, it)) }
                onEdited()
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
