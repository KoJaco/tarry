package me.tarry.app.summary

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tarry.app.export.summary.SummaryExportFormat
import me.tarry.app.export.summary.SummaryExporter
import me.tarry.app.export.summary.SummarySnapshot
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
    /** A file is being written; the export button shows progress and is disabled. */
    val exporting: Boolean = false,
)

/** One-shot outcome of an export, shown as a snackbar. */
sealed interface SummaryExportEvent {
    data class Saved(val format: SummaryExportFormat, val blocks: Int) : SummaryExportEvent
    data object Failed : SummaryExportEvent
}

/** How long an export waits for the first result of the range (the view model was just recreated) before it gives up. */
internal const val EXPORT_WAIT_TIMEOUT_MS = 10_000L

private data class Computed(
    val range: SummaryRange,
    val result: SummaryResult,
    // What the PDF needs beyond the result: the rules in words, and the visits behind "places visited".
    val rules: BillingRules,
    val visits: List<Visit>,
    val places: List<Place>,
)

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
    /** Writes the snapshot to the document and returns the number of blocks in it; throws on failure. */
    private val exportFile: suspend (SummaryExportFormat, SummarySnapshot, Uri) -> Int,
    /** Deletes the empty document the file picker created for an export that will not happen; never throws. */
    private val discardFile: suspend (Uri) -> Unit,
) : ViewModel() {
    @Inject
    constructor(
        savedState: SavedStateHandle,
        blocks: JobBlockRepository,
        timeline: TimelineRepository,
        jobs: JobRepository,
        places: PlaceRepository,
        settings: SettingsRepository,
        exporter: SummaryExporter,
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
        exportFile = exporter::export,
        discardFile = exporter::discard,
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
            val rules = s.billingRules()
            Computed(
                range,
                calculateSummary(
                    SummaryInput(
                        range, zone, blocks, allJobs, visits, trips,
                        ignoredPlaceIds = allPlaces.filter { it.ignored }.map { it.id }.toSet(),
                        rules = rules,
                    ),
                ),
                rules, visits, allPlaces,
            )
        }
    }

    private val exporting = MutableStateFlow(false)

    // The result for the chosen range together with the inputs behind it (the PDF needs the rules, visits and places too).
    // Null until the calculation catches up with a new range. Both the screen and export are built from this one value.
    private val shown: StateFlow<Computed?> = combine(rangeAndPreset, computed) { (_, range), computed ->
        computed.takeIf { it.range == range }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    val state: StateFlow<SummaryUiState?> = combine(rangeAndPreset, grouping, shown, exporting) { (preset, range), grouping, shown, busy ->
        SummaryUiState(preset, range, grouping, zone, shown?.result, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    private val _events = Channel<SummaryExportEvent>(Channel.BUFFERED)
    val events: Flow<SummaryExportEvent> = _events.receiveAsFlow()

    /**
     * Writes the Summary as it is now (this range, this result) to [uri], the document the user picked, and the file
     * picker has already created it empty. If the view model was recreated meanwhile (process death) the result may not
     * be there yet: it is awaited for up to [EXPORT_WAIT_TIMEOUT_MS]. When it never comes, or has no job blocks, the
     * document is deleted and [SummaryExportEvent.Failed] is sent. A call while another export runs is ignored (its
     * document is deleted). The outcome arrives on [events].
     */
    fun export(format: SummaryExportFormat, uri: Uri) {
        if (!exporting.compareAndSet(expect = false, update = true)) {
            viewModelScope.launch { discardFile(uri) }
            return
        }
        viewModelScope.launch {
            try {
                val ready = withTimeoutOrNull(EXPORT_WAIT_TIMEOUT_MS) { shown.filterNotNull().first() }
                if (ready == null || !ready.result.hasBlocks) {
                    discardFile(uri)
                    _events.send(SummaryExportEvent.Failed)
                } else {
                    val snapshot = SummarySnapshot(ready.range, zone, ready.result, ready.rules, ready.visits, ready.places)
                    _events.send(SummaryExportEvent.Saved(format, exportFile(format, snapshot, uri)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SummaryExportEvent.Failed)
            } finally {
                exporting.value = false
            }
        }
    }

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
