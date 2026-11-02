package me.tarry.app.summary

import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.settings.RoundingMode
import me.tarry.core.data.settings.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Pure Summary maths (phase 7 step 3): job blocks over a date range, split into local-day pieces, billed with the
 * rounding rules, plus the visits and trips no block covers. No Android or Compose imports. Ranges are half-open
 * `[start, end)` in UTC epoch ms; days come from the zone, so a DST day is 23 or 25 hours long.
 */

private const val MINUTE_MS = 60_000L

/** How billed time is worked out. [incrementMin] 0 = no rounding, [minimumMin] 0 = no minimum. */
data class BillingRules(
    val incrementMin: Int = 0,
    val mode: RoundingMode = RoundingMode.NEAREST,
    val minimumMin: Int = 0,
    /** On: a job's billable time on one local day is added up first, then rounded and topped up once. Off: per block. */
    val perDay: Boolean = false,
) {
    /**
     * Billed time of one billing unit with [rawMs] of billable time. Rounds to the increment first (NEAREST rounds half
     * up, UP rounds up any part of a step), then applies the minimum, even when NEAREST rounded down to 0. A unit with
     * no time is not billed at all.
     */
    fun billedMs(rawMs: Long): Long {
        if (rawMs <= 0L) return 0L
        val step = incrementMin * MINUTE_MS
        val rounded = when {
            step <= 0L -> rawMs
            mode == RoundingMode.UP -> (rawMs + step - 1) / step * step
            else -> (rawMs + step / 2) / step * step
        }
        return maxOf(rounded, minimumMin * MINUTE_MS)
    }
}

fun Settings.billingRules() = BillingRules(roundingIncrementMin, roundingMode, minBillableMinutes, roundDailyTotalPerJob)

/** Inclusive local dates. */
data class SummaryRange(val from: LocalDate, val to: LocalDate) {
    init {
        require(!to.isBefore(from)) { "Range ends before it starts: $from..$to" }
    }

    /** `[start of from, start of to + 1)` in [zone], as epoch ms. */
    fun bounds(zone: ZoneId): Pair<Long, Long> =
        from.atStartOfDay(zone).toInstant().toEpochMilli() to to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Everything the calculator reads. [jobs] are live jobs (archived included); [blocks] are live blocks. */
data class SummaryInput(
    val range: SummaryRange,
    val zone: ZoneId,
    val blocks: List<JobBlock>,
    val jobs: List<Job>,
    val visits: List<Visit>,
    val trips: List<Trip>,
    /** Ids of places the user ignores; their visits are never unlabelled time. */
    val ignoredPlaceIds: Set<String>,
    val rules: BillingRules,
)

/**
 * One block clipped to the range and to one local day. [billedMs] is the piece's own billed time in per-block mode
 * (0 when not billable); null in per-day mode, where billing happens on the day (see [DayBilled]).
 */
data class BlockPiece(
    val blockId: String,
    val date: LocalDate,
    val start: Long,
    val end: Long,
    val billable: Boolean,
    val notes: String?,
    val billedMs: Long?,
) {
    val durationMs: Long get() = end - start
}

/** A job's billable time on one local day, and what it bills as. */
data class DayBilled(val date: LocalDate, val billableMs: Long, val billedMs: Long)

/** One job's row: over the whole range ("by job") or one day ("by day"). */
data class JobSummary(
    val jobId: String,
    val name: String,
    val colorArgb: Int,
    val archived: Boolean,
    val blockCount: Int,
    /** Raw clipped time of every piece, billable or not. */
    val totalMs: Long,
    /** Raw clipped time of billable pieces. */
    val billableMs: Long,
    val billedMs: Long,
    val pieces: List<BlockPiece>,
    val days: List<DayBilled>,
)

/** One local day's job rows, sorted like the by-job rows. */
data class DaySection(val date: LocalDate, val rows: List<JobSummary>) {
    val billedMs: Long get() = rows.sumOf { it.billedMs }
}

data class SummaryResult(
    val byJob: List<JobSummary>,
    val byDay: List<DaySection>,
    /** Sum of every job's billed time. */
    val billedMs: Long,
    /** Wall-clock time any block covers; overlapping blocks count once. */
    val onClockMs: Long,
    /** Visit time at non-ignored places that no block covers. */
    val unlabelledOnSiteMs: Long,
    /** Trip time that no block covers. */
    val unlabelledTravelMs: Long,
) {
    val hasBlocks: Boolean get() = byJob.isNotEmpty()
    val hasUnlabelled: Boolean get() = unlabelledOnSiteMs > 0 || unlabelledTravelMs > 0

    companion object {
        val Empty = SummaryResult(emptyList(), emptyList(), 0, 0, 0, 0)
    }
}

private data class Interval(val start: Long, val end: Long)

/** Sorted, non-overlapping union of [spans]. */
private fun union(spans: List<Interval>): List<Interval> {
    val out = ArrayList<Interval>()
    for (s in spans.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && s.start <= last.end) {
            if (s.end > last.end) out[out.lastIndex] = Interval(last.start, s.end)
        } else {
            out.add(s)
        }
    }
    return out
}

/** Length of `[start, end)` that lies outside [covered] (a [union] result). */
private fun uncoveredMs(start: Long, end: Long, covered: List<Interval>): Long {
    var free = end - start
    for (c in covered) {
        if (c.start >= end) break
        free -= (minOf(end, c.end) - maxOf(start, c.start)).coerceAtLeast(0)
    }
    return free.coerceAtLeast(0)
}

private val JobOrder = compareByDescending<JobSummary> { it.billedMs }.thenBy { it.name.lowercase() }.thenBy { it.jobId }

fun calculateSummary(input: SummaryInput): SummaryResult {
    val (rangeStart, rangeEnd) = input.range.bounds(input.zone)
    val jobsById = input.jobs.associateBy { it.id }
    val live = input.blocks.filter { !it.deleted && it.jobId in jobsById }

    // 1. Clip to the range, then split at each local midnight.
    class Piece(val jobId: String, val piece: BlockPiece)
    val pieces = ArrayList<Piece>()
    for (b in live) {
        val start = maxOf(b.start, rangeStart)
        val end = minOf(b.end, rangeEnd)
        if (start >= end) continue
        var cursor = start
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(input.zone).toLocalDate()
            val midnight = date.plusDays(1).atStartOfDay(input.zone).toInstant().toEpochMilli()
            val pieceEnd = minOf(end, midnight)
            pieces.add(Piece(b.jobId, BlockPiece(b.id, date, cursor, pieceEnd, b.billable, b.notes, billedMs = null)))
            cursor = pieceEnd
        }
    }

    // 2. Bill. Per block: each billable piece alone. Per day: a job's billable pieces on one day, once.
    val rules = input.rules
    fun rowFor(jobId: String, ps: List<BlockPiece>): JobSummary {
        val job = jobsById.getValue(jobId)
        val dayBilled = ps.filter { it.billable }.groupBy { it.date }.toSortedMap().map { (date, day) ->
            val raw = day.sumOf { it.durationMs }
            val billed = if (rules.perDay) rules.billedMs(raw) else day.sumOf { rules.billedMs(it.durationMs) }
            DayBilled(date, raw, billed)
        }
        val shown = ps.map { p ->
            p.copy(billedMs = if (rules.perDay) null else if (p.billable) rules.billedMs(p.durationMs) else 0L)
        }.sortedWith(compareBy({ it.start }, { it.blockId }))
        return JobSummary(
            jobId = jobId,
            name = job.name,
            colorArgb = job.colorArgb,
            archived = job.archived,
            blockCount = ps.map { it.blockId }.distinct().size,
            totalMs = ps.sumOf { it.durationMs },
            billableMs = ps.filter { it.billable }.sumOf { it.durationMs },
            billedMs = dayBilled.sumOf { it.billedMs },
            pieces = shown,
            days = dayBilled,
        )
    }

    // 3. Groups.
    val byJob = pieces.groupBy({ it.jobId }, { it.piece }).map { (jobId, ps) -> rowFor(jobId, ps) }.sortedWith(JobOrder)
    val byDay = pieces.groupBy { it.piece.date }.toSortedMap().map { (date, day) ->
        DaySection(date, day.groupBy({ it.jobId }, { it.piece }).map { (jobId, ps) -> rowFor(jobId, ps) }.sortedWith(JobOrder))
    }

    // 4. Grand totals.
    val clock = union(pieces.map { Interval(it.piece.start, it.piece.end) })

    // 5. Unlabelled: the part of visits and trips no (included) block covers.
    val blockUnion = union(live.map { Interval(it.start, it.end) })
    fun uncovered(start: Long, end: Long): Long {
        val s = maxOf(start, rangeStart)
        val e = minOf(end, rangeEnd)
        return if (s >= e) 0L else uncoveredMs(s, e, blockUnion)
    }
    return SummaryResult(
        byJob = byJob,
        byDay = byDay,
        billedMs = byJob.sumOf { it.billedMs },
        onClockMs = clock.sumOf { it.end - it.start },
        unlabelledOnSiteMs = input.visits
            .filter { !it.deleted && (it.placeId == null || it.placeId !in input.ignoredPlaceIds) }
            .sumOf { uncovered(it.start, it.end) },
        unlabelledTravelMs = input.trips.filter { !it.deleted }.sumOf { uncovered(it.start, it.end) },
    )
}
