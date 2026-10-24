package me.tarry.app.ui.drawer

import androidx.compose.runtime.Immutable
import me.tarry.app.map.GeoPoint
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.MergeDirection

enum class EditKind { VISIT, TRIP, NEW_VISIT }

/** A validation failure from the editor, shown inline in the drawer. Never a crash. */
enum class EditError { OVERLAP, EMPTY_RANGE, NOT_FOUND, BLANK_NAME }

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
data class JobOption(val id: String, val name: String, val colorArgb: Int)

@Immutable
data class TagOption(val id: String, val name: String)

@Immutable
data class TagGroupOption(val id: String, val name: String, val tags: List<TagOption>)

@Immutable
data class PlaceOption(val id: String, val label: String, val detail: String?)

/**
 * Everything the drawer draws. Times are UTC epoch ms; [zoneId] is the zone the record was captured in.
 * Resolved values follow the spec: the record's own value if set, otherwise the place default.
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
    /** The visit has a live place; false for a deleted one (label and inherited job still show), which hides Rename and "Set as place default". */
    val hasPlace: Boolean = false,
    val placeIgnored: Boolean = false,
    /** The live place's id, centre and radius for the header mini map; null when there is no live place. */
    val placeId: String? = null,
    val placeCentre: GeoPoint? = null,
    val placeRadiusM: Float = 0f,
    val tripMode: TripMode = TripMode.UNKNOWN,
    val distanceM: Float = 0f,
    val jobs: List<JobOption> = emptyList(),
    val tagGroups: List<TagGroupOption> = emptyList(),
    val places: List<PlaceOption> = emptyList(),
    /** The visit's own job, or for a trip its attribution. */
    val ownJob: JobOption? = null,
    val placeJob: JobOption? = null,
    val billable: Boolean? = null,
    val placeBillable: Boolean? = null,
    /** Tags shown as selected: the record's own, or the place defaults while it has none of its own. */
    val tagIds: Set<String> = emptySet(),
    val tagsInherited: Boolean = false,
    val notes: String = "",
    val frozen: Boolean = false,
    val origin: VisitOrigin? = null,
    /** True when the place's defaults equal this visit's resolved values (and are not all empty). */
    val isPlaceDefault: Boolean = false,
    val error: EditError? = null,
) {
    /** Visits only: own job, else the place default. */
    val resolvedJob: JobOption? get() = ownJob ?: placeJob
    val jobInherited: Boolean get() = ownJob == null && placeJob != null
    val resolvedBillable: Boolean get() = billable ?: placeBillable ?: false
    val billableInherited: Boolean get() = billable == null && placeBillable != null
    val needsReview: Boolean get() = kind == EditKind.VISIT && resolvedJob == null && !placeIgnored

    /** "Reset to detected" exists only for frozen visits that were not created by hand. */
    val canReset: Boolean get() = kind == EditKind.VISIT && frozen && origin != null && origin != VisitOrigin.MANUAL
}

/** What the drawer can ask for. Defaults are no-ops so tests and previews set only what they check. */
class EditDrawerActions(
    val onSetJob: (String?) -> Unit = {},
    val onCreateJob: (String) -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
    val onCreateTag: (String) -> Unit = {},
    val onBillable: (Boolean) -> Unit = {},
    val onNotes: (String) -> Unit = {},
    val onSetPlaceDefault: (Boolean) -> Unit = {},
    val onRenamePlace: (String) -> Unit = {},
    val onTrimStart: (Long) -> Unit = {},
    val onTrimEnd: (Long) -> Unit = {},
    val onSplit: (Long) -> Unit = {},
    val onMerge: (MergeDirection) -> Unit = {},
    val onChangePlace: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onAddStop: () -> Unit = {},
    /** New-visit form: (placeId, start, end). */
    val onCreateManual: (String, Long, Long) -> Unit = { _, _, _ -> },
)
