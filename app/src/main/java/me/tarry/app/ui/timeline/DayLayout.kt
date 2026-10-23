package me.tarry.app.ui.timeline

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Tunables for [layoutDay]. All sizes are dp. */
data class DayLayoutParams(
    val dpPerHour: Float = 144f,
    /** Scaled by max(1, fontScale), like [tripRowDp], because the text inside a block grows with the font. */
    val minBlockDp: Float = 28f,
    val compactUnderMinutes: Int = 30,
    /** Scaled by max(1, fontScale). */
    val tripRowDp: Float = 24f,
    /**
     * Most a visit's drawn top or bottom edge is trimmed to make room for a short trip between two visits. Not font-scaled.
     * A trimmed visit never goes below its minimum height.
     */
    val tripTrimMaxDp: Float = 12f,
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
    /** True when even after trimming the neighbours the slot is too small for text: only the mode icon is drawn. */
    val iconOnly: Boolean = false,
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
    /** Offsets (dp from the canvas top) of the :15, :30 and :45 ticks on the rail; the hour marks are in [hourMarks]. */
    val quarterMarks: List<Float> = emptyList(),
) {
    /** Blocks and trips in reading order, by real start. */
    fun ordered(): List<Placed> = (blocks + trips).sortedWith(
        compareBy<Placed> { if (it is PlacedBlock) it.item.start else (it as PlacedTrip).trip.start }.thenBy { it.topDp },
    )
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
 * Lays [items] out on a day canvas on a true linear time scale: every visit, gap and long trip row sits exactly at its real
 * start and end, so hour marks, blocks and the now-line always agree. Scale, minimum block height and trip rows all grow with max(1, [fontScale]).
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
 * Now-line: always at its proportional position.
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

    val quarters = buildList {
        var h = floorHour(canvasStart, zone)
        while (h.toInstant().toEpochMilli() < canvasEnd) {
            val base = h.toInstant().toEpochMilli()
            for (q in 1..3) {
                val ms = base + q * 15 * MS_PER_MINUTE
                if (ms > canvasStart && ms < canvasEnd) add(dp(canvasStart, ms))
            }
            h = h.plusHours(1)
        }
    }

    val compactUnderMs = params.compactUnderMinutes * MS_PER_MINUTE

    /**
     * True time scale: a block's top and bottom are its real start and end on the linear scale, nothing is pushed or
     * trimmed. The only exception is the minimum height of a very short block, which grows downward as far as the next
     * block's true start allows (never below its true height).
     */
    fun blockHeight(c: Clamped, nextStart: Long?): Float {
        val trueHeight = dp(c.start, c.end)
        if (trueHeight >= minBlock) return trueHeight
        val room = if (nextStart == null) minBlock else dp(c.start, nextStart)
        return maxOf(trueHeight, minOf(minBlock, room))
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

    val blocks = mutableListOf<PlacedBlock>()
    val blockSpans = ArrayList<Clamped>()
    val trips = mutableListOf<PlacedTrip>()

    // A short block may grow down only as far as the next item (block or trip) starts, so it never covers a trip.
    for ((i, c) in clamped.withIndex()) {
        if (c.item is TimelineItem.Trip) continue
        val height = blockHeight(c, clamped.getOrNull(i + 1)?.start)
        blockSpans += c
        blocks += PlacedBlock(
            item = c.item,
            topDp = dp(canvasStart, c.start),
            heightDp = height,
            compact = c.end - c.start < compactUnderMs,
            foldedTrips = emptyList(),
            cluster = stackedClusters[c.item]?.gaps.orEmpty(),
        )
    }

    // Trips sit on their true span. One shorter than a trip row needs a slot of that height: it takes free space beside
    // the span first (split evenly), then trims the adjacent blocks' drawn edges (the earlier one's bottom and the later
    // one's top, each by at most tripTrimMaxDp and never below minBlock). If it still does not fit, the slot is all the
    // room there is and only the icon is drawn. Nothing else moves: the hour grid stays true.
    var prevSlotBottom = Float.NEGATIVE_INFINITY
    for (c in clamped) {
        if (c.item !is TimelineItem.Trip) continue
        val top = dp(canvasStart, c.start)
        val height = dp(c.start, c.end)
        val bottom = top + height
        if (height >= tripRow - 0.01f) {
            trips += PlacedTrip(c.item, top, height)
            prevSlotBottom = bottom
            continue
        }
        val centre = top + height / 2
        fun blockBottom(i: Int) = blocks[i].topDp + blocks[i].heightDp
        val earlierIdx = blocks.indices
            .filter { blockBottom(it) <= bottom + 0.01f && (blocks[it].topDp + blockBottom(it)) / 2 <= centre }
            .maxByOrNull { blockBottom(it) }
        val laterIdx = blocks.indices
            .filter { blocks[it].topDp >= top - 0.01f && (blocks[it].topDp + blockBottom(it)) / 2 > centre }
            .minByOrNull { blocks[it].topDp }
        val lowEdge = maxOf(earlierIdx?.let { blockBottom(it) } ?: Float.NEGATIVE_INFINITY, prevSlotBottom)
        val freeE = if (lowEdge == Float.NEGATIVE_INFINITY) maxOf(0f, top) else maxOf(0f, top - lowEdge)
        val freeL = laterIdx?.let { maxOf(0f, blocks[it].topDp - bottom) } ?: Float.MAX_VALUE
        val capE = if (earlierIdx != null && blockBottom(earlierIdx) >= prevSlotBottom) {
            minOf(params.tripTrimMaxDp, maxOf(0f, blocks[earlierIdx].heightDp - minBlock))
        } else {
            0f
        }
        val capL = laterIdx?.let { minOf(params.tripTrimMaxDp, maxOf(0f, blocks[it].heightDp - minBlock)) } ?: 0f
        // Split a need evenly between two capacities; what one side cannot give, the other takes.
        fun split(need: Float, a: Float, b: Float): Pair<Float, Float> {
            var x = minOf(a, need / 2)
            var y = minOf(b, need / 2)
            x += minOf(a - x, need - x - y)
            y += minOf(b - y, need - x - y)
            return x to y
        }
        val need = tripRow - height
        val (eE, eL) = split(need, freeE, freeL)
        val (tE, tL) = split(need - eE - eL, capE, capL)
        val fits = need - eE - eL - tE - tL <= 0.01f
        if (earlierIdx != null && tE > 0f) {
            blocks[earlierIdx] = blocks[earlierIdx].let { it.copy(heightDp = it.heightDp - tE) }
        }
        if (laterIdx != null && tL > 0f) {
            blocks[laterIdx] = blocks[laterIdx].let { it.copy(topDp = it.topDp + tL, heightDp = it.heightDp - tL) }
        }
        val slotTop = top - eE - tE
        val slotHeight = height + eE + eL + tE + tL
        trips += PlacedTrip(c.item, slotTop, slotHeight, iconOnly = !fits)
        prevSlotBottom = slotTop + slotHeight
    }
    // Outage bands inside a visit follow the final (possibly trimmed) height.
    for (i in blocks.indices) {
        blocks[i] = blocks[i].copy(innerGaps = innerGapsOf(blockSpans[i], blocks[i].heightDp))
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
    val nowDp: Float? = if (isToday && now >= canvasStart && now <= canvasEnd) dp(canvasStart, now) else null

    return DayLayout(
        canvasStart = canvasStart,
        canvasEnd = canvasEnd,
        heightDp = maxOf(dp(canvasStart, canvasEnd), lastBottom),
        scale = scale,
        hourMarks = marks,
        blocks = blocks,
        trips = trips,
        nowDp = nowDp,
        quarterMarks = quarters,
    )
}
