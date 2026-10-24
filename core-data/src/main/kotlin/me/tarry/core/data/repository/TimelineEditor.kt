package me.tarry.core.data.repository

import androidx.room.withTransaction
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.dao.ExcludedRangeDao
import me.tarry.core.data.db.dao.JobDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.dao.SegmentationCheckpointDao
import me.tarry.core.data.db.dao.TagDao
import me.tarry.core.data.db.dao.TripDao
import me.tarry.core.data.db.dao.VisitDao
import me.tarry.core.data.db.entity.ExcludedRange
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TripTag
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag
import me.tarry.core.data.model.VisitOrigin
import me.tarry.segmentation.geo.haversineMeters
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Source of "now" for edit timestamps (`updatedAt`). Tests pass a fixed value. */
fun interface EditClock {
    fun nowMs(): Long
}

/**
 * Earliest instant a place op may rewind segmentation to: the start of the raw-ping retention window (0 when pings are
 * kept forever). Older pings are purged, so re-detecting before it would only erase history. Same window as `SegmentationUpgrade`.
 */
fun interface RewindFloor {
    suspend fun floorMs(): Long
}

enum class ItemType { VISIT, TRIP }

/** A visit or a trip, for ops (and bulk ops) that accept either. */
data class ItemRef(val id: String, val type: ItemType) {
    companion object {
        fun visit(id: String) = ItemRef(id, ItemType.VISIT)
        fun trip(id: String) = ItemRef(id, ItemType.TRIP)
    }
}

enum class MergeDirection { PREVIOUS, NEXT }

/** An annotation change that can be applied to one item or, via [TimelineEditor.bulk], to many in one transaction. */
sealed interface AnnotationOp {
    data class SetJob(val jobId: String?) : AnnotationOp
    data class SetBillable(val billable: Boolean?) : AnnotationOp
    data class SetNotes(val notes: String) : AnnotationOp

    /** Replaces the item's tags. */
    data class SetTags(val tagIds: Set<String>) : AnnotationOp

    /** Adds to the item's tags, keeping the existing ones. */
    data class AddTags(val tagIds: Set<String>) : AnnotationOp

    /** Trips only (visits are skipped): the job the trip is attributed to, or null for unattributed. */
    data class SetAttribution(val jobId: String?) : AnnotationOp

    /** Sets `Place.ignored` on the place of each visit (trips are skipped). Does not freeze the visit. */
    data class IgnorePlace(val ignored: Boolean = true) : AnnotationOp
}

/**
 * In-memory undo token: the rows (and their tags) as they were before an edit. Never persisted. [range] is the
 * affected span for structural edits (null for bulk annotations, which restore by id only).
 */
data class Snapshot(
    val range: LongRange?,
    val visits: List<Visit>,
    val visitTags: List<VisitTag>,
    val trips: List<Trip>,
    val tripTags: List<TripTag>,
    val exclusions: List<ExcludedRange>,
    val places: List<Place> = emptyList(),
    /** Ids a [CatalogEditor] archive hid; restore only clears `archived` (and bumps `updatedAt`) on the ones still live, never resurrecting a deleted row or reverting a rename. */
    val archivedJobIds: List<String> = emptyList(),
    val archivedTagIds: List<String> = emptyList(),
    /** The default-tag links a tag archive removed; restore re-inserts just these, and only while both the place and the tag are live. */
    val placeDefaultTags: List<PlaceDefaultTag> = emptyList(),
    /** placeId to the default job a job archive cleared; restore sets only that column, and only while the place is live, still has no default and the job is live. */
    val placeDefaultJobs: Map<String, String> = emptyMap(),
    /** placeId to its exact default-tag set before a place merge; restore replaces the place's links with these (live tags only). */
    val replaceDefaultTags: Map<String, List<PlaceDefaultTag>> = emptyMap(),
    /** Places the edit created (a manual visit at a new map pin); restore soft-deletes each one that no live visit uses any more. */
    val createdPlaceIds: List<String> = emptyList(),
)

/** Limits of a place's circle, in metres. */
object PlaceLimits {
    const val RADIUS_MIN_M = 20f
    const val RADIUS_MAX_M = 300f
    const val RADIUS_STEP_M = 5f
}

/** What [TimelineEditor.mergePlaces] would produce: the target's radius after growing to cover both places, and whether [PlaceLimits.RADIUS_MAX_M] clipped it. */
data class MergePreview(val radiusM: Float, val capped: Boolean)

sealed interface EditResult {
    /**
     * [undo] is set for structural edits and bulk; [id] is the created row for split (second half), createManual, createJob, createTag.
     * [capped] is set by mergePlaces when the target's circle could not grow to cover the source (radius clipped at the max).
     */
    data class Ok(val undo: Snapshot? = null, val id: String? = null, val capped: Boolean = false) : EditResult

    /** The edit would overlap another live visit. */
    data object Overlap : EditResult

    /** Zero or negative duration, or a split point outside the visit. */
    data object EmptyRange : EditResult

    /** A referenced visit, trip, place, job, tag, group or neighbour does not exist (or is deleted). */
    data object NotFound : EditResult

    /** A name that is blank after trimming. */
    data object BlankName : EditResult

    /** A catalog row cannot be deleted because [count] live visits, trips, places or tags still use it. */
    data class InUse(val count: Int) : EditResult
}

/**
 * Every user edit to the timeline (phase 3 T1). One Room transaction per op, so edits serialise with segmentation
 * (which also runs in a transaction). Annotation edits freeze the record and bump `updatedAt`. Structural edits also
 * lower the segmentation checkpoint to the edit start so the affected days are re-processed; the caller (app) is
 * responsible for enqueueing segmentation afterwards. Validation failures come back as [EditResult], not exceptions.
 */
@Singleton
class TimelineEditor @Inject constructor(
    private val db: TarryDatabase,
    private val visits: VisitDao,
    private val trips: TripDao,
    private val excluded: ExcludedRangeDao,
    private val places: PlaceDao,
    private val jobs: JobDao,
    private val tags: TagDao,
    private val checkpoints: SegmentationCheckpointDao,
    private val clock: EditClock,
    private val rewindFloor: RewindFloor = RewindFloor { 0L },
) {
    // region annotations

    suspend fun setJob(ref: ItemRef, jobId: String?): EditResult = annotate(listOf(ref), AnnotationOp.SetJob(jobId), snapshot = false)

    suspend fun setBillable(ref: ItemRef, billable: Boolean?): EditResult =
        annotate(listOf(ref), AnnotationOp.SetBillable(billable), snapshot = false)

    suspend fun setNotes(ref: ItemRef, notes: String): EditResult = annotate(listOf(ref), AnnotationOp.SetNotes(notes), snapshot = false)

    suspend fun setTags(ref: ItemRef, tagIds: Set<String>): EditResult =
        annotate(listOf(ref), AnnotationOp.SetTags(tagIds), snapshot = false)

    /** Trips only. [jobId] null means unattributed. */
    suspend fun setAttribution(tripId: String, jobId: String?): EditResult =
        annotate(listOf(ItemRef.trip(tripId)), AnnotationOp.SetAttribution(jobId), snapshot = false)

    /** One transaction, one undo [Snapshot], for any [AnnotationOp] over many items. Nothing is written if any item is missing. */
    suspend fun bulk(items: List<ItemRef>, op: AnnotationOp): EditResult = annotate(items, op, snapshot = true)

    private suspend fun annotate(items: List<ItemRef>, op: AnnotationOp, snapshot: Boolean): EditResult = db.withTransaction {
        if (items.isEmpty()) return@withTransaction EditResult.NotFound
        val visitRows = items.filter { it.type == ItemType.VISIT }.map { visits.getById(it.id)?.takeUnless { v -> v.deleted } ?: return@withTransaction EditResult.NotFound }
        val tripRows = items.filter { it.type == ItemType.TRIP }.map { trips.getById(it.id)?.takeUnless { t -> t.deleted } ?: return@withTransaction EditResult.NotFound }
        val placeRows = if (op is AnnotationOp.IgnorePlace) {
            visitRows.mapNotNull { it.placeId }.distinct().map { livePlace(it) ?: return@withTransaction EditResult.NotFound }
        } else {
            emptyList()
        }
        val undo = if (snapshot) {
            Snapshot(
                range = null,
                visits = visitRows, visitTags = visitRows.flatMap { visits.getTags(it.id) },
                trips = tripRows, tripTags = tripRows.flatMap { trips.getTags(it.id) },
                exclusions = emptyList(), places = placeRows,
            )
        } else {
            null
        }
        val now = clock.nowMs()
        when (op) {
            is AnnotationOp.IgnorePlace -> placeRows.forEach { places.upsert(it.copy(ignored = op.ignored, updatedAt = now)) }
            is AnnotationOp.SetAttribution -> tripRows.forEach { trips.upsert(it.copy(attributedJobId = op.jobId, frozen = true, updatedAt = now)) }
            else -> {
                visitRows.forEach { v ->
                    visits.upsert(
                        when (op) {
                            is AnnotationOp.SetJob -> v.copy(jobId = op.jobId)
                            is AnnotationOp.SetBillable -> v.copy(billable = op.billable)
                            is AnnotationOp.SetNotes -> v.copy(freeNotes = op.notes)
                            else -> v
                        }.copy(frozen = true, updatedAt = now),
                    )
                    when (op) {
                        is AnnotationOp.SetTags -> {
                            visits.deleteAllTags(v.id)
                            op.tagIds.forEach { visits.upsertTag(VisitTag(v.id, it)) }
                        }
                        is AnnotationOp.AddTags -> op.tagIds.forEach { visits.upsertTag(VisitTag(v.id, it)) }
                        else -> Unit
                    }
                }
                tripRows.forEach { t ->
                    trips.upsert(
                        when (op) {
                            is AnnotationOp.SetJob -> t.copy(attributedJobId = op.jobId)
                            is AnnotationOp.SetBillable -> t.copy(billable = op.billable)
                            is AnnotationOp.SetNotes -> t.copy(freeNotes = op.notes)
                            else -> t
                        }.copy(frozen = true, updatedAt = now),
                    )
                    when (op) {
                        is AnnotationOp.SetTags -> {
                            trips.deleteAllTags(t.id)
                            op.tagIds.forEach { trips.upsertTag(TripTag(t.id, it)) }
                        }
                        is AnnotationOp.AddTags -> op.tagIds.forEach { trips.upsertTag(TripTag(t.id, it)) }
                        else -> Unit
                    }
                }
            }
        }
        EditResult.Ok(undo)
    }

    /**
     * Sets `Place.ignored` directly, so a place with no live visits can be ignored too. Re-reads the row inside the
     * transaction (segmentation rewrites centroid and lastVisitAt). The undo restores the row as it was. No change is
     * [EditResult.Ok] without an undo. Does not freeze or rewind anything.
     */
    suspend fun setPlaceIgnored(placeId: String, ignored: Boolean): EditResult = db.withTransaction {
        val place = livePlace(placeId) ?: return@withTransaction EditResult.NotFound
        if (place.ignored == ignored) return@withTransaction EditResult.Ok()
        val undo = Snapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), places = listOf(place))
        places.upsert(place.copy(ignored = ignored, updatedAt = clock.nowMs()))
        EditResult.Ok(undo)
    }

    /** Writes the place's defaults (replacing the previous ones). Applies to future visits only. */
    suspend fun setPlaceDefault(placeId: String, jobId: String?, billable: Boolean?, tagIds: Set<String>): EditResult = db.withTransaction {
        val place = places.getById(placeId)?.takeUnless { it.deleted } ?: return@withTransaction EditResult.NotFound
        places.upsert(place.copy(defaultJobId = jobId, defaultBillable = billable, updatedAt = clock.nowMs()))
        places.getDefaultTags(placeId).forEach { places.deleteDefaultTag(placeId, it.tagId) }
        tagIds.forEach { places.upsertDefaultTag(PlaceDefaultTag(placeId, it)) }
        EditResult.Ok()
    }

    // endregion

    // region catalog

    /**
     * Creates a job with the first colour in [paletteArgb] that no live job uses (the palette is the app's
     * `JobPalette` keys, passed in because core-data cannot see the UI theme). When all are used it cycles by job count.
     */
    suspend fun createJob(name: String, paletteArgb: List<Int>): EditResult = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction EditResult.BlankName
        val used = jobs.getAllActive()
        val usedColors = used.map { it.colorArgb }.toSet()
        val color = paletteArgb.firstOrNull { it !in usedColors }
            ?: paletteArgb.getOrNull(used.size % paletteArgb.size.coerceAtLeast(1))
            ?: 0
        val job = Job(name = trimmed, colorArgb = color, updatedAt = clock.nowMs())
        jobs.upsert(job)
        EditResult.Ok(id = job.id)
    }

    /** Creates a tag at the end of [groupId], or of the first group (creating a "Tags" group if there are none) when null. */
    suspend fun createTag(name: String, groupId: String? = null): EditResult = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction EditResult.BlankName
        val now = clock.nowMs()
        val group = if (groupId != null) {
            tags.getGroupById(groupId)?.takeUnless { it.deleted } ?: return@withTransaction EditResult.NotFound
        } else {
            tags.getGroups().firstOrNull() ?: TagGroup(name = "Tags", colorArgb = 0, sortOrder = 0, updatedAt = now).also { tags.upsertGroup(it) }
        }
        val tag = Tag(groupId = group.id, name = trimmed, sortOrder = tags.getByGroup(group.id).size, updatedAt = now)
        tags.upsert(tag)
        EditResult.Ok(id = tag.id)
    }

    // endregion

    // region structural edits

    /** Moves the edges, clamped to the neighbouring live visits. Zero length after clamping is [EditResult.EmptyRange]. */
    suspend fun trim(visitId: String, newStart: Long, newEnd: Long): EditResult = db.withTransaction {
        val v = liveVisit(visitId) ?: return@withTransaction EditResult.NotFound
        val start = maxOf(newStart, visits.getLastEndingAtOrBefore(v.start)?.end ?: Long.MIN_VALUE)
        val end = minOf(newEnd, visits.getFirstStartingAtOrAfter(v.end)?.start ?: Long.MAX_VALUE)
        if (end <= start) return@withTransaction EditResult.EmptyRange
        val from = minOf(v.start, start)
        val to = maxOf(v.end, end)
        val undo = snapshotRange(from, to)
        visits.upsert(v.copy(start = start, end = end, frozen = true, updatedAt = clock.nowMs()))
        unfreezeTripsOver(start, end)
        rewindCheckpoint(from)
        EditResult.Ok(undo)
    }

    /**
     * Drag-to-resize (phase 3 R2): moves the edges to [newStart]/[newEnd] and trims the adjacent previous or next live
     * visit if the new span overlaps it (never merges or deletes). The visit keeps at least [minDurationMs] (the edge
     * that moved is clamped), and a trimmed neighbour keeps at least [minDurationMs] too, so the span stops there.
     * Everything touched is frozen, overlapped frozen trips are unfrozen, the checkpoint is rewound, and one
     * [Snapshot] undoes it all. No change at all is [EditResult.Ok] with no undo.
     */
    suspend fun resize(visitId: String, newStart: Long, newEnd: Long, minDurationMs: Long = MIN_VISIT_MS): EditResult =
        db.withTransaction {
            val v = liveVisit(visitId) ?: return@withTransaction EditResult.NotFound
            var start = newStart
            var end = newEnd
            if (end - start < minDurationMs) {
                if (start != v.start && end == v.end) start = end - minDurationMs else end = start + minDurationMs
            }
            val prev = visits.getLastEndingAtOrBefore(v.start)
            val next = visits.getFirstStartingAtOrAfter(v.end)
            if (prev != null) start = maxOf(start, if (prev.end - prev.start >= minDurationMs) prev.start + minDurationMs else prev.end)
            if (next != null) end = minOf(end, if (next.end - next.start >= minDurationMs) next.end - minDurationMs else next.start)
            if (end - start < minDurationMs) return@withTransaction EditResult.EmptyRange
            if (start == v.start && end == v.end) return@withTransaction EditResult.Ok()
            val prevTrim = prev?.takeIf { start < it.end }
            val nextTrim = next?.takeIf { end > it.start }
            val from = minOf(v.start, start, prevTrim?.start ?: Long.MAX_VALUE)
            val to = maxOf(v.end, end, nextTrim?.end ?: Long.MIN_VALUE)
            val undo = snapshotRange(from, to)
            val now = clock.nowMs()
            visits.upsert(v.copy(start = start, end = end, frozen = true, updatedAt = now))
            prevTrim?.let { visits.upsert(it.copy(end = start, frozen = true, updatedAt = now)) }
            nextTrim?.let { visits.upsert(it.copy(start = end, frozen = true, updatedAt = now)) }
            unfreezeTripsOver(from, to)
            rewindCheckpoint(from)
            EditResult.Ok(undo)
        }

    /** Splits at [at] (strictly inside the visit) into two frozen [VisitOrigin.SPLIT] visits. [EditResult.Ok.id] is the second. */
    suspend fun split(visitId: String, at: Long): EditResult = db.withTransaction {
        val v = liveVisit(visitId) ?: return@withTransaction EditResult.NotFound
        if (at <= v.start || at >= v.end) return@withTransaction EditResult.EmptyRange
        val undo = snapshotRange(v.start, v.end)
        val now = clock.nowMs()
        val second = v.copy(id = UUID.randomUUID().toString(), start = at)
        visits.upsert(v.copy(end = at, frozen = true, origin = VisitOrigin.SPLIT, updatedAt = now))
        visits.upsert(second.copy(frozen = true, origin = VisitOrigin.SPLIT, updatedAt = now))
        visits.getTags(v.id).forEach { visits.upsertTag(VisitTag(second.id, it.tagId)) }
        unfreezeTripsOver(v.start, v.end)
        rewindCheckpoint(v.start)
        EditResult.Ok(undo, id = second.id)
    }

    /**
     * Merges the visit with its neighbouring live visit into one frozen [VisitOrigin.MERGED] visit spanning both (and
     * the time between). The earlier visit's row is kept: its job (else the later one's), billable and notes (joined),
     * the union of tags, and the place of the longer visit. [EditResult.NotFound] when there is no neighbour.
     */
    suspend fun merge(visitId: String, direction: MergeDirection): EditResult = db.withTransaction {
        val v = liveVisit(visitId) ?: return@withTransaction EditResult.NotFound
        val other = when (direction) {
            MergeDirection.PREVIOUS -> visits.getLastEndingAtOrBefore(v.start)
            MergeDirection.NEXT -> visits.getFirstStartingAtOrAfter(v.end)
        } ?: return@withTransaction EditResult.NotFound
        val (a, b) = if (direction == MergeDirection.PREVIOUS) other to v else v to other
        val undo = snapshotRange(a.start, b.end)
        val now = clock.nowMs()
        val longer = if (b.end - b.start > a.end - a.start) b else a
        val tagIds = (visits.getTags(a.id) + visits.getTags(b.id)).map { it.tagId }.toSet()
        visits.upsert(
            a.copy(
                end = b.end, placeId = longer.placeId, jobId = a.jobId ?: b.jobId, billable = a.billable ?: b.billable,
                freeNotes = listOf(a.freeNotes, b.freeNotes).filter { it.isNotBlank() }.joinToString("\n"),
                frozen = true, origin = VisitOrigin.MERGED, confidence = maxOf(a.confidence, b.confidence), updatedAt = now,
            ),
        )
        visits.upsert(b.copy(deleted = true, updatedAt = now))
        visits.deleteAllTags(a.id)
        tagIds.forEach { visits.upsertTag(VisitTag(a.id, it)) }
        unfreezeTripsOver(a.start, b.end)
        rewindCheckpoint(a.start)
        EditResult.Ok(undo, id = a.id)
    }

    suspend fun reassignPlace(visitId: String, placeId: String): EditResult = db.withTransaction {
        val v = liveVisit(visitId) ?: return@withTransaction EditResult.NotFound
        if (places.getById(placeId)?.deleted != false) return@withTransaction EditResult.NotFound
        val undo = snapshotRange(v.start, v.end)
        visits.upsert(v.copy(placeId = placeId, frozen = true, updatedAt = clock.nowMs()))
        rewindCheckpoint(v.start)
        EditResult.Ok(undo)
    }

    /** Soft-deletes the visit and excludes its span so segmentation does not detect it again. */
    suspend fun delete(visitId: String): EditResult = db.withTransaction {
        val v = liveVisit(visitId) ?: return@withTransaction EditResult.NotFound
        val undo = snapshotRange(v.start, v.end)
        val now = clock.nowMs()
        visits.upsert(v.copy(deleted = true, updatedAt = now))
        excluded.upsert(ExcludedRange(start = v.start, end = v.end, zoneId = v.zoneId, updatedAt = now))
        rewindCheckpoint(v.start)
        EditResult.Ok(undo)
    }

    /** A frozen [VisitOrigin.MANUAL] visit. Rejected if it overlaps a live visit; trips may overlap (they are derived). */
    suspend fun createManual(placeId: String, start: Long, end: Long, zoneId: String = ZoneId.systemDefault().id): EditResult =
        db.withTransaction { insertManual(placeId, start, end, zoneId, newPlace = null) }

    /**
     * [createManual] at a new, unnamed place centred on the pin ([lat], [lng]; default radius), in one transaction. Any
     * failure inserts nothing. The undo removes the visit and the new place together. The address is filled later by the geocoder.
     */
    suspend fun createManualAtNewPlace(
        lat: Double,
        lng: Double,
        start: Long,
        end: Long,
        zoneId: String = ZoneId.systemDefault().id,
    ): EditResult = db.withTransaction {
        val place = Place(centroidLat = lat, centroidLng = lng, lastVisitAt = end, updatedAt = clock.nowMs())
        insertManual(place.id, start, end, zoneId, newPlace = place)
    }

    private suspend fun insertManual(placeId: String, start: Long, end: Long, zoneId: String, newPlace: Place?): EditResult {
        if (end <= start) return EditResult.EmptyRange
        if (newPlace == null && places.getById(placeId)?.deleted != false) return EditResult.NotFound
        if (visits.getInRange(start, end).isNotEmpty()) return EditResult.Overlap
        val undo = snapshotRange(start, end)
        newPlace?.let { places.upsert(it) }
        val visit = Visit(
            placeId = placeId, start = start, end = end, zoneId = zoneId, frozen = true,
            origin = VisitOrigin.MANUAL, confidence = 1f, updatedAt = clock.nowMs(),
        )
        visits.upsert(visit)
        unfreezeTripsOver(start, end)
        rewindCheckpoint(start)
        return EditResult.Ok(undo.copy(createdPlaceIds = listOfNotNull(newPlace?.id)), id = visit.id)
    }

    /**
     * Clears job, billable, notes and tags, unfreezes, and soft-deletes the exclusions inside the visit's span, so the
     * next segmentation run owns it again.
     */
    suspend fun resetToDetected(visitId: String): EditResult = db.withTransaction {
        val v = liveVisit(visitId) ?: return@withTransaction EditResult.NotFound
        val undo = snapshotRange(v.start, v.end)
        val now = clock.nowMs()
        visits.upsert(v.copy(jobId = null, billable = null, freeNotes = "", frozen = false, updatedAt = now))
        visits.deleteAllTags(v.id)
        excluded.getInRange(v.start, v.end).filter { it.start >= v.start && it.end <= v.end }
            .forEach { excluded.upsert(it.copy(deleted = true, updatedAt = now)) }
        rewindCheckpoint(v.start)
        EditResult.Ok(undo)
    }

    // endregion

    // region undo

    /**
     * Puts back the rows in [snapshot] exactly as they were (tags included). For a structural edit it also soft-deletes
     * live visits, trips and exclusions in the affected range that are not in the snapshot (rows the edit or a later
     * segmentation run created), and rewinds the checkpoint so segmentation re-checks the range.
     */
    suspend fun restore(snapshot: Snapshot): EditResult = db.withTransaction {
        val now = clock.nowMs()
        snapshot.range?.let { range ->
            val from = range.first - PAD_MS
            val to = range.last + PAD_MS
            val keepVisits = snapshot.visits.map { it.id }.toSet()
            val keepTrips = snapshot.trips.map { it.id }.toSet()
            val keepExclusions = snapshot.exclusions.map { it.id }.toSet()
            visits.getInRange(from, to).filter { it.id !in keepVisits }.forEach { visits.upsert(it.copy(deleted = true, updatedAt = now)) }
            trips.getInRange(from, to).filter { it.id !in keepTrips }.forEach { trips.upsert(it.copy(deleted = true, updatedAt = now)) }
            excluded.getInRange(from, to).filter { it.id !in keepExclusions }.forEach { excluded.upsert(it.copy(deleted = true, updatedAt = now)) }
        }
        snapshot.places.forEach { places.upsert(it) }
        snapshot.replaceDefaultTags.forEach { (placeId, links) ->
            places.deleteAllDefaultTags(placeId)
            links.forEach { if (tags.getById(it.tagId)?.deleted == false) places.upsertDefaultTag(it) }
        }
        // Catalog undo is targeted: it reverses only what the archive did, so edits made since (renames, new defaults) survive.
        snapshot.archivedJobIds.forEach { jobs.unarchive(it, now) }
        snapshot.placeDefaultJobs.forEach { (placeId, jobId) ->
            if (jobs.getById(jobId)?.deleted == false) places.restoreDefaultJob(placeId, jobId, now)
        }
        snapshot.archivedTagIds.forEach { tags.unarchive(it, now) }
        snapshot.placeDefaultTags.forEach { link ->
            val placeLive = places.getById(link.placeId)?.deleted == false
            val tagLive = tags.getById(link.tagId)?.deleted == false
            if (placeLive && tagLive) places.upsertDefaultTag(link)
        }
        snapshot.visits.forEach { v ->
            visits.upsert(v)
            visits.deleteAllTags(v.id)
            snapshot.visitTags.filter { it.visitId == v.id }.forEach { visits.upsertTag(it) }
        }
        snapshot.trips.forEach { t ->
            trips.upsert(t)
            trips.deleteAllTags(t.id)
            snapshot.tripTags.filter { it.tripId == t.id }.forEach { trips.upsertTag(it) }
        }
        snapshot.exclusions.forEach { excluded.upsert(it) }
        snapshot.createdPlaceIds.forEach { id ->
            val created = livePlace(id)
            if (created != null && visits.getLiveByPlace(id).isEmpty()) places.upsert(created.copy(deleted = true, updatedAt = now))
        }
        snapshot.range?.let { rewindCheckpoint(if (snapshot.places.isNotEmpty()) maxOf(it.first, rewindFloor.floorMs()) else it.first) }
        EditResult.Ok()
    }

    // endregion

    /** Frozen trips under a new or extended visit span are unfrozen (annotations kept) so segmentation recomputes their times. */
    private suspend fun unfreezeTripsOver(from: Long, to: Long) {
        val now = clock.nowMs()
        trips.getFrozenInRange(from, to).forEach { trips.upsert(it.copy(frozen = false, updatedAt = now)) }
    }

    private suspend fun liveVisit(id: String): Visit? = visits.getById(id)?.takeUnless { it.deleted }

    /** Everything live that touches [from, to] (padded by 1 ms so rows ending exactly at the edge, such as adjacent trips, are included). */
    private suspend fun snapshotRange(from: Long, to: Long): Snapshot {
        val vs = visits.getInRange(from - PAD_MS, to + PAD_MS)
        val ts = trips.getInRange(from - PAD_MS, to + PAD_MS)
        return Snapshot(
            range = from..to,
            visits = vs, visitTags = vs.flatMap { visits.getTags(it.id) },
            trips = ts, tripTags = ts.flatMap { trips.getTags(it.id) },
            exclusions = excluded.getInRange(from - PAD_MS, to + PAD_MS),
        )
    }

    /** Segmentation only reprocesses from the checkpoint, so an edit before it must pull the checkpoint back. */
    private suspend fun rewindCheckpoint(editStart: Long) {
        val current = checkpoints.getById()?.processedUntil ?: return
        if (editStart < current) checkpoints.upsert(SegmentationCheckpoint(processedUntil = editStart))
    }

    // region place naming

    /** Sets [Place.name]; a blank [name] clears it so the address label shows again. */
    suspend fun renamePlace(placeId: String, name: String?): EditResult = db.withTransaction {
        val place = places.getById(placeId)?.takeUnless { it.deleted } ?: return@withTransaction EditResult.NotFound
        places.upsert(place.copy(name = name?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    // endregion

    // region place geometry (phase 4 T7)
    //
    // Every op re-reads its rows by id inside the transaction and never upserts a Place the UI holds, because segmentation
    // rewrites centroid and lastVisitAt. Each returns an undo [Snapshot] for [restore]; when unfrozen history is affected the
    // snapshot spans [earliest unfrozen visit, now], so the undo is exact even after a re-run. The caller (app) enqueues segmentation.

    /**
     * Sets the circle radius, coerced to [PlaceLimits] in steps of [PlaceLimits.RADIUS_STEP_M]. No change is [EditResult.Ok] without an
     * undo. Unfrozen visits at the place, and at neighbours within `max(old, new) + neighbour radius`, are re-detected;
     * frozen visits never change.
     */
    suspend fun setPlaceRadius(placeId: String, radiusM: Float): EditResult = db.withTransaction {
        val place = livePlace(placeId) ?: return@withTransaction EditResult.NotFound
        val radius = coerceRadius(radiusM)
        if (radius == place.radiusM) return@withTransaction EditResult.Ok()
        val reach = maxOf(place.radiusM, radius)
        val neighbours = places.getAllActive().filter {
            it.id != place.id && haversineMeters(place.centroidLat, place.centroidLng, it.centroidLat, it.centroidLng) <= reach + it.radiusM
        }
        val from = visits.earliestUnfrozenStart(listOf(place.id) + neighbours.map { it.id })
        val now = clock.nowMs()
        val undo = placeSnapshot(from, now, listOf(place))
        places.setRadius(place.id, radius, now)
        rewindForPlaces(from)
        EditResult.Ok(undo)
    }

    /** The target's radius after a merge, without writing. Null when the ids are the same, missing or deleted (what [mergePlaces] rejects). */
    suspend fun previewMerge(sourceId: String, targetId: String): MergePreview? = db.withTransaction {
        calcMerge(sourceId, targetId)?.let { MergePreview(it.radiusM, it.capped) }
    }

    /**
     * Merges [sourceId] into [targetId]. The target's centroid becomes the duration-weighted mean and its circle grows to cover
     * both (never below its own radius, capped at [PlaceLimits.RADIUS_MAX_M]; [EditResult.Ok.capped] says the cap clipped it).
     * Empty target fields are filled from the source (default tags too, as a set); `ignored` stays the target's. Every
     * visit at the source, frozen included, moves to the target (only placeId and updatedAt change). The source becomes a deleted
     * tombstone with `mergedIntoId` set, and places merged into it point at the target.
     */
    suspend fun mergePlaces(sourceId: String, targetId: String): EditResult = db.withTransaction {
        val calc = calcMerge(sourceId, targetId) ?: return@withTransaction EditResult.NotFound
        val (source, target) = calc.source to calc.target
        val now = clock.nowMs()
        val chain = places.getMergedInto(source.id)
        val sourceVisits = visits.getLiveByPlace(source.id)
        val targetTags = places.getDefaultTags(target.id)
        val from = visits.earliestUnfrozenStart(listOf(source.id, target.id))
        val undo = placeSnapshot(
            from, now, listOf(source, target) + chain, extraVisits = sourceVisits,
            replaceDefaultTags = mapOf(target.id to targetTags),
        )
        places.upsert(
            target.copy(
                centroidLat = calc.lat, centroidLng = calc.lng, radiusM = calc.radiusM,
                name = target.name?.takeIf { it.isNotBlank() } ?: source.name?.takeIf { it.isNotBlank() },
                address = target.address?.takeIf { it.isNotBlank() } ?: source.address?.takeIf { it.isNotBlank() } ?: target.address,
                defaultJobId = target.defaultJobId ?: source.defaultJobId,
                defaultBillable = target.defaultBillable ?: source.defaultBillable,
                lastVisitAt = listOfNotNull(target.lastVisitAt, source.lastVisitAt).maxOrNull(),
                updatedAt = now,
            ),
        )
        if (targetTags.isEmpty()) places.getDefaultTags(source.id).forEach { places.upsertDefaultTag(PlaceDefaultTag(target.id, it.tagId)) }
        visits.repointPlace(source.id, target.id, now)
        places.repointMerged(source.id, target.id, now)
        places.upsert(source.copy(deleted = true, mergedIntoId = target.id, updatedAt = now))
        rewindForPlaces(from)
        EditResult.Ok(undo, capped = calc.capped)
    }

    /**
     * Soft-deletes the place and its unfrozen visits (no [ExcludedRange], so they are detected again, usually at a new place).
     * Frozen visits keep their placeId and are labelled through the deleted place.
     */
    suspend fun deletePlace(placeId: String): EditResult = db.withTransaction {
        val place = livePlace(placeId) ?: return@withTransaction EditResult.NotFound
        val now = clock.nowMs()
        val from = visits.earliestUnfrozenStart(listOf(place.id))
        val undo = placeSnapshot(from, now, listOf(place))
        visits.softDeleteUnfrozenAt(place.id, now)
        places.upsert(place.copy(deleted = true, updatedAt = now))
        rewindForPlaces(from)
        EditResult.Ok(undo)
    }

    private class MergeCalc(val source: Place, val target: Place, val lat: Double, val lng: Double, val radiusM: Float, val capped: Boolean)

    private suspend fun calcMerge(sourceId: String, targetId: String): MergeCalc? {
        if (sourceId == targetId) return null
        val source = livePlace(sourceId) ?: return null
        val target = livePlace(targetId)?.takeIf { it.mergedIntoId == null } ?: return null
        val ds = visits.durationAt(source.id)
        val dt = visits.durationAt(target.id)
        val (ws, wt) = if (ds + dt == 0L) 1.0 to 1.0 else ds.toDouble() to dt.toDouble()
        val lat = (source.centroidLat * ws + target.centroidLat * wt) / (ws + wt)
        val lng = (source.centroidLng * ws + target.centroidLng * wt) / (ws + wt)
        val cover = maxOf(
            haversineMeters(lat, lng, source.centroidLat, source.centroidLng) + source.radiusM,
            haversineMeters(lat, lng, target.centroidLat, target.centroidLng) + target.radiusM,
        )
        val radius = maxOf(target.radiusM, minOf(cover.toFloat(), PlaceLimits.RADIUS_MAX_M))
        return MergeCalc(source, target, lat, lng, radius, capped = cover > PlaceLimits.RADIUS_MAX_M)
    }

    private fun coerceRadius(r: Float): Float {
        val step = PlaceLimits.RADIUS_STEP_M
        return (Math.round(r / step) * step).coerceIn(PlaceLimits.RADIUS_MIN_M, PlaceLimits.RADIUS_MAX_M)
    }

    private suspend fun livePlace(id: String): Place? = places.getById(id)?.takeUnless { it.deleted }

    /** Pulls the checkpoint back to [from] (the earliest unfrozen visit the op touched; null means nothing to re-detect) so the next run reprocesses it, but never before [RewindFloor] (purged pings). */
    private suspend fun rewindForPlaces(from: Long?) {
        if (from != null) rewindCheckpoint(maxOf(from, rewindFloor.floorMs()))
    }

    /**
     * Undo token for a place op: [snapshotRange] from the earliest affected unfrozen visit to now (when there is one), plus
     * the [placeRows] as they were and any [extraVisits] outside that range (frozen visits a merge repoints).
     */
    private suspend fun placeSnapshot(
        from: Long?,
        now: Long,
        placeRows: List<Place>,
        extraVisits: List<Visit> = emptyList(),
        replaceDefaultTags: Map<String, List<PlaceDefaultTag>> = emptyMap(),
    ): Snapshot {
        val base = if (from != null) {
            snapshotRange(from, maxOf(now, from))
        } else {
            Snapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val known = base.visits.map { it.id }.toSet()
        val extra = extraVisits.filter { it.id !in known }
        return base.copy(
            visits = base.visits + extra, visitTags = base.visitTags + extra.flatMap { visits.getTags(it.id) },
            places = placeRows, replaceDefaultTags = replaceDefaultTags,
        )
    }

    // endregion

    private companion object {
        const val PAD_MS = 1L

        /** Default floor for [resize]; the app passes `SegmentationParams.minStayDurationMs`. */
        const val MIN_VISIT_MS = 5 * 60_000L
    }
}
