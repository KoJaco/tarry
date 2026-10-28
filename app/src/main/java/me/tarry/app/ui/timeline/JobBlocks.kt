package me.tarry.app.ui.timeline

import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin

/*
 * Pure job block logic for the day timeline (phase 6b): lanes, coverage, suggestions and range helpers.
 * No Android or Compose imports. All ranges are half-open `[start, end)` in UTC epoch ms, so blocks that merely
 * touch do not overlap.
 */

/** Tunables for job blocks. */
data class JobBlockConfig(
    /** A block of the same job this close (or closer) to a segment is extended instead of a new block being created. */
    val joinWindowMs: Long = 15 * 60_000L,
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
fun jobLanes(blocks: List<JobBlock>): Map<String, Lane> {
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
 * Manual and edited-by-hand visits (origin MANUAL) never get one.
 */
fun suggestions(visits: List<Visit>, placesById: Map<String, Place>, blocks: List<JobBlock>): List<JobSuggestion> =
    visits.mapNotNull { v ->
        if (v.deleted || v.origin != VisitOrigin.DETECTED) return@mapNotNull null
        val jobId = v.placeId?.let(placesById::get)?.defaultJobId ?: return@mapNotNull null
        val covered = blocks.any { it.jobId == jobId && overlaps(v.start, v.end, it.start, it.end) }
        if (covered) null else JobSuggestion(v.id, jobId, v.start, v.end)
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
