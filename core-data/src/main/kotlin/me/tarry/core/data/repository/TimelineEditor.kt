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
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.TripTag
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag
import me.tarry.core.data.model.VisitOrigin
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Source of "now" for edit timestamps (`updatedAt`). Tests pass a fixed value. */
fun interface EditClock {
    fun nowMs(): Long
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
)

sealed interface EditResult {
    /** [undo] is set for structural edits and bulk; [id] is the created row for split (second half), createManual, createJob, createTag. */
    data class Ok(val undo: Snapshot? = null, val id: String? = null) : EditResult

    /** The edit would overlap another live visit. */
    data object Overlap : EditResult

    /** Zero or negative duration, or a split point outside the visit. */
    data object EmptyRange : EditResult

    /** A referenced visit, trip, place, job, tag, group or neighbour does not exist (or is deleted). */
    data object NotFound : EditResult

    /** A name that is blank after trimming. */
    data object BlankName : EditResult
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
            visitRows.mapNotNull { it.placeId }.distinct().map { places.getById(it) ?: return@withTransaction EditResult.NotFound }
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
        db.withTransaction {
            if (end <= start) return@withTransaction EditResult.EmptyRange
            if (places.getById(placeId)?.deleted != false) return@withTransaction EditResult.NotFound
            if (visits.getInRange(start, end).isNotEmpty()) return@withTransaction EditResult.Overlap
            val undo = snapshotRange(start, end)
            val visit = Visit(
                placeId = placeId, start = start, end = end, zoneId = zoneId, frozen = true,
                origin = VisitOrigin.MANUAL, confidence = 1f, updatedAt = clock.nowMs(),
            )
            visits.upsert(visit)
            unfreezeTripsOver(start, end)
            rewindCheckpoint(start)
            EditResult.Ok(undo, id = visit.id)
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
        snapshot.range?.let { rewindCheckpoint(it.first) }
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

    private companion object {
        const val PAD_MS = 1L
    }
}
