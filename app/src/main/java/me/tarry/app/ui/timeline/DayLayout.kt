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
    /** Used only when there are no items and no window. */
    val emptyDayStartHour: Int = 7,
    val emptyDayEndHour: Int = 17,
)

data class HourMark(val epochMs: Long, val topDp: Float)

sealed interface Placed {
    val topDp: Float
    val heightDp: Float
}

/** A visit or gap. [foldedTrips] are trips that had no room of their own and are summarised in this visit. */
data class PlacedBlock(
    val item: TimelineItem,
    override val topDp: Float,
    override val heightDp: Float,
    val compact: Boolean,
    val foldedTrips: List<TimelineItem.Trip>,
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
    val clamped = items
        .map { Clamped(it, it.start.coerceIn(dayStart, dayEnd), it.end.coerceIn(dayStart, dayEnd)) }
        .filter { it.end > it.start }
        .sortedBy { it.start }

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

    fun block(c: Clamped, top: Float, folded: List<TimelineItem.Trip>) = PlacedBlock(
        item = c.item,
        topDp = top,
        heightDp = maxOf(dp(c.start, c.end), minBlock),
        compact = c.end - c.start < compactUnderMs,
        foldedTrips = folded,
    )

    val blocks = mutableListOf<PlacedBlock>()
    val trips = mutableListOf<PlacedTrip>()

    if (!hasVisit) {
        // Nothing to fold into: every trip keeps its own row and pushes later items down.
        var cur = 0f
        for (c in clamped) {
            val top = maxOf(dp(canvasStart, c.start), cur)
            if (c.item is TimelineItem.Trip) {
                trips += PlacedTrip(c.item, top, tripRow)
                cur = top + tripRow
            } else {
                val b = block(c, top, emptyList())
                blocks += b
                cur = top + b.heightDp
            }
        }
    } else {
        // Pass 1: blocks (visits and gaps), never overlapping each other.
        val blockSources = clamped.filter { it.item !is TimelineItem.Trip }
        val tops = ArrayList<Float>()
        val heights = ArrayList<Float>()
        var bottom = 0f
        for (c in blockSources) {
            val top = maxOf(dp(canvasStart, c.start), bottom)
            val h = maxOf(dp(c.start, c.end), minBlock)
            tops += top
            heights += h
            bottom = top + h
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
            var cur = if (k == 0) 0f else tops[k - 1] + heights[k - 1]
            val nextTop = if (k < blockSources.size) tops[k] else Float.POSITIVE_INFINITY
            for (c in group) {
                val trip = c.item as TimelineItem.Trip
                val top = maxOf(dp(canvasStart, c.start), cur)
                if (top + tripRow <= nextTop) {
                    trips += PlacedTrip(trip, top, tripRow)
                    cur = top + tripRow
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
            )
        }
    }

    val lastBottom = maxOf(
        blocks.maxOfOrNull { it.topDp + it.heightDp } ?: 0f,
        trips.maxOfOrNull { it.topDp + it.heightDp } ?: 0f,
    )
    val nowDp = if (isToday && now >= canvasStart && now <= canvasEnd) dp(canvasStart, now) else null

    return DayLayout(
        canvasStart = canvasStart,
        canvasEnd = canvasEnd,
        heightDp = maxOf(dp(canvasStart, canvasEnd), lastBottom),
        scale = scale,
        hourMarks = marks,
        blocks = blocks,
        trips = trips,
        nowDp = nowDp,
    )
}
