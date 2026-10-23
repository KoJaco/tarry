package me.tarry.app.ui.places

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tarry.app.R
import me.tarry.app.segmentation.PlaceRadiusPreviewLoader
import me.tarry.app.segmentation.RadiusPreviewInput
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.ui.drawer.JobOption
import me.tarry.app.ui.drawer.PlaceOption
import me.tarry.app.ui.drawer.TagGroupOption
import me.tarry.app.ui.drawer.TagOption
import me.tarry.app.ui.timeline.displayLabel
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.app.ui.undo.noticeRes
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.MergePreview
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TagRepository
import me.tarry.core.data.repository.TimelineEditor
import me.tarry.core.data.repository.TimelineRepository
import javax.inject.Inject

/** Everything the detail screen shows. Pickers already hide archived jobs and tags unless they are the current value. */
@Immutable
data class PlaceDetailUi(
    val id: String,
    val label: String,
    val name: String?,
    val address: String?,
    val visitCount: Int,
    val defaultJob: JobOption?,
    val pickableJobs: List<JobOption>,
    val defaultBillable: Boolean?,
    val tagIds: Set<String>,
    val tagNames: List<String>,
    val pickableTagGroups: List<TagGroupOption>,
    val ignored: Boolean,
    val radiusM: Float,
    /** Every other live place, for "Merge into...". */
    val otherPlaces: List<PlaceOption>,
)

sealed interface PlaceDetailLoad {
    data object Loading : PlaceDetailLoad

    /** The place was deleted, pruned or merged away: the screen closes itself. */
    data object Missing : PlaceDetailLoad
    data class Ready(val ui: PlaceDetailUi) : PlaceDetailLoad
}

private class Catalog(val jobs: List<Job>, val groups: List<TagGroup>, val tags: List<Tag>)

private class Inputs(val id: String?, val all: List<Place>, val catalog: Catalog, val counts: Map<String, Int>)

/**
 * Backs one place's detail screen. Not a navigation arg: the host calls [load] with the route's id. Every structural
 * edit (radius, merge, delete) offers an undo and asks segmentation to re-run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaceDetailViewModel @Inject constructor(
    private val places: PlaceRepository,
    private val timeline: TimelineRepository,
    jobs: JobRepository,
    tags: TagRepository,
    private val editor: TimelineEditor,
    private val preview: PlaceRadiusPreviewLoader,
    private val undoEvents: UndoEvents,
    private val scheduler: SegmentationScheduler,
) : ViewModel() {
    private val placeId = MutableStateFlow<String?>(null)
    private val defaultsLock = Mutex()

    private val catalog = combine(jobs.observeAll(), tags.observeGroups(), tags.observeTags(), ::Catalog)

    val state: StateFlow<PlaceDetailLoad> =
        combine(placeId, places.observeAll(), catalog, timeline.observePlaceVisitCounts(), ::Inputs)
            .mapLatest { build(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), PlaceDetailLoad.Loading)

    fun load(id: String) {
        placeId.value = id
    }

    private suspend fun build(i: Inputs): PlaceDetailLoad {
        val id = i.id ?: return PlaceDetailLoad.Loading
        val place = i.all.firstOrNull { it.id == id && it.mergedIntoId == null } ?: return PlaceDetailLoad.Missing
        val tagIds = places.defaultTags(id).map { it.tagId }.toSet()
        val archivedTags = i.catalog.tags.filter { it.archived }.map { it.id }.toSet()
        val tagsByGroup = i.catalog.tags.groupBy { it.groupId }
        val jobOptions = i.catalog.jobs.filter { !it.archived || it.id == place.defaultJobId }.map { JobOption(it.id, it.name, it.colorArgb) }
        val groups = i.catalog.groups.map { g ->
            TagGroupOption(g.id, g.name, tagsByGroup[g.id].orEmpty().filter { it.id !in archivedTags || it.id in tagIds }.map { TagOption(it.id, it.name) })
        }.filter { it.tags.isNotEmpty() }
        return PlaceDetailLoad.Ready(
            PlaceDetailUi(
                id = id, label = place.displayLabel(), name = place.name?.takeIf { it.isNotBlank() },
                address = place.address?.takeIf { it.isNotBlank() }, visitCount = i.counts[id] ?: 0,
                defaultJob = jobOptions.firstOrNull { it.id == place.defaultJobId }, pickableJobs = jobOptions,
                defaultBillable = place.defaultBillable, tagIds = tagIds,
                tagNames = i.catalog.tags.filter { it.id in tagIds }.map { it.name },
                pickableTagGroups = groups, ignored = place.ignored, radiusM = place.radiusM,
                otherPlaces = i.all.filter { it.id != id && it.mergedIntoId == null }
                    .map { PlaceOption(it.id, it.displayLabel(), it.address?.takeIf { a -> a.isNotBlank() }) },
            ),
        )
    }

    /** A blank [name] clears it, so the address label shows again. */
    fun rename(name: String) = viewModelScope.launch {
        val id = placeId.value ?: return@launch
        report(editor.renamePlace(id, name))
    }

    fun setDefaultJob(jobId: String?) = writeDefaults { _, billable, tagIds -> Defaults(jobId, billable, tagIds) }

    fun setDefaultBillable(billable: Boolean?) = writeDefaults { job, _, tagIds -> Defaults(job, billable, tagIds) }

    fun setDefaultTags(tagIds: Set<String>) = writeDefaults { job, billable, _ -> Defaults(job, billable, tagIds) }

    private data class Defaults(val jobId: String?, val billable: Boolean?, val tagIds: Set<String>)

    /** Re-reads the place's defaults, so a stale screen never overwrites a field it did not touch. */
    private fun writeDefaults(change: (String?, Boolean?, Set<String>) -> Defaults) = viewModelScope.launch {
        defaultsLock.withLock {
            val id = placeId.value ?: return@launch
            val place = places.place(id)?.takeUnless { it.deleted } ?: return@launch
            val next = change(place.defaultJobId, place.defaultBillable, places.defaultTags(id).map { it.tagId }.toSet())
            report(editor.setPlaceDefault(id, next.jobId, next.billable, next.tagIds))
        }
    }

    /** Sets `Place.ignored` directly, so it works for a place with no live visits too. */
    fun setIgnored(ignored: Boolean) = viewModelScope.launch {
        val id = placeId.value ?: return@launch
        report(editor.setPlaceIgnored(id, ignored))
    }

    /** The visits and pings the radius sheet counts from; null when the place is gone. */
    suspend fun radiusInput(): RadiusPreviewInput? = placeId.value?.let { preview.load(it) }

    fun setRadius(radiusM: Float) = durably { id -> structural(editor.setPlaceRadius(id, radiusM), R.string.places_undo_radius) }

    suspend fun previewMerge(targetId: String): MergePreview? = placeId.value?.let { editor.previewMerge(it, targetId) }

    fun merge(targetId: String) = durably { id -> structural(editor.mergePlaces(id, targetId), R.string.places_undo_merged) }

    /** The screen closes itself once the place leaves the live list. */
    fun delete() = durably { id -> structural(editor.deletePlace(id), R.string.places_undo_deleted) }

    /**
     * Structural edits must finish their undo offer and re-run request even if the screen is left right after the tap
     * (which clears this ViewModel and cancels [viewModelScope]), so they run non-cancellably. The coroutine starts
     * atomically so a tap followed at once by a clear is not dropped.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun durably(block: suspend (String) -> Unit) = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
        withContext(NonCancellable) { placeId.value?.let { block(it) } }
    }

    private fun structural(result: EditResult, @StringRes undoMessage: Int) {
        if (result is EditResult.Ok) {
            result.undo?.let {
                undoEvents.offer(UndoEvent(undoMessage, it))
                scheduler.enqueueNow()
            }
        } else {
            report(result)
        }
    }

    private fun report(result: EditResult) {
        result.noticeRes()?.let(undoEvents::notice)
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
