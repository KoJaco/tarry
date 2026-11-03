package me.tarry.app.summary

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.repository.JobBlockRepository
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineRepository
import me.tarry.core.data.settings.Settings
import me.tarry.core.data.settings.SettingsRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** The range chips. Weeks run Monday to Sunday, as in the schedule. */
enum class RangePreset { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, CUSTOM }

enum class SummaryGrouping { BY_JOB, BY_DAY }

/** The preset's inclusive range for [today]; CUSTOM has none (it carries its own dates). */
fun RangePreset.rangeFor(today: LocalDate): SummaryRange? {
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return when (this) {
        RangePreset.TODAY -> SummaryRange(today, today)
        RangePreset.YESTERDAY -> today.minusDays(1).let { SummaryRange(it, it) }
        RangePreset.THIS_WEEK -> SummaryRange(monday, monday.plusDays(6))
        RangePreset.LAST_WEEK -> SummaryRange(monday.minusDays(7), monday.minusDays(1))
        RangePreset.CUSTOM -> null
    }
}

/** What the screen shows. [result] is null until the first calculation for [range] finishes. */
@Immutable
data class SummaryUiState(
    val preset: RangePreset,
    val range: SummaryRange,
    val grouping: SummaryGrouping,
    val zone: ZoneId,
    val result: SummaryResult?,
)

private data class Computed(val range: SummaryRange, val result: SummaryResult)

/**
 * Backs the Summary tab: the chosen range and grouping (kept in [SavedStateHandle], so they survive rotation and process
 * death) feed the Room flows into [calculateSummary]. Any change to a block, job, visit, trip, place or billing setting
 * re-emits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SummaryViewModel internal constructor(
    private val savedState: SavedStateHandle,
    private val blocksOverlapping: (from: Long, to: Long) -> Flow<List<JobBlock>>,
    private val visitsIn: (from: Long, to: Long) -> Flow<List<Visit>>,
    private val tripsIn: (from: Long, to: Long) -> Flow<List<Trip>>,
    private val jobs: Flow<List<Job>>,
    private val places: Flow<List<Place>>,
    private val settings: Flow<Settings>,
    private val zone: ZoneId,
    private val todayProvider: () -> LocalDate,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle,
        blocks: JobBlockRepository,
        timeline: TimelineRepository,
        jobs: JobRepository,
        places: PlaceRepository,
        settings: SettingsRepository,
    ) : this(
        savedState,
        blocksOverlapping = blocks::observeOverlapping,
        visitsIn = timeline::observeVisits,
        tripsIn = timeline::observeTrips,
        jobs = jobs.observeAll(),
        places = places.observeLabels(), // includes deleted places, like the timeline: visits there keep their ignored flag
        settings = settings.settings,
        zone = ZoneId.systemDefault(),
        todayProvider = { LocalDate.now(ZoneId.systemDefault()) },
    )

    private val today = MutableStateFlow(todayProvider())

    /** Re-derives today (call on resume), so "Today" and "This week" follow midnight. */
    fun refreshToday() {
        today.value = todayProvider()
    }

    private val presetFlow = savedState.getStateFlow(KEY_PRESET, RangePreset.TODAY.name)
    // One entry ("fromEpochDay,toEpochDay"), so a new custom range is never seen half written.
    private val customRange = savedState.getStateFlow<String?>(KEY_CUSTOM_RANGE, null)
    private val groupingFlow = savedState.getStateFlow(KEY_GROUPING, SummaryGrouping.BY_JOB.name)

    private val preset: Flow<RangePreset> = presetFlow.map { name -> RangePreset.entries.firstOrNull { it.name == name } ?: RangePreset.TODAY }
    private val grouping: Flow<SummaryGrouping> =
        groupingFlow.map { name -> SummaryGrouping.entries.firstOrNull { it.name == name } ?: SummaryGrouping.BY_JOB }

    private val rangeAndPreset: Flow<Pair<RangePreset, SummaryRange>> =
        combine(preset, customRange, today) { preset, stored, today ->
            val custom = parseCustom(stored)
            // A custom preset without (valid) dates falls back to today rather than showing nothing.
            if (preset == RangePreset.CUSTOM && custom != null) preset to custom else preset.rangeFor(today)?.let { preset to it } ?: (RangePreset.TODAY to SummaryRange(today, today))
        }.distinctUntilChanged()

    private val computed: Flow<Computed> = rangeAndPreset.map { it.second }.distinctUntilChanged().flatMapLatest { range ->
        val (from, to) = range.bounds(zone)
        // combine() takes five flows at most: jobs, places and settings travel together.
        val catalog = combine(jobs, places, settings, ::Triple)
        combine(blocksOverlapping(from, to), visitsIn(from, to), tripsIn(from, to), catalog) { blocks, visits, trips, (allJobs, allPlaces, s) ->
            Computed(
                range,
                calculateSummary(
                    SummaryInput(
                        range, zone, blocks, allJobs, visits, trips,
                        ignoredPlaceIds = allPlaces.filter { it.ignored }.map { it.id }.toSet(),
                        rules = s.billingRules(),
                    ),
                ),
            )
        }
    }

    val state: StateFlow<SummaryUiState?> = combine(rangeAndPreset, grouping, computed) { (preset, range), grouping, computed ->
        // The result belongs to the range it was computed for; until it catches up the new range shows as loading.
        SummaryUiState(preset, range, grouping, zone, computed.result.takeIf { computed.range == range })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    fun selectPreset(preset: RangePreset) {
        // CUSTOM needs dates: use setCustomRange. Selecting it again without a stored range is a no-op.
        if (preset == RangePreset.CUSTOM && savedState.get<String?>(KEY_CUSTOM_RANGE) == null) return
        savedState[KEY_PRESET] = preset.name
    }

    /** Sets and selects a custom range; the dates are swapped if given the wrong way round. */
    fun setCustomRange(from: LocalDate, to: LocalDate) {
        val (a, b) = if (to.isBefore(from)) to to from else from to to
        savedState[KEY_CUSTOM_RANGE] = "${a.toEpochDay()},${b.toEpochDay()}"
        savedState[KEY_PRESET] = RangePreset.CUSTOM.name
    }

    fun setGrouping(grouping: SummaryGrouping) {
        savedState[KEY_GROUPING] = grouping.name
    }

    private fun parseCustom(stored: String?): SummaryRange? {
        val parts = stored?.split(",")?.map { it.toLongOrNull() ?: return null } ?: return null
        if (parts.size != 2 || parts[0] > parts[1]) return null
        return SummaryRange(LocalDate.ofEpochDay(parts[0]), LocalDate.ofEpochDay(parts[1]))
    }

    private companion object {
        const val KEY_PRESET = "summary.preset"
        const val KEY_CUSTOM_RANGE = "summary.customRange"
        const val KEY_GROUPING = "summary.grouping"
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
