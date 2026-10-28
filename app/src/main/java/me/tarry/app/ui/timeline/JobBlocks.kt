package me.tarry.app.ui.timeline

import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.JobBlockRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/*
 * Pure job block logic for the day timeline (phase 6b): lanes, coverage, suggestions and range helpers.
 * No Android or Compose imports. All ranges are half-open `[start, end)` in UTC epoch ms, so blocks that merely
 * touch do not overlap.
 */

/** Tunables for job blocks. */
data class JobBlockConfig(
    /** A block of the same job this close (or closer) to a segment is extended instead of a new block being created. */
    val joinWindowMs: Long = 15 * 60_000L,
    /** Length of the range "Add job" prefills. */
    val defaultLengthMs: Long = 60 * 60_000L,
    /** On a day other than today, "Add job" looks for the first free whole hour from this local hour. */
    val searchStartHour: Int = 8,
    /** ...and falls back to this local hour when every hour from [searchStartHour] is covered. */
    val fallbackStartHour: Int = 9,
    /** Today's "Add job" starts at the current time rounded down to this many minutes. */
    val roundingMinutes: Int = 15,
    /** Drag-to-create on the canvas snaps both ends to this many minutes; it is also the shortest range a drag makes. */
    val snapMinutes: Int = 15,
    /**
     * Height of the grab zone at a block's top and bottom edge, in dp. A touch that starts there and moves vertically
     * resizes; a block shorter than three zones is split in half instead (upper half = start, lower half = end).
     */
    val edgeZoneDp: Float = 16f,
)

/** A visit or trip as the logic sees it. */
data class Segment(val id: String, val start: Long, val end: Long)

/** Side-by-side position of an overlapping block: [index] of [count] lanes in its cluster. */
data class Lane(val index: Int, val count: Int)

private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean = aStart < bEnd && bStart < aEnd

/**
 * Greedy interval-graph lane assignment by start time. A cluster of transitively overlapping blocks shares one
 * `count` (the lanes the cluster needs); a block that overlaps nothing gets `Lane(0, 1)`.
 */
fun jobLanes(blocks: List<JobBlock>): Map<String, Lane> = assignLanes(blocks.map { LaneSpan(it.id, it.start, it.end) })

/** Anything that takes a lane: a block, or a suggestion drawn beside the blocks. */
data class LaneSpan(val id: String, val start: Long, val end: Long)

/** The lane algorithm of [jobLanes] over plain spans, so suggestions can share lanes with blocks. */
fun assignLanes(blocks: List<LaneSpan>): Map<String, Lane> {
    val result = HashMap<String, Lane>(blocks.size)
    var cluster = ArrayList<Pair<String, Int>>()
    val laneEnds = ArrayList<Long>()
    var clusterEnd = Long.MIN_VALUE
    fun flush() {
        val count = laneEnds.size
        cluster.forEach { (id, index) -> result[id] = Lane(index, count) }
        cluster = ArrayList()
        laneEnds.clear()
    }
    for (b in blocks.sortedWith(compareBy({ it.start }, { it.end }, { it.id }))) {
        if (cluster.isNotEmpty() && b.start >= clusterEnd) flush()
        val free = laneEnds.indexOfFirst { it <= b.start }
        val lane = if (free >= 0) free else laneEnds.size.also { laneEnds.add(b.end) }
        laneEnds[lane] = b.end
        cluster.add(b.id to lane)
        clusterEnd = if (cluster.size == 1) b.end else maxOf(clusterEnd, b.end)
    }
    if (cluster.isNotEmpty()) flush()
    return result
}

/** Which blocks overlap each visit and trip (any overlap counts). Segments with none map to an empty list. */
class Coverage(val byVisit: Map<String, List<String>>, val byTrip: Map<String, List<String>>) {
    /** A visit needs review when its place is not ignored and no block overlaps it. */
    fun needsReview(visitId: String, placeIgnored: Boolean): Boolean = !placeIgnored && byVisit[visitId].isNullOrEmpty()
}

fun coverage(visits: List<Segment>, trips: List<Segment>, blocks: List<JobBlock>): Coverage {
    fun cover(segments: List<Segment>) = segments.associate { s ->
        s.id to blocks.filter { overlaps(s.start, s.end, it.start, it.end) }.map { it.id }
    }
    return Coverage(cover(visits), cover(trips))
}

/** A faint "add this job" offer for a detected visit at a place with a default job. Never stored, never billed. */
data class JobSuggestion(val visitId: String, val jobId: String, val start: Long, val end: Long)

/**
 * One suggestion per live DETECTED visit whose place has a default job and which no block of that job overlaps.
 * Manual and edited-by-hand visits (origin MANUAL) and visits at ignored places never get one. A (visit id, job id) pair in
 * [declined] is skipped; another job at the same visit is still offered.
 */
fun suggestions(
    visits: List<Visit>,
    placesById: Map<String, Place>,
    blocks: List<JobBlock>,
    declined: Set<Pair<String, String>> = emptySet(),
): List<JobSuggestion> =
    visits.mapNotNull { v ->
        if (v.deleted || v.origin != VisitOrigin.DETECTED) return@mapNotNull null
        val place = v.placeId?.let(placesById::get) ?: return@mapNotNull null
        // You don't bill an ignored place, so it is never offered a job.
        if (place.ignored) return@mapNotNull null
        val jobId = place.defaultJobId ?: return@mapNotNull null
        if ((v.id to jobId) in declined) return@mapNotNull null
        val covered = blocks.any { it.jobId == jobId && overlaps(v.start, v.end, it.start, it.end) }
        if (covered) null else JobSuggestion(v.id, jobId, v.start, v.end)
    }

/**
 * The range a long-press-and-drag on the job column makes, from the time pressed ([anchorMs]) to the time under the
 * finger now ([currentMs]). Both are snapped to [JobBlockConfig.snapMinutes] from [dayStart]; the earlier is the start.
 * A press without a drag (both snap to the same time) gives [JobBlockConfig.defaultLengthMs] from there. The range
 * stays inside `[dayStart, dayEnd]`.
 */
internal fun dragJobRange(
    anchorMs: Long,
    currentMs: Long,
    dayStart: Long,
    dayEnd: Long,
    config: JobBlockConfig = JobBlockConfig(),
): Span {
    val step = config.snapMinutes * 60_000L
    fun snap(ms: Long) = (dayStart + Math.floorDiv(ms - dayStart + step / 2, step) * step).coerceIn(dayStart, dayEnd)
    val a = snap(anchorMs)
    val b = snap(currentMs)
    val start = minOf(minOf(a, b), dayEnd - step)
    val length = if (a == b) config.defaultLengthMs else abs(a - b)
    return Span(start, minOf(start + length, dayEnd))
}

private fun snapTo(ms: Long, dayFrom: Long, step: Long): Long = dayFrom + Math.floorDiv(ms - dayFrom + step / 2, step) * step

/**
 * A block moved by [deltaMs] (a drag, or the "move earlier/later" accessibility actions): the start is snapped to
 * [JobBlockConfig.snapMinutes] counted from [dayFrom] (the local midnight, so a DST day still lands on wall-clock quarter
 * hours) and the duration is kept. The block stays inside `[dayFrom, dayTo]`. No movement returns the range unchanged.
 */
internal fun moveRange(
    start: Long,
    end: Long,
    deltaMs: Long,
    dayFrom: Long,
    dayTo: Long,
    config: JobBlockConfig = JobBlockConfig(),
): Span {
    if (deltaMs == 0L) return Span(start, end)
    val step = config.snapMinutes * 60_000L
    val length = end - start
    val moved = snapTo(start + deltaMs, dayFrom, step).coerceAtMost(dayTo - length).coerceAtLeast(dayFrom)
    return Span(moved, moved + length)
}

/**
 * A block with one [edge] dragged to [newEdgeMs]: snapped like [moveRange], never leaving the block shorter than
 * [JobBlockConfig.snapMinutes] (so an edge cannot cross the other), and inside `[dayFrom, dayTo]`. The other edge is untouched.
 */
internal fun resizeRange(
    edge: ResizeEdge,
    start: Long,
    end: Long,
    newEdgeMs: Long,
    dayFrom: Long,
    dayTo: Long,
    config: JobBlockConfig = JobBlockConfig(),
): Span {
    val step = config.snapMinutes * 60_000L
    val snapped = snapTo(newEdgeMs, dayFrom, step)
    return when (edge) {
        ResizeEdge.Start -> Span(snapped.coerceAtMost(end - step).coerceAtLeast(dayFrom), end)
        ResizeEdge.End -> Span(start, snapped.coerceAtLeast(start + step).coerceAtMost(dayTo))
    }
}

/**
 * Which edge a touch at [yDp] (from the top of the touch box, which is [touchHeightDp] tall and holds the block centred)
 * grabs, or null for the middle. A block shorter than three [zoneDp] is split in half so both edges stay reachable.
 */
internal fun edgeAt(yDp: Float, blockHeightDp: Float, touchHeightDp: Float, zoneDp: Float): ResizeEdge? {
    if (blockHeightDp < zoneDp * 3) return if (yDp < touchHeightDp / 2) ResizeEdge.Start else ResizeEdge.End
    val top = (touchHeightDp - blockHeightDp) / 2
    return when {
        yDp < top + zoneDp -> ResizeEdge.Start
        yDp > top + blockHeightDp - zoneDp -> ResizeEdge.End
        else -> null
    }
}

/** The block to save plus the ids of other same-job blocks it swallowed (soft-delete them in the same transaction). */
data class BlockMerge(val block: JobBlock, val deleteIds: List<String>)

/**
 * Keeps blocks of one job from overlapping (overlaps across different jobs are allowed). Unions [candidate] with every
 * live same-job block of [dayBlocks] it really overlaps (half-open, so touching does not merge), repeating until nothing
 * more overlaps. The kept block is [candidate] when its id is already in [dayBlocks], else the earliest block of the
 * union; it carries the union range and its own billable. Notes are the distinct non-blank notes joined with a newline.
 * [dayBlocks] must cover the candidate's range (blocks can cross midnight).
 */
fun mergeSameJob(candidate: JobBlock, dayBlocks: List<JobBlock>): BlockMerge {
    val others = dayBlocks.filter { !it.deleted && it.jobId == candidate.jobId && it.id != candidate.id }
    val cluster = ArrayList<JobBlock>()
    var start = candidate.start
    var end = candidate.end
    do {
        val next = others.firstOrNull { b -> b !in cluster && overlaps(start, end, b.start, b.end) }?.also {
            cluster.add(it)
            start = minOf(start, it.start)
            end = maxOf(end, it.end)
        }
    } while (next != null)
    if (cluster.isEmpty()) return BlockMerge(candidate, emptyList())
    val sorted = cluster.sortedWith(compareBy({ it.start }, { it.id }))
    val candidateExists = dayBlocks.any { it.id == candidate.id }
    val kept = if (candidateExists) candidate else sorted.first()
    val ordered = listOf(kept) + (if (candidateExists) emptyList() else listOf(candidate)) + sorted.filter { it.id != kept.id }
    val notes = ordered.mapNotNull { it.notes?.takeIf(String::isNotBlank) }.distinct().joinToString("\n").ifEmpty { null }
    return BlockMerge(kept.copy(start = start, end = end, notes = notes), sorted.filter { it.id != kept.id }.map { it.id })
}

/**
 * Saves [candidate] merged with the same-job blocks it overlaps ([mergeSameJob]) in one transaction and returns what was
 * saved. Reads the blocks around the candidate's range, widened by [windowMs] so a neighbour is never missed.
 */
suspend fun JobBlockRepository.saveMerged(candidate: JobBlock, windowMs: Long = JobBlockConfig().joinWindowMs): JobBlock =
    saveMergedUndoable(candidate, windowMs).saved

/** What [saveMergedUndoable] did, enough to put it all back: the block as it was ([before], null when new) and what the merge swallowed. */
class MergedSave(val saved: JobBlock, val before: JobBlock?, val swallowedIds: List<String>) {
    /** Undo, atomically: the saved block goes back to [before] (or is deleted when it was new) and swallowed blocks return. */
    suspend fun undo(blocks: JobBlockRepository) = blocks.revert(before, saved.id, swallowedIds)
}

/** [saveMerged] that also reports how to undo it. */
suspend fun JobBlockRepository.saveMergedUndoable(candidate: JobBlock, windowMs: Long = JobBlockConfig().joinWindowMs): MergedSave {
    val merged = mergeSameJob(candidate, overlapping(candidate.start - windowMs, candidate.end + windowMs))
    val before = byId(merged.block.id)
    replace(merged.block, merged.deleteIds)
    return MergedSave(merged.block, before, merged.deleteIds)
}

/** Earliest start to latest end of [segments]; null for an empty selection. */
internal fun assignRange(segments: List<Segment>): Span? =
    if (segments.isEmpty()) null else Span(segments.minOf { it.start }, segments.maxOf { it.end })

sealed interface AddJobResult {
    /** Widen the existing block [blockId] to `[start, end)` (the union with the segment). */
    data class Extend(val blockId: String, val start: Long, val end: Long) : AddJobResult

    /** No block of that job is near enough: create one equal to the segment. */
    data class Create(val start: Long, val end: Long) : AddJobResult
}

/**
 * Adds [jobId] to [segment]: extends the nearest block of the same job that overlaps or lies within
 * [JobBlockConfig.joinWindowMs] of it, otherwise creates a block equal to the segment.
 */
internal fun addJobToSegment(
    segment: Span,
    jobId: String,
    blocks: List<JobBlock>,
    config: JobBlockConfig = JobBlockConfig(),
): AddJobResult {
    fun gap(b: JobBlock): Long = maxOf(0L, b.start - segment.end, segment.start - b.end)
    val near = blocks.filter { it.jobId == jobId && gap(it) <= config.joinWindowMs }
        .minWithOrNull(compareBy<JobBlock>({ gap(it) }, { it.start }, { it.id }))
        ?: return AddJobResult.Create(segment.start, segment.end)
    return AddJobResult.Extend(near.id, minOf(near.start, segment.start), maxOf(near.end, segment.end))
}

/**
 * The range "Add job" prefills on [date], as `[start, end)` epoch ms. Today: [nowMs] rounded down to
 * [JobBlockConfig.roundingMinutes], [JobBlockConfig.defaultLengthMs] long. Any other day: the first whole local hour from
 * [JobBlockConfig.searchStartHour] that no block overlaps, else [JobBlockConfig.fallbackStartHour]. Never past the end of
 * the day.
 */
internal fun defaultJobRange(
    date: LocalDate,
    isToday: Boolean,
    nowMs: Long,
    zone: ZoneId,
    blocks: List<JobBlock>,
    config: JobBlockConfig = JobBlockConfig(),
): Span {
    val (dayStart, dayEnd) = dayBounds(date, zone)
    fun spanFrom(start: Long) = Span(start, minOf(start + config.defaultLengthMs, dayEnd))
    if (isToday) {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val rounded = now.withMinute(now.minute / config.roundingMinutes * config.roundingMinutes).withSecond(0).withNano(0)
        return spanFrom(maxOf(rounded.toInstant().toEpochMilli(), dayStart))
    }
    fun hourStart(hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    for (hour in config.searchStartHour until 24) {
        val candidate = spanFrom(hourStart(hour))
        if (candidate.start >= dayEnd) break
        if (blocks.none { !it.deleted && overlaps(candidate.start, candidate.end, it.start, it.end) }) return candidate
    }
    return spanFrom(hourStart(config.fallbackStartHour))
}

/** The first block (by start) that overlaps a visit, or null: the job a map pin or scrubber segment is coloured by. */
internal fun coveringJob(start: Long, end: Long, blocks: List<JobBlockItem>): JobBlockItem? =
    blocks.filter { overlaps(start, end, it.start, it.end) }.minWithOrNull(compareBy({ it.start }, { it.id }))
