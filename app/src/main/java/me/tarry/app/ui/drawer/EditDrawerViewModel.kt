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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tarry.app.R
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.ui.timeline.displayLabel
import me.tarry.app.ui.timeline.labelIsCoordinates
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.app.map.GeoPoint
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.ItemRef
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

private class Catalog(val places: List<PlaceOption>)

private class Inputs(val target: EditTarget?, val error: EditError?, val catalog: Catalog)

/** The rows behind one visit or trip, read fresh from the database. */
private class ItemData(val visit: Visit?, val trip: Trip?, val place: Place?)

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
) : ViewModel() {
    private val target = MutableStateFlow<EditTarget?>(null)
    private val reload = MutableStateFlow(0)
    private val error = MutableStateFlow<EditError?>(null)
    private val eventChannel = Channel<EditDrawerEvent>(Channel.BUFFERED)

    val events: Flow<EditDrawerEvent> = eventChannel.receiveAsFlow()

    private val catalog: Flow<Catalog> = places.observeAll().map { allPlaces ->
        Catalog(
            places = allPlaces.filter { it.mergedIntoId == null }.map { PlaceOption(it.id, it.displayLabel(), it.address?.takeIf { a -> a.isNotBlank() }, GeoPoint(it.centroidLat, it.centroidLng), it.lastVisitAt, it.radiusM) },
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
        val ref = ref() ?: return@launch
        finish(editor.setNotes(ref, notes))
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
        is EditTarget.NewVisit -> null
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
        val data = loadItem(t) ?: return null
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
            )
        }
        val trip = data.trip ?: return null
        return EditDrawerState(
            kind = EditKind.TRIP, id = trip.id, start = trip.start, end = trip.end, zoneId = trip.zoneId,
            tripMode = trip.mode, distanceM = trip.distanceM,
            places = catalog.places,
            notes = trip.freeNotes, frozen = trip.frozen, error = inputs.error,
        )
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
