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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tarry.app.R
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.timeline.AddJobResult
import me.tarry.app.ui.timeline.JobBlockConfig
import me.tarry.app.ui.timeline.Span
import me.tarry.app.ui.timeline.addJobToSegment
import me.tarry.app.ui.timeline.displayLabel
import me.tarry.app.ui.timeline.labelIsCoordinates
import me.tarry.app.ui.timeline.saveMerged
import me.tarry.app.ui.timeline.saveMergedUndoable
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.app.map.GeoPoint
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.Job as JobEntity
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.ItemRef
import me.tarry.core.data.repository.JobBlockRepository
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.MergeDirection
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineEditor
import me.tarry.core.data.repository.TimelineRepository
import java.time.ZoneId
import javax.inject.Inject

/** One-shot instructions for the host: close the drawer, or swap what it shows. */
sealed interface EditDrawerEvent {
    data object Dismiss : EditDrawerEvent

    data class Retarget(val target: EditTarget) : EditDrawerEvent
}

private class Catalog(val places: List<PlaceOption>, val jobs: List<JobEntity>) {
    private fun JobEntity.option() = JobOption(id, name, colorArgb, defaultBillable)

    /** Archived jobs are not offered, unless the open item already uses one ([keep]). */
    fun pickable(keep: Set<String?>): List<JobOption> = jobs.filter { !it.archived || it.id in keep }.map { it.option() }

    fun option(id: String?): JobOption? = id?.let { wanted -> jobs.firstOrNull { it.id == wanted }?.option() }
}

private class Inputs(val target: EditTarget?, val error: EditError?, val catalog: Catalog)

/** The rows behind one visit or trip, read fresh from the database. */
private class ItemData(val visit: Visit?, val trip: Trip?, val place: Place?, val block: JobBlock? = null)

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
    private val editor: TimelineEditor,
    private val undoEvents: UndoEvents,
    private val scheduler: SegmentationScheduler,
    private val jobs: JobRepository,
    private val jobBlocks: JobBlockRepository,
) : ViewModel() {
    private val target = MutableStateFlow<EditTarget?>(null)
    private val reload = MutableStateFlow(0)
    private val error = MutableStateFlow<EditError?>(null)
    private val eventChannel = Channel<EditDrawerEvent>(Channel.BUFFERED)
    private val blockEdits = Mutex()

    val events: Flow<EditDrawerEvent> = eventChannel.receiveAsFlow()

    private val catalog: Flow<Catalog> = combine(places.observeAll(), jobs.observeAll()) { allPlaces, allJobs ->
        Catalog(
            places = allPlaces.filter { it.mergedIntoId == null }.map { PlaceOption(it.id, it.displayLabel(), it.address?.takeIf { a -> a.isNotBlank() }, GeoPoint(it.centroidLat, it.centroidLng), it.lastVisitAt, it.radiusM) },
            jobs = allJobs,
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

    fun setNotes(notes: String) = viewModelScope.launch {
        if (target.value is EditTarget.Job) return@launch editBlock { it.copy(notes = notes.ifEmpty { null }) }
        val ref = ref() ?: return@launch
        finish(editor.setNotes(ref, notes))
    }

    /** Names the visit's place; a blank name clears it so the address label shows again. */
    fun renamePlace(name: String) = viewModelScope.launch {
        val place = item()?.place ?: return@launch
        finish(editor.renamePlace(place.id, name))
    }

    // endregion

    // region job blocks

    /**
     * Creates a job and reports its id to [onCreated] (the picker that asked then uses it). A refused name (blank) shows
     * inline. The picker lists refresh on their own, since the catalog observes the job table.
     */
    fun createJob(name: String, onCreated: (String) -> Unit = {}) = viewModelScope.launch {
        val created = editor.createJob(name, JobPalette.slots.map { it.key })
        val newId = (created as? EditResult.Ok)?.id
        if (newId != null) onCreated(newId) else finish(created)
    }

    /**
     * Saves the new-job form. [billable] null takes the job's own default. A missing job or an end not after the start
     * stays open with the error inline; success closes the drawer.
     */
    fun createBlock(jobId: String?, start: Long, end: Long, billable: Boolean?, notes: String) = viewModelScope.launch {
        if (target.value !is EditTarget.NewJob) return@launch
        if (jobId == null) return@launch fail(EditError.JOB_REQUIRED)
        val job = jobs.job(jobId)?.takeUnless { it.deleted } ?: return@launch fail(EditError.JOB_REQUIRED)
        if (end <= start) return@launch finish(EditResult.EmptyRange)
        jobBlocks.saveMerged(
            JobBlock(
                jobId = job.id, start = start, end = end, zoneId = ZoneId.systemDefault().id,
                billable = billable ?: job.defaultBillable, notes = notes.ifEmpty { null }, updatedAt = 0L,
            ),
        )
        finish(EditResult.Ok())
        eventChannel.send(EditDrawerEvent.Dismiss)
    }

    /** JOB edits save as they are made, like the visit drawer's time edits; a bad range shows inline and changes nothing. */
    fun setBlockJob(jobId: String) = viewModelScope.launch {
        if (jobs.job(jobId)?.takeUnless { it.deleted } == null) return@launch fail(EditError.JOB_REQUIRED)
        editBlock { it.copy(jobId = jobId) }
    }

    fun setBlockStart(start: Long) = viewModelScope.launch { editBlock { it.copy(start = start) } }

    fun setBlockEnd(end: Long) = viewModelScope.launch { editBlock { it.copy(end = end) } }

    fun setBlockBillable(billable: Boolean) = viewModelScope.launch { editBlock { it.copy(billable = billable) } }

    /** Soft-deletes the open block, offers Undo (which restores it) and closes the drawer. */
    fun deleteBlock() = viewModelScope.launch {
        val id = (target.value as? EditTarget.Job)?.id ?: return@launch
        blockEdits.withLock {
            jobBlocks.delete(id)
            undoEvents.offer(UndoEvent(R.string.edit_undo_job_deleted, null, onUndo = { jobBlocks.restore(id) }))
        }
        finish(EditResult.Ok())
        eventChannel.send(EditDrawerEvent.Dismiss)
    }

    /**
     * Puts the open visit or trip in [jobId]: extends a block of that job that overlaps or lies within the join window,
     * else creates a block equal to the segment, billable per the job's default. Either way the result is merged with any
     * same-job block it now overlaps, so blocks of one job never overlap.
     */
    fun addToJob(jobId: String) = viewModelScope.launch {
        val (start, end) = segmentRange() ?: return@launch
        val job = jobs.job(jobId)?.takeUnless { it.deleted } ?: return@launch fail(EditError.JOB_REQUIRED)
        val config = JobBlockConfig()
        blockEdits.withLock {
            // The query is open at both ends, so widen by one ms to see a block exactly a join window away.
            val nearby = jobBlocks.overlapping(start - config.joinWindowMs - 1, end + config.joinWindowMs + 1)
            val save = when (val result = addJobToSegment(Span(start, end), job.id, nearby, config)) {
                is AddJobResult.Extend -> nearby.firstOrNull { it.id == result.blockId }
                    ?.let { jobBlocks.saveMergedUndoable(it.copy(start = result.start, end = result.end)) }
                is AddJobResult.Create -> jobBlocks.saveMergedUndoable(
                    JobBlock(
                        jobId = job.id, start = result.start, end = result.end, zoneId = ZoneId.systemDefault().id,
                        billable = job.defaultBillable, updatedAt = 0L,
                    ),
                )
            }
            save?.let {
                undoEvents.offer(UndoEvent(R.string.edit_undo_added_to_job, null, onUndo = { it.undo(jobBlocks) }, args = listOf(job.name)))
            }
        }
        finish(EditResult.Ok())
    }

    /** Writes the visit's place default job (null clears it). It only suggests a job for future visits there. */
    fun setPlaceDefaultJob(jobId: String?) = viewModelScope.launch {
        val place = item()?.place?.takeUnless { it.deleted } ?: return@launch
        finish(editor.setPlaceDefault(place.id, jobId))
    }

    /** Read-modify-write of the open block; serialised so quick successive edits (a notes flush and a time pick) cannot lose one another. */
    private suspend fun editBlock(change: (JobBlock) -> JobBlock) = blockEdits.withLock {
        val id = (target.value as? EditTarget.Job)?.id ?: return@withLock
        val block = jobBlocks.byId(id)?.takeUnless { it.deleted } ?: return@withLock finish(EditResult.NotFound)
        val changed = change(block)
        if (changed.end <= changed.start) return@withLock finish(EditResult.EmptyRange)
        if (changed != block) jobBlocks.saveMerged(changed)
        finish(EditResult.Ok())
    }

    private fun fail(e: EditError) {
        error.value = e
        reload.update { it + 1 }
    }

    /** `[start, end)` of the open visit or trip; null when it is gone. */
    private suspend fun segmentRange(): Pair<Long, Long>? = when (val t = target.value) {
        is EditTarget.Visit -> timeline.visit(t.id)?.takeUnless { it.deleted }?.let { it.start to it.end }
        is EditTarget.Trip -> timeline.trip(t.id)?.takeUnless { it.deleted }?.let { it.start to it.end }
        else -> null
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

    /**
     * Saves the new-visit form: a frozen MANUAL visit at [placeId] over `[start, end)`. On success the drawer follows the
     * new visit (so the user can label it), with an undo offer and a segmentation run; on a validation failure it stays
     * open with the error inline.
     */
    fun createManual(placeId: String, start: Long, end: Long) = saveManual {
        editor.createManual(placeId, start, end, ZoneId.systemDefault().id)
    }

    /**
     * Like [createManual], but at a new place centred on a map pin. One undo removes the visit and the place. The
     * address is geocoded after the segmentation run this requests, the same way as for detected places.
     */
    fun createManualAtNewPlace(lat: Double, lng: Double, start: Long, end: Long) = saveManual {
        editor.createManualAtNewPlace(lat, lng, start, end, ZoneId.systemDefault().id)
    }

    private fun saveManual(op: suspend () -> EditResult) = viewModelScope.launch {
        if (target.value !is EditTarget.NewVisit) return@launch
        val result = op()
        if (result is EditResult.Ok) {
            result.undo?.let { undoEvents.offer(UndoEvent(R.string.edit_undo_visit_added, it)) }
            scheduler.enqueueNow()
        }
        finish(result)
        val newId = (result as? EditResult.Ok)?.id
        if (newId != null) eventChannel.send(EditDrawerEvent.Retarget(EditTarget.Visit(newId)))
    }

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
            ItemData(visit = v, trip = null, place = v.placeId?.let { places.place(it) })
        }
        is EditTarget.Trip -> timeline.trip(t.id)?.takeUnless { it.deleted }?.let { trip ->
            ItemData(trip = trip, visit = null, place = null)
        }
        is EditTarget.Job -> jobBlocks.byId(t.id)?.takeUnless { it.deleted }?.let { ItemData(null, null, null, block = it) }
        is EditTarget.NewVisit, is EditTarget.NewJob -> null
    }

    /** The live job blocks overlapping `[start, end)`, as chips (blocks of a deleted job are left out). */
    private suspend fun chipsFor(start: Long, end: Long, catalog: Catalog): List<BlockChip> =
        jobBlocks.observeOverlapping(start, end).first().mapNotNull { b ->
            catalog.option(b.jobId)?.let { BlockChip(b.id, it, b.start, b.end) }
        }

    private suspend fun build(inputs: Inputs): EditDrawerState? {
        val t = inputs.target ?: return null
        val catalog = inputs.catalog
        if (t is EditTarget.NewVisit) {
            return EditDrawerState(
                kind = EditKind.NEW_VISIT, id = null, start = t.start, end = t.end, zoneId = ZoneId.systemDefault().id,
                places = catalog.places, error = inputs.error,
            )
        }
        if (t is EditTarget.NewJob) {
            val job = catalog.option(t.jobId)
            return EditDrawerState(
                kind = EditKind.NEW_JOB, id = null, start = t.start, end = t.end, zoneId = ZoneId.systemDefault().id,
                jobs = catalog.pickable(setOf(t.jobId)), jobId = job?.id, billable = job?.defaultBillable ?: true,
                error = inputs.error,
            )
        }
        val data = loadItem(t) ?: return null
        data.block?.let { b ->
            val visits = timeline.observeVisits(b.start, b.end).first().count { !it.deleted && it.start < b.end && b.start < it.end }
            val trips = timeline.observeTrips(b.start, b.end).first().count { !it.deleted && it.start < b.end && b.start < it.end }
            return EditDrawerState(
                kind = EditKind.JOB, id = b.id, start = b.start, end = b.end, zoneId = b.zoneId,
                notes = b.notes.orEmpty(), jobs = catalog.pickable(setOf(b.jobId)), jobId = b.jobId, billable = b.billable,
                coveredVisits = visits, coveredTrips = trips, error = inputs.error,
            )
        }
        data.visit?.let { v ->
            val place = data.place
            return EditDrawerState(
                kind = EditKind.VISIT, id = v.id, start = v.start, end = v.end, zoneId = v.zoneId,
                placeLabel = place?.displayLabel(), placeLabelIsCoordinates = place?.labelIsCoordinates() ?: false,
                placeName = place?.name?.takeIf { it.isNotBlank() },
                hasPlace = place != null && !place.deleted, placeIgnored = place?.ignored ?: false,
                placeId = place?.takeIf { !it.deleted }?.id,
                placeCentre = place?.takeIf { !it.deleted }?.let { GeoPoint(it.centroidLat, it.centroidLng) },
                placeRadiusM = place?.radiusM ?: 0f,
                places = catalog.places,
                notes = v.freeNotes, frozen = v.frozen, origin = v.origin, error = inputs.error,
                jobs = catalog.pickable(setOfNotNull(place?.defaultJobId)),
                blocks = chipsFor(v.start, v.end, catalog),
                placeDefaultJob = place?.takeIf { !it.deleted && !it.ignored }?.let { catalog.option(it.defaultJobId) },
            )
        }
        val trip = data.trip ?: return null
        return EditDrawerState(
            kind = EditKind.TRIP, id = trip.id, start = trip.start, end = trip.end, zoneId = trip.zoneId,
            tripMode = trip.mode, distanceM = trip.distanceM,
            places = catalog.places,
            notes = trip.freeNotes, frozen = trip.frozen, error = inputs.error,
            jobs = catalog.pickable(emptySet()), blocks = chipsFor(trip.start, trip.end, catalog),
        )
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
