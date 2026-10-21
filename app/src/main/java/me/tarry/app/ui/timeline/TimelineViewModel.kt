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
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
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
         * Name and colour of the visit's job; both null when the visit has no job or its job is unknown or deleted
         * (deleted jobs are deliberately not shown).
         */
        val jobName: String? = null,
        val jobColorArgb: Int? = null,
    ) : TimelineItem

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

private val RowOrder = compareBy<TimelineItem>({ it.start }, { it.typeOrder() })

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel internal constructor(
    timeline: TimelineRepository,
    places: PlaceRepository,
    jobs: JobRepository,
    zone: ZoneId,
    private val todayProvider: () -> LocalDate,
) : ViewModel() {
    @Inject
    constructor(timeline: TimelineRepository, places: PlaceRepository, jobs: JobRepository) :
        this(timeline, places, jobs, ZoneId.systemDefault(), { LocalDate.now(ZoneId.systemDefault()) })

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
                            val job = it.jobId?.let(jobsById::get)
                            TimelineItem.Visit(
                                it.id, it.start, it.end, place?.displayLabel(), place?.labelIsCoordinates() ?: false,
                                job?.name, job?.colorArgb,
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

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
