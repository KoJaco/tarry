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
    /** Least height (not font-scaled) of a trip row, so the chip is a 48 dp touch target. */
    val tripMinTouchDp: Float = 48f,
    /** Minimum vertical space between consecutive items, so adjacent blocks never touch. Not font-scaled. */
    val itemGapDp: Float = 4f,
    /** Minimum height of an outage marker drawn inside a visit. Not font-scaled. */
    val innerGapMinDp: Float = 4f,
    /** Used only when there are no items and no window. */
    val emptyDayStartHour: Int = 7,
    val emptyDayEndHour: Int = 17,
    /**
     * Outage clusters: two gap blocks merge into one "Patchy tracking" block when everything between them is a trip or
     * a visit shorter than this. A longer visit between them keeps them apart. Absolute milliseconds, so DST is moot.
     */
    val outageClusterMaxSeparationMs: Long = 10 * 60_000L,
)

data class HourMark(val epochMs: Long, val topDp: Float)

sealed interface Placed {
    val topDp: Float
    val heightDp: Float
}

/** A health gap drawn as a band inside a visit. [offsetDp] is relative to the block top. */
data class InnerGap(val gap: TimelineItem.Gap, val offsetDp: Float, val heightDp: Float)

/**
 * A visit or gap. [foldedTrips] is always empty now (every trip has its own row); the field is kept for the spoken description.
 * [innerGaps] are outages that fall inside this visit (see [layoutDay]).
 */
data class PlacedBlock(
    val item: TimelineItem,
    override val topDp: Float,
    override val heightDp: Float,
    val compact: Boolean,
    val foldedTrips: List<TimelineItem.Trip>,
    val innerGaps: List<InnerGap> = emptyList(),
    /**
     * Non-empty for an outage cluster: [item] is then a synthetic gap (id `cluster:<first gap id>`) spanning the run,
     * and these are the real gaps it stands for, in time order.
     */
    val cluster: List<TimelineItem.Gap> = emptyList(),
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
 * Outage clusters: a run of two or more gap blocks separated only by trips, or by visits shorter than
 * [DayLayoutParams.outageClusterMaxSeparationMs], becomes one cluster block (see [PlacedBlock.cluster]); the trips
 * between them are dropped. Short visits inside the run still draw on top of the cluster. A cluster without visits
 * inside is stacked like any other block; one with visits inside is a band at its true time span that they overlay.
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
    // A trip row is also the touch target of the trip chip: never below tripMinTouchDp, whatever the font scale.
    val tripRow = maxOf(params.tripRowDp * fs, params.tripMinTouchDp)
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
    val nonInner = allClamped.filter { it !in innerSet }

    class Band(val synthetic: TimelineItem.Gap, val gaps: List<TimelineItem.Gap>, val start: Long, val end: Long, val visits: List<TimelineItem>)
    val bands = mutableListOf<Band>()
    val clamped: List<Clamped> = run {
        val out = ArrayList<Clamped>()
        var i = 0
        while (i < nonInner.size) {
            val first = nonInner[i]
            if (first.item !is TimelineItem.Gap) {
                out += first
                i++
                continue
            }
            var lastGap = i
            var j = i + 1
            scan@ while (j < nonInner.size) {
                val s = nonInner[j]
                when (s.item) {
                    is TimelineItem.Gap -> lastGap = j
                    is TimelineItem.Visit -> if (s.end - s.start >= params.outageClusterMaxSeparationMs) break@scan
                    is TimelineItem.Trip -> Unit
                }
                j++
            }
            val members = nonInner.subList(i, lastGap + 1)
            val gaps = members.filter { it.item is TimelineItem.Gap }
            if (gaps.size < 2) {
                out += first
                i++
                continue
            }
            val realGaps = gaps.map { it.item as TimelineItem.Gap }
            val synthetic = TimelineItem.Gap(
                id = "cluster:${realGaps.first().id}",
                start = realGaps.first().start,
                end = realGaps.maxOf { it.end },
                cause = realGaps.first().cause,
            )
            val spanEnd = gaps.maxOf { it.end }
            val inside = members.filter { it.item is TimelineItem.Visit }
            if (inside.isEmpty()) {
                out += Clamped(synthetic, gaps.first().start, spanEnd)
                bands += Band(synthetic, realGaps, gaps.first().start, spanEnd, emptyList())
            } else {
                bands += Band(synthetic, realGaps, gaps.first().start, spanEnd, inside.map { it.item })
                out += inside
            }
            i = lastGap + 1
        }
        out
    }
    val stackedClusters: Map<TimelineItem, Band> = bands.filter { it.visits.isEmpty() }.associateBy { it.synthetic }

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
        bands.forEach {
            rawStart = minOf(rawStart, it.start)
            rawEnd = maxOf(rawEnd, it.end)
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
        cluster = stackedClusters[c.item]?.gaps.orEmpty(),
    )

    val blocks = mutableListOf<PlacedBlock>()
    val trips = mutableListOf<PlacedTrip>()

    // One flow top to bottom: every item sits at its true time or just below the previous item, never closer than the
    // item gap. Every trip keeps its own row (dogfood-2: a trip under ~23 min used to be folded away and invisible), so
    // a short trip between two visits pushes the later ones down rather than hiding; visits never yield to overlap.
    var cur = 0f
    for ((i, c) in clamped.withIndex()) {
        val top = maxOf(dp(canvasStart, c.start), cur)
        if (c.item is TimelineItem.Trip) {
            trips += PlacedTrip(c.item, top, tripRow)
            cur = top + tripRow + gap
        } else {
            // A block is trimmed against the next block's true start; trips between them are placed below it.
            val nextBlockStart = (i + 1 until clamped.size).firstOrNull { clamped[it].item !is TimelineItem.Trip }
                ?.let { clamped[it].start }
            val b = block(c, top, nextBlockStart, emptyList())
            blocks += b
            cur = top + b.heightDp + gap
        }
    }
    // Clusters with visits inside: a band at the true span, drawn first so the visits overlay it.
    for (b in bands.filter { it.visits.isNotEmpty() }.asReversed()) {
        val top = dp(canvasStart, b.start)
        val innerBottom = blocks.filter { it.item in b.visits }.maxOfOrNull { it.topDp + it.heightDp } ?: 0f
        val height = maxOf(dp(canvasStart, b.end), innerBottom, top + minBlock) - top
        blocks.add(0, PlacedBlock(b.synthetic, top, height, compact = false, foldedTrips = emptyList(), cluster = b.gaps))
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
