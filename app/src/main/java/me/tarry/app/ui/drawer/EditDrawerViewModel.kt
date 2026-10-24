package me.tarry.app.ui.drawer

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tarry.app.R
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.timeline.displayLabel
import me.tarry.app.ui.timeline.labelIsCoordinates
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.ItemRef
import me.tarry.core.data.repository.ItemType
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.MergeDirection
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TagRepository
import me.tarry.core.data.repository.TimelineEditor
import me.tarry.core.data.repository.TimelineRepository
import java.time.ZoneId
import javax.inject.Inject

/** One-shot instructions for the host: close the drawer, or swap what it shows. */
sealed interface EditDrawerEvent {
    data object Dismiss : EditDrawerEvent

    data class Retarget(val target: EditTarget) : EditDrawerEvent
}

private class Catalog(
    val jobs: List<JobOption>,
    val tagGroups: List<TagGroupOption>,
    val places: List<PlaceOption>,
    val archivedJobIds: Set<String>,
    val archivedTagIds: Set<String>,
) {
    /** Pickers hide archived jobs unless one is the item's current value ([keep]). Lookups by id still see every job. */
    fun pickableJobs(keep: Set<String?>): List<JobOption> = jobs.filter { it.id !in archivedJobIds || it.id in keep }

    /** Pickers hide archived tags unless already applied ([applied]); groups left empty drop out. */
    fun pickableTagGroups(applied: Set<String>): List<TagGroupOption> =
        tagGroups.map { g -> g.copy(tags = g.tags.filter { it.id !in archivedTagIds || it.id in applied }) }.filter { it.tags.isNotEmpty() }
}

private class Inputs(val target: EditTarget?, val error: EditError?, val catalog: Catalog)

/** The rows behind one visit or trip, read fresh from the database. */
private class ItemData(
    val visit: Visit?,
    val trip: Trip?,
    val place: Place?,
    val ownTags: Set<String>,
    val placeTags: Set<String>,
) {
    val frozen: Boolean get() = visit?.frozen ?: trip?.frozen ?: false

    /** Visits fall back to the place's default tags while they have none of their own and are not yet edited. */
    val tagsInherited: Boolean get() = visit != null && ownTags.isEmpty() && !visit.frozen && placeTags.isNotEmpty()
    val effectiveTags: Set<String> get() = if (tagsInherited) placeTags else ownTags
}

/**
 * Backs one [EditDrawer]. The drawer is not a navigation destination, so the host keys the instance by its target
 * (`hiltViewModel(key = ...)`) and calls [load]. Every annotation freezes the record (the editor does that); every
 * destructive edit offers an [UndoEvent] and asks segmentation to re-run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditDrawerViewModel @Inject constructor(
    private val timeline: TimelineRepository,
    private val places: PlaceRepository,
    jobs: JobRepository,
    tags: TagRepository,
    private val editor: TimelineEditor,
    private val undoEvents: UndoEvents,
    private val scheduler: SegmentationScheduler,
) : ViewModel() {
    private val target = MutableStateFlow<EditTarget?>(null)
    private val reload = MutableStateFlow(0)
    private val error = MutableStateFlow<EditError?>(null)
    private val eventChannel = Channel<EditDrawerEvent>(Channel.BUFFERED)

    val events: Flow<EditDrawerEvent> = eventChannel.receiveAsFlow()

    private val catalog: Flow<Catalog> = combine(
        jobs.observeAll(), tags.observeGroups(), tags.observeTags(), places.observeAll(),
    ) { allJobs, groups, allTags, allPlaces ->
        val tagsByGroup = allTags.groupBy { it.groupId }
        Catalog(
            jobs = allJobs.map { JobOption(it.id, it.name, it.colorArgb) },
            tagGroups = groups.map { g -> TagGroupOption(g.id, g.name, tagsByGroup[g.id].orEmpty().map { TagOption(it.id, it.name) }) }
                .filter { it.tags.isNotEmpty() },
            places = allPlaces.filter { it.mergedIntoId == null }.map { PlaceOption(it.id, it.displayLabel(), it.address?.takeIf { a -> a.isNotBlank() }) },
            archivedJobIds = allJobs.filter { it.archived }.map { it.id }.toSet(),
            archivedTagIds = allTags.filter { it.archived }.map { it.id }.toSet(),
        )
    }

    /** Null until [load] was called and the first read finished, or when the target no longer exists. */
    val state: StateFlow<EditDrawerState?> = combine(target, error, reload, catalog) { t, e, _, c -> Inputs(t, e, c) }
        .mapLatest { build(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    fun load(newTarget: EditTarget) {
        if (target.value == newTarget) return
        error.value = null
        target.value = newTarget
    }

    // region annotations

    fun setJob(jobId: String?) = viewModelScope.launch {
        val ref = ref() ?: return@launch
        finish(if (ref.type == ItemType.TRIP) editor.setAttribution(ref.id, jobId) else editor.setJob(ref, jobId))
    }

    fun createJob(name: String) = viewModelScope.launch {
        val created = editor.createJob(name, JobPalette.slots.map { it.key })
        val newId = (created as? EditResult.Ok)?.id
        val ref = ref()
        if (newId != null && ref != null) {
            finish(if (ref.type == ItemType.TRIP) editor.setAttribution(ref.id, newId) else editor.setJob(ref, newId))
        } else {
            finish(created)
        }
    }

    fun toggleTag(tagId: String) = viewModelScope.launch {
        val ref = ref() ?: return@launch
        val current = item()?.effectiveTags ?: return@launch
        finish(editor.setTags(ref, if (tagId in current) current - tagId else current + tagId))
    }

    fun createTag(name: String) = viewModelScope.launch {
        val created = editor.createTag(name)
        val newId = (created as? EditResult.Ok)?.id
        val ref = ref()
        val current = item()?.effectiveTags
        if (newId != null && ref != null && current != null) {
            finish(editor.setTags(ref, current + newId))
        } else {
            finish(created)
        }
    }

    fun setBillable(billable: Boolean) = viewModelScope.launch {
        val ref = ref() ?: return@launch
        finish(editor.setBillable(ref, billable))
    }

    fun setNotes(notes: String) = viewModelScope.launch {
        val ref = ref() ?: return@launch
        finish(editor.setNotes(ref, notes))
    }

    /** On: the place takes this visit's resolved job, billable state and tags as its defaults. Off: clears them. */
    fun setPlaceDefault(enabled: Boolean) = viewModelScope.launch {
        val data = item() ?: return@launch
        val place = data.place ?: return@launch
        val visit = data.visit ?: return@launch
        finish(
            if (enabled) {
                editor.setPlaceDefault(place.id, visit.jobId ?: place.defaultJobId, visit.billable ?: place.defaultBillable, data.effectiveTags)
            } else {
                editor.setPlaceDefault(place.id, null, null, emptySet())
            },
        )
    }

    /** Names the visit's place; a blank name clears it so the address label shows again. */
    fun renamePlace(name: String) = viewModelScope.launch {
        val place = item()?.place ?: return@launch
        finish(editor.renamePlace(place.id, name))
    }

    // endregion

    // region structural edits

    fun trimStart(newStart: Long) = structural(R.string.edit_undo_trimmed) { v -> editor.trim(v.id, newStart, v.end) }

    fun trimEnd(newEnd: Long) = structural(R.string.edit_undo_trimmed) { v -> editor.trim(v.id, v.start, newEnd) }

    fun split(at: Long) = structural(R.string.edit_undo_split) { v -> editor.split(v.id, at) }

    fun merge(direction: MergeDirection) = structural(R.string.edit_undo_merged, onOk = { ok ->
        // Merging into the previous visit keeps that visit's row, so the drawer follows it.
        val keptId = ok.id
        if (direction == MergeDirection.PREVIOUS && keptId != null) eventChannel.send(EditDrawerEvent.Retarget(EditTarget.Visit(keptId)))
    }) { v -> editor.merge(v.id, direction) }

    fun changePlace(placeId: String) = structural(R.string.edit_undo_place_changed) { v -> editor.reassignPlace(v.id, placeId) }

    fun delete() = structural(R.string.edit_undo_deleted, onOk = { eventChannel.send(EditDrawerEvent.Dismiss) }) { v -> editor.delete(v.id) }

    fun resetToDetected() = structural(R.string.edit_undo_reset) { v -> editor.resetToDetected(v.id) }

    private fun structural(
        @StringRes undoMessage: Int,
        onOk: suspend (EditResult.Ok) -> Unit = {},
        op: suspend (Visit) -> EditResult,
    ) = viewModelScope.launch {
        val visit = (target.value as? EditTarget.Visit)?.let { timeline.visit(it.id) }?.takeUnless { it.deleted } ?: return@launch
        val result = op(visit)
        if (result is EditResult.Ok) {
            result.undo?.let { undoEvents.offer(UndoEvent(undoMessage, it)) }
            scheduler.enqueueNow()
        }
        finish(result)
        if (result is EditResult.Ok) onOk(result)
    }

    // endregion

    private fun finish(result: EditResult) {
        error.value = result.toError()
        reload.update { it + 1 }
    }

    private fun ref(): ItemRef? = when (val t = target.value) {
        is EditTarget.Visit -> ItemRef.visit(t.id)
        is EditTarget.Trip -> ItemRef.trip(t.id)
        else -> null
    }

    private suspend fun item(): ItemData? = target.value?.let { loadItem(it) }

    private suspend fun loadItem(t: EditTarget): ItemData? = when (t) {
        is EditTarget.Visit -> timeline.visit(t.id)?.takeUnless { it.deleted }?.let { v ->
            val place = v.placeId?.let { places.place(it) }
            ItemData(
                visit = v, trip = null, place = place,
                ownTags = timeline.visitTags(v.id).map { it.tagId }.toSet(),
                placeTags = place?.let { p -> places.defaultTags(p.id).map { it.tagId }.toSet() }.orEmpty(),
            )
        }
        is EditTarget.Trip -> timeline.trip(t.id)?.takeUnless { it.deleted }?.let { trip ->
            ItemData(trip = trip, visit = null, place = null, ownTags = timeline.tripTags(trip.id).map { it.tagId }.toSet(), placeTags = emptySet())
        }
        is EditTarget.NewVisit -> null
    }

    private suspend fun build(inputs: Inputs): EditDrawerState? {
        val t = inputs.target ?: return null
        val catalog = inputs.catalog
        fun job(id: String?): JobOption? = id?.let { wanted -> catalog.jobs.firstOrNull { it.id == wanted } }
        if (t is EditTarget.NewVisit) {
            return EditDrawerState(
                kind = EditKind.NEW_VISIT, id = null, start = t.start, end = t.end, zoneId = ZoneId.systemDefault().id,
                jobs = catalog.pickableJobs(emptySet()), tagGroups = catalog.pickableTagGroups(emptySet()), places = catalog.places,
                error = inputs.error,
            )
        }
        val data = loadItem(t) ?: return null
        data.visit?.let { v ->
            val place = data.place
            val resolvedJobId = v.jobId ?: place?.defaultJobId
            val resolvedBillable = v.billable ?: place?.defaultBillable
            val isDefault = place != null &&
                (place.defaultJobId != null || place.defaultBillable != null || data.placeTags.isNotEmpty()) &&
                place.defaultJobId == resolvedJobId && place.defaultBillable == resolvedBillable && data.placeTags == data.effectiveTags
            return EditDrawerState(
                kind = EditKind.VISIT, id = v.id, start = v.start, end = v.end, zoneId = v.zoneId,
                placeLabel = place?.displayLabel(), placeLabelIsCoordinates = place?.labelIsCoordinates() ?: false,
                placeName = place?.name?.takeIf { it.isNotBlank() },
                hasPlace = place != null, placeIgnored = place?.ignored ?: false,
                jobs = catalog.pickableJobs(setOf(v.jobId, place?.defaultJobId)), tagGroups = catalog.pickableTagGroups(data.effectiveTags),
                places = catalog.places,
                ownJob = job(v.jobId), placeJob = job(place?.defaultJobId),
                billable = v.billable, placeBillable = place?.defaultBillable,
                tagIds = data.effectiveTags, tagsInherited = data.tagsInherited,
                notes = v.freeNotes, frozen = v.frozen, origin = v.origin, isPlaceDefault = isDefault, error = inputs.error,
            )
        }
        val trip = data.trip ?: return null
        return EditDrawerState(
            kind = EditKind.TRIP, id = trip.id, start = trip.start, end = trip.end, zoneId = trip.zoneId,
            tripMode = trip.mode, distanceM = trip.distanceM,
            jobs = catalog.pickableJobs(setOf(trip.attributedJobId)), tagGroups = catalog.pickableTagGroups(data.ownTags),
            places = catalog.places,
            ownJob = job(trip.attributedJobId), billable = trip.billable, tagIds = data.ownTags,
            notes = trip.freeNotes, frozen = trip.frozen, error = inputs.error,
        )
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
