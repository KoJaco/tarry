package me.tarry.app.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel internal constructor(
    timeline: TimelineRepository,
    places: PlaceRepository,
    zone: ZoneId,
    initialDate: LocalDate,
) : ViewModel() {
    @Inject
    constructor(timeline: TimelineRepository, places: PlaceRepository) :
        this(timeline, places, ZoneId.systemDefault(), LocalDate.now(ZoneId.systemDefault()))

    private val _selectedDate = MutableStateFlow(initialDate)
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    val items: StateFlow<List<TimelineItem>> = _selectedDate
        .flatMapLatest { date ->
            val (from, to) = dayBounds(date, zone)
            combine(
                timeline.observeVisits(from, to),
                timeline.observeTrips(from, to),
                timeline.observeGaps(from, to),
                places.observeAll(),
            ) { visits, trips, gaps, allPlaces ->
                val byId = allPlaces.associateBy { it.id }
                val rows: List<TimelineItem> =
                    visits.map { TimelineItem.Visit(it.id, it.start, it.end, it.placeId?.let(byId::get)?.displayLabel()) } +
                        trips.map { TimelineItem.Trip(it.id, it.start, it.end, it.distanceM, it.mode) } +
                        gaps.map { TimelineItem.Gap(it.id, it.start, it.end, it.cause) }
                rows.sortedBy { it.start }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    fun previousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun nextDay() {
        _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
