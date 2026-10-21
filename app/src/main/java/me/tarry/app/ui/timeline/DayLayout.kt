package me.tarry.app.ui.timeline

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Tunables for [layoutDay]. All sizes are dp. */
data class DayLayoutParams(
    val dpPerHour: Float = 72f,
    /** Scaled by max(1, fontScale), like [tripRowDp], because the text inside a block grows with the font. */
    val minBlockDp: Float = 28f,
    val compactUnderMinutes: Int = 30,
    /** Scaled by max(1, fontScale). */
    val tripRowDp: Float = 24f,
    /** Minimum vertical space between consecutive items, so adjacent blocks never touch. Not font-scaled. */
    val itemGapDp: Float = 4f,
    /** Minimum height of an outage marker drawn inside a visit. Not font-scaled. */
    val innerGapMinDp: Float = 4f,
    /** Used only when there are no items and no window. */
    val emptyDayStartHour: Int = 7,
    val emptyDayEndHour: Int = 17,
)

data class HourMark(val epochMs: Long, val topDp: Float)

sealed interface Placed {
    val topDp: Float
    val heightDp: Float
}

/** A health gap drawn as a band inside a visit. [offsetDp] is relative to the block top. */
data class InnerGap(val gap: TimelineItem.Gap, val offsetDp: Float, val heightDp: Float)

/**
 * A visit or gap. [foldedTrips] are trips that had no room of their own and are summarised in this visit.
 * [innerGaps] are outages that fall inside this visit (see [layoutDay]).
 */
data class PlacedBlock(
    val item: TimelineItem,
    override val topDp: Float,
    override val heightDp: Float,
    val compact: Boolean,
    val foldedTrips: List<TimelineItem.Trip>,
    val innerGaps: List<InnerGap> = emptyList(),
) : Placed

data class PlacedTrip(
    val trip: TimelineItem.Trip,
    override val topDp: Float,
    override val heightDp: Float,
) : Placed

data class DayLayout(
    val canvasStart: Long,
    val canvasEnd: Long,
    val heightDp: Float,
    /** dp per hour actually used. */
    val scale: Float,
    val hourMarks: List<HourMark>,
    val blocks: List<PlacedBlock>,
    val trips: List<PlacedTrip>,
    val nowDp: Float?,
) {
    /** Blocks and trips merged in time (reading) order. */
    fun ordered(): List<Placed> = (blocks + trips).sortedBy { it.topDp }
}

private const val MS_PER_HOUR = 3_600_000.0
private const val MS_PER_MINUTE = 60_000L

private fun floorHour(ms: Long, zone: ZoneId): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).truncatedTo(ChronoUnit.HOURS)

private fun ceilHour(ms: Long, zone: ZoneId): Long {
    val floor = floorHour(ms, zone)
    val floorMs = floor.toInstant().toEpochMilli()
    return if (floorMs == ms) ms else floor.plusHours(1).toInstant().toEpochMilli()
}

/**
 * Lays [items] out on a day canvas. Scale, minimum block height and trip rows all grow with max(1, [fontScale]).
 *
 * Inner gaps: segmentation bridges short outages, so a gap can overlap a visit in time. Each gap is assigned to the
 * visit it overlaps most (clamped to the day); if that overlap is at least half of the gap's duration it is an inner
 * gap of that visit (a band inside the block, not a block of its own). A gap sticking out of the visit by more than
 * half its length stays an ordinary block. Gaps overlapping no visit are ordinary blocks.
 *
 * Now-line: placed on the proportional position, but never above the proportional point of any placed block or trip
 * that started before now, so everything that started is at or above the line.
 *
 * [window] (the schedule window) is accepted but nothing passes it yet: it is deferred to phase 6, when the schedule
 * window is exposed to the UI.
 */
fun layoutDay(
    items: List<TimelineItem>,
    dayBounds: Pair<Long, Long>,
    zone: ZoneId,
    now: Long,
    fontScale: Float,
    params: DayLayoutParams = DayLayoutParams(),
    window: Pair<Long, Long>? = null,
): DayLayout {
    val (dayStart, dayEnd) = dayBounds
    val fs = maxOf(1f, fontScale)
    val scale = params.dpPerHour * fs
    val tripRow = params.tripRowDp * fs
    val minBlock = params.minBlockDp * fs

    // Clamp to the day, drop empties, stable sort by start.
    data class Clamped(val item: TimelineItem, val start: Long, val end: Long)
    val allClamped = items
        .map { Clamped(it, it.start.coerceIn(dayStart, dayEnd), it.end.coerceIn(dayStart, dayEnd)) }
        .filter { it.end > it.start }
        .sortedBy { it.start }

    // Partition gaps: inner gaps of a visit are not placed as blocks.
    val visitsC = allClamped.filter { it.item is TimelineItem.Visit }
    val innerByVisit = HashMap<TimelineItem, MutableList<Clamped>>()
    val innerSet = HashSet<Clamped>()
    for (g in allClamped) {
        if (g.item !is TimelineItem.Gap) continue
        val best = visitsC.maxByOrNull { minOf(it.end, g.end) - maxOf(it.start, g.start) } ?: continue
        val overlap = minOf(best.end, g.end) - maxOf(best.start, g.start)
        if (overlap > 0 && overlap * 2 >= g.end - g.start) {
            innerByVisit.getOrPut(best.item) { mutableListOf() } += g
            innerSet += g
        }
    }
    val clamped = allClamped.filter { it !in innerSet }

    val isToday = now >= dayStart && now < dayEnd
    val win = window?.let { it.first.coerceIn(dayStart, dayEnd) to it.second.coerceIn(dayStart, dayEnd) }

    var rawStart: Long
    var rawEnd: Long
    if (clamped.isEmpty() && win == null) {
        val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(dayStart), zone).toLocalDate()
        rawStart = date.atTime(params.emptyDayStartHour, 0).atZone(zone).toInstant().toEpochMilli()
        rawEnd = date.atTime(params.emptyDayEndHour, 0).atZone(zone).toInstant().toEpochMilli()
    } else {
        rawStart = Long.MAX_VALUE
        rawEnd = Long.MIN_VALUE
        if (clamped.isNotEmpty()) {
            rawStart = clamped.minOf { it.start }
            rawEnd = clamped.maxOf { it.end }
        }
        if (win != null) {
            rawStart = minOf(rawStart, win.first)
            rawEnd = maxOf(rawEnd, win.second)
        }
    }
    if (isToday) {
        rawStart = minOf(rawStart, now)
        rawEnd = maxOf(rawEnd, now)
    }

    val canvasStart = floorHour(rawStart, zone).toInstant().toEpochMilli().coerceAtLeast(dayStart)
    var canvasEnd = ceilHour(rawEnd, zone).coerceAtMost(dayEnd)
    if (canvasEnd <= canvasStart) {
        canvasEnd = floorHour(canvasStart, zone).plusHours(1).toInstant().toEpochMilli().coerceAtMost(dayEnd)
    }

    fun dp(from: Long, to: Long): Float = ((to - from) / MS_PER_HOUR * scale).toFloat()

    val marks = buildList {
        var h = floorHour(canvasStart, zone)
        if (h.toInstant().toEpochMilli() < canvasStart) h = h.plusHours(1)
        while (h.toInstant().toEpochMilli() <= canvasEnd) {
            val ms = h.toInstant().toEpochMilli()
            add(HourMark(ms, dp(canvasStart, ms)))
            h = h.plusHours(1)
        }
    }

    val hasVisit = clamped.any { it.item is TimelineItem.Visit }
    val compactUnderMs = params.compactUnderMinutes * MS_PER_MINUTE

    val gap = params.itemGapDp

    /**
     * Height of a block placed at [top]: its true height (at least the minimum), trimmed at the bottom so a gap is
     * left before the next item's true start, but only as far as the minimum height allows. Where that is not
     * enough, the next item is pushed down instead.
     */
    fun blockHeight(c: Clamped, top: Float, nextStart: Long?): Float {
        val trueHeight = maxOf(dp(c.start, c.end), minBlock)
        if (nextStart == null) return trueHeight
        val room = dp(canvasStart, nextStart) - gap - top
        return if (room >= trueHeight) trueHeight else maxOf(room, minBlock)
    }

    fun innerGapsOf(c: Clamped, height: Float): List<InnerGap> {
        val list = innerByVisit[c.item] ?: return emptyList()
        val dur = (c.end - c.start).toFloat()
        return list.sortedBy { it.start }.map { g ->
            val gs = maxOf(g.start, c.start)
            val ge = minOf(g.end, c.end)
            val h = minOf(maxOf((ge - gs) / dur * height, params.innerGapMinDp), height)
            val off = ((gs - c.start) / dur * height).coerceIn(0f, height - h)
            InnerGap(g.item as TimelineItem.Gap, off, h)
        }
    }

    fun block(c: Clamped, top: Float, nextStart: Long?, folded: List<TimelineItem.Trip>) = PlacedBlock(
        item = c.item,
        topDp = top,
        heightDp = blockHeight(c, top, nextStart),
        compact = c.end - c.start < compactUnderMs,
        foldedTrips = folded,
        innerGaps = innerGapsOf(c, blockHeight(c, top, nextStart)),
    )

    val blocks = mutableListOf<PlacedBlock>()
    val trips = mutableListOf<PlacedTrip>()

    if (!hasVisit) {
        // Nothing to fold into: every trip keeps its own row and pushes later items down.
        var cur = 0f
        for ((i, c) in clamped.withIndex()) {
            val top = maxOf(dp(canvasStart, c.start), cur)
            if (c.item is TimelineItem.Trip) {
                trips += PlacedTrip(c.item, top, tripRow)
                cur = top + tripRow + gap
            } else {
                val b = block(c, top, clamped.getOrNull(i + 1)?.start, emptyList())
                blocks += b
                cur = top + b.heightDp + gap
            }
        }
    } else {
        // Pass 1: blocks (visits and gaps), never overlapping each other and never closer than the item gap.
        val blockSources = clamped.filter { it.item !is TimelineItem.Trip }
        val tops = ArrayList<Float>()
        val heights = ArrayList<Float>()
        var bottom = 0f
        for ((i, c) in blockSources.withIndex()) {
            val top = maxOf(dp(canvasStart, c.start), bottom)
            val h = blockHeight(c, top, blockSources.getOrNull(i + 1)?.start)
            tops += top
            heights += h
            bottom = top + h + gap
        }
        // Group trips by the number of blocks that start before them: group k sits between block k-1 and k.
        val groups = List(blockSources.size + 1) { mutableListOf<Clamped>() }
        var seen = 0
        for (c in clamped) {
            if (c.item is TimelineItem.Trip) groups[seen] += c else seen++
        }
        val folded = List(blockSources.size) { mutableListOf<TimelineItem.Trip>() }
        fun foldTarget(k: Int): Int =
            (k until blockSources.size).firstOrNull { blockSources[it].item is TimelineItem.Visit }
                ?: (k - 1 downTo 0).first { blockSources[it].item is TimelineItem.Visit }
        for ((k, group) in groups.withIndex()) {
            var cur = if (k == 0) 0f else tops[k - 1] + heights[k - 1] + gap
            val nextTop = if (k < blockSources.size) tops[k] else Float.POSITIVE_INFINITY
            for (c in group) {
                val trip = c.item as TimelineItem.Trip
                val top = maxOf(dp(canvasStart, c.start), cur)
                if (top + tripRow + gap <= nextTop) {
                    trips += PlacedTrip(trip, top, tripRow)
                    cur = top + tripRow + gap
                } else {
                    folded[foldTarget(k)] += trip
                }
            }
        }
        for ((i, c) in blockSources.withIndex()) {
            blocks += PlacedBlock(
                item = c.item,
                topDp = tops[i],
                heightDp = heights[i],
                compact = c.end - c.start < compactUnderMs,
                foldedTrips = folded[i].toList(),
                innerGaps = innerGapsOf(c, heights[i]),
            )
        }
    }

    val lastBottom = maxOf(
        blocks.maxOfOrNull { it.topDp + it.heightDp } ?: 0f,
        trips.maxOfOrNull { it.topDp + it.heightDp } ?: 0f,
    )
    var nowDp: Float? = if (isToday && now >= canvasStart && now <= canvasEnd) dp(canvasStart, now) else null
    if (nowDp != null) {
        fun reach(item: TimelineItem, top: Float, height: Float): Float? {
            val s = item.start.coerceIn(dayStart, dayEnd)
            val e = item.end.coerceIn(dayStart, dayEnd)
            if (e <= s || s > now) return null
            return top + height * ((now - s).toFloat() / (e - s)).coerceIn(0f, 1f)
        }
        val reaches = blocks.mapNotNull { reach(it.item, it.topDp, it.heightDp) } +
            trips.mapNotNull { reach(it.trip, it.topDp, it.heightDp) }
        nowDp = maxOf(nowDp, reaches.maxOrNull() ?: nowDp)
    }

    return DayLayout(
        canvasStart = canvasStart,
        canvasEnd = canvasEnd,
        heightDp = maxOf(dp(canvasStart, canvasEnd), lastBottom, nowDp ?: 0f),
        scale = scale,
        hourMarks = marks,
        blocks = blocks,
        trips = trips,
        nowDp = nowDp,
    )
}
