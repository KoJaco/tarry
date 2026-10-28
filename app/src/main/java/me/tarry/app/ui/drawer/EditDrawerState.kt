package me.tarry.app.ui.drawer

import androidx.compose.runtime.Immutable
import me.tarry.app.map.GeoPoint
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.MergeDirection

enum class EditKind { VISIT, TRIP, NEW_VISIT, JOB, NEW_JOB }

/** A validation failure from the editor, shown inline in the drawer. Never a crash. */
enum class EditError { OVERLAP, EMPTY_RANGE, NOT_FOUND, BLANK_NAME, JOB_REQUIRED }

internal fun EditResult.toError(): EditError? = when (this) {
    is EditResult.Ok -> null
    EditResult.Overlap -> EditError.OVERLAP
    EditResult.EmptyRange -> EditError.EMPTY_RANGE
    EditResult.NotFound -> EditError.NOT_FOUND
    EditResult.BlankName -> EditError.BLANK_NAME
    // Only CatalogEditor returns InUse, and its callers are the catalog screens, not the drawer.
    is EditResult.InUse -> EditError.NOT_FOUND
}

@Immutable
data class JobOption(val id: String, val name: String, val colorArgb: Int, val defaultBillable: Boolean = true)

/** A job block as a chip in the visit or trip drawer: tapping it opens the block. */
@Immutable
data class BlockChip(val id: String, val job: JobOption, val start: Long, val end: Long)

@Immutable
data class PlaceOption(
    val id: String,
    val label: String,
    val detail: String?,
    /** Where the place is and when it was last visited, for the map pin picker; null when not known (tests, previews). */
    val centre: GeoPoint? = null,
    val lastVisitAt: Long? = null,
    val radiusM: Float = 75f,
)

/**
 * Everything the drawer draws. Times are UTC epoch ms; [zoneId] is the zone the record was captured in.
 */
@Immutable
data class EditDrawerState(
    val kind: EditKind,
    val id: String?,
    val start: Long,
    val end: Long,
    val zoneId: String,
    /** Visit: the place name, address or coordinates; null when the visit has no place. */
    val placeLabel: String? = null,
    val placeLabelIsCoordinates: Boolean = false,
    /** The place's own name (blank-free), null when unnamed; seeds the rename dialog. */
    val placeName: String? = null,
    /** The visit has a live place; false for a deleted one (the label still shows), which hides Rename. */
    val hasPlace: Boolean = false,
    val placeIgnored: Boolean = false,
    /** The live place's id, centre and radius for the header mini map; null when there is no live place. */
    val placeId: String? = null,
    val placeCentre: GeoPoint? = null,
    val placeRadiusM: Float = 0f,
    val tripMode: TripMode = TripMode.UNKNOWN,
    val distanceM: Float = 0f,
    val places: List<PlaceOption> = emptyList(),
    val notes: String = "",
    val frozen: Boolean = false,
    val origin: VisitOrigin? = null,
    val error: EditError? = null,
    /** Pickable jobs (archived ones hidden unless the block already uses one). */
    val jobs: List<JobOption> = emptyList(),
    /** JOB: the block's job. NEW_JOB: the preselected job, if any. */
    val jobId: String? = null,
    val billable: Boolean = true,
    /** VISIT and TRIP: the job blocks overlapping this segment. */
    val blocks: List<BlockChip> = emptyList(),
    /** VISIT with a live, non-ignored place: that place's default job (suggested for new visits there). */
    val placeDefaultJob: JobOption? = null,
    /** JOB: live visits and trips the block overlaps. */
    val coveredVisits: Int = 0,
    val coveredTrips: Int = 0,
) {
    /** "Reset to detected" exists only for frozen visits that were not created by hand. */
    val canReset: Boolean get() = kind == EditKind.VISIT && frozen && origin != null && origin != VisitOrigin.MANUAL
}

/** What the drawer can ask for. Defaults are no-ops so tests and previews set only what they check. */
class EditDrawerActions(
    val onNotes: (String) -> Unit = {},
    val onRenamePlace: (String) -> Unit = {},
    val onTrimStart: (Long) -> Unit = {},
    val onTrimEnd: (Long) -> Unit = {},
    val onSplit: (Long) -> Unit = {},
    val onMerge: (MergeDirection) -> Unit = {},
    val onChangePlace: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onAddStop: () -> Unit = {},
    /** Job picker: set the job of the open block (JOB). */
    val onSetBlockJob: (String) -> Unit = {},
    val onBlockStart: (Long) -> Unit = {},
    val onBlockEnd: (Long) -> Unit = {},
    val onBlockBillable: (Boolean) -> Unit = {},
    val onDeleteBlock: () -> Unit = {},
    /** New-job form: (jobId, start, end, billable or null for the job's default, notes). */
    val onCreateBlock: (String, Long, Long, Boolean?, String) -> Unit = { _, _, _, _, _ -> },
    /** Inline "New job": creates it, then reports the new id. */
    val onCreateJob: (String, (String) -> Unit) -> Unit = { _, _ -> },
    /** Visit and trip drawer: put this segment in the job (extends a nearby block or creates one). */
    val onAddToJob: (String) -> Unit = {},
    /** Open a job block (a chip in the visit or trip drawer). */
    val onOpenBlock: (String) -> Unit = {},
    /** Visit drawer: the place's default job; null clears it. */
    val onSetPlaceDefaultJob: (String?) -> Unit = {},
    /** New-visit form: (placeId, start, end). */
    val onCreateManual: (String, Long, Long) -> Unit = { _, _, _ -> },
    /** New-visit form, place picked as a new place at a map pin: (lat, lng, start, end). */
    val onCreateManualAtNewPlace: (Double, Double, Long, Long) -> Unit = { _, _, _, _ -> },
)
