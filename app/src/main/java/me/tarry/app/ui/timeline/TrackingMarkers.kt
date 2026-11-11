package me.tarry.app.ui.timeline

import me.tarry.app.tracking.TrackingConfig
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionEndReason

/** Tunables of the "Tracking started / stopped" marks on the day canvas. */
object TrackingMarkerConfig {
    /** A stop followed by a start within this long (pause + resume, kill + restart) is one collapsed marker. */
    const val MergeWindowMs: Long = 2 * 60_000L
}

/**
 * A tracking start and/or stop on the day canvas. [stoppedMs] and [startedMs] are inside the viewed day; a collapsed
 * marker has both (stop first). [reason] is the stop's end reason (a collapsed marker keeps it but doesn't show it).
 */
data class TrackingMarker(
    val startedMs: Long?,
    val stoppedMs: Long?,
    val reason: SessionEndReason? = null,
) {
    /** Where the marker is placed: the stop of a collapsed marker, which is the earlier of the two. */
    val timeMs: Long get() = stoppedMs ?: startedMs!!

    /** Every instant a rule is drawn at. */
    val ruleTimes: List<Long> get() = listOfNotNull(stoppedMs, startedMs)
}

/** [inside] false: an edge outside the day, kept only so a KILLED stop and its restart can see each other across midnight. */
private class Edge(val time: Long, val isStart: Boolean, val reason: SessionEndReason?, val inside: Boolean = true)

/**
 * The markers for `[dayStart, dayEnd)`: each session's start and end that fall inside the day, in time order. A session
 * crossing midnight contributes only its edge inside the day; an open session only its start. A stop and the next start
 * at most [mergeWindowMs] later become one marker. A start up to [shortInterruptionMs] after the day only cancels a
 * KILLED stop just before the end, and a KILLED stop up to [shortInterruptionMs] before the day cancels a start just after the
 * day's beginning (the caller loads sessions that far on both sides); neither is ever a marker of this day.
 */
fun trackingMarkers(
    sessions: List<TrackingSession>,
    dayStart: Long,
    dayEnd: Long,
    mergeWindowMs: Long = TrackingMarkerConfig.MergeWindowMs,
    /** A [SessionEndReason.KILLED] stop followed by a start sooner than this is not a visible break: no marker, no stretch. */
    shortInterruptionMs: Long = TrackingConfig().shortInterruptionMs,
): List<TrackingMarker> {
    val edges = sessions.flatMap { s ->
        val end = s.end
        listOfNotNull(
            when {
                s.start >= dayStart && s.start < dayEnd -> Edge(s.start, true, null)
                s.start >= dayEnd && s.start - dayEnd < shortInterruptionMs -> Edge(s.start, true, null, inside = false)
                else -> null
            },
            when {
                end == null -> null
                end > dayStart && end < dayEnd -> Edge(end, false, s.endReason)
                end <= dayStart && dayStart - end < shortInterruptionMs -> Edge(end, false, s.endReason, inside = false)
                else -> null
            },
        )
    }.sortedWith(compareBy<Edge>({ it.time }, { it.isStart })) // a stop sorts before a start at the same instant
    val out = mutableListOf<TrackingMarker>()
    var i = 0
    while (i < edges.size) {
        val e = edges[i]
        val next = edges.getOrNull(i + 1)
        if (!e.isStart && e.reason == SessionEndReason.KILLED && next != null && next.isStart && next.time - e.time < shortInterruptionMs) {
            i += 2
            continue
        }
        if (!e.inside) {
            i++
            continue
        }
        if (!e.isStart && next != null && next.isStart && next.inside && next.time - e.time <= mergeWindowMs) {
            out += TrackingMarker(startedMs = next.time, stoppedMs = e.time, reason = e.reason)
            i += 2
        } else {
            out += if (e.isStart) TrackingMarker(startedMs = e.time, stoppedMs = null) else TrackingMarker(null, e.time, e.reason)
            i++
        }
    }
    return out
}

/** A [marker] and where its label sits in the time column: [anchorDp] is its true time position, [labelTopDp] the label's top. */
data class PlacedMarker(
    val marker: TrackingMarker,
    val anchorDp: Float,
    val labelTopDp: Float,
    val labelHeightDp: Float,
    /** Large-font layout only: both sides of the tick are covered by blocks, so the label is drawn over them on its card backdrop. */
    val overBlocks: Boolean = false,
)

/**
 * Positions [markers] for the time column of a canvas starting at [canvasStart] with [dpPerHour]. A label is
 * `lineCount(marker) * lineHeightDp` tall and centred on its time (a collapsed pair on the middle of its two times),
 * kept inside `0..canvasHeightDp`; a label that would overlap the previous one is pushed down below it, [gapDp] apart.
 * A label that would overlap [avoid] (the now-pill's span) moves just above it, or just below it when above would
 * collide with the previous label or leave the top of the canvas. Markers outside the canvas are dropped. With [above]
 * (large fonts) the label ends [gapDp] above the marker's first time instead of being centred on it.
 */
fun placeTrackingMarkers(
    markers: List<TrackingMarker>,
    canvasStart: Long,
    canvasEnd: Long,
    dpPerHour: Float,
    canvasHeightDp: Float,
    lineHeightDp: Float,
    gapDp: Float,
    lineCount: (TrackingMarker) -> Int = { 2 },
    avoid: ClosedFloatingPointRange<Float>? = null,
    /** Large-font layout: the label sits just above its (first) time instead of centred on it; see [stackedTimeLabels]. */
    above: Boolean = false,
    /** With [above]: the vertical spans (dp) of the day's blocks; a label prefers the free side of its tick, see [stackedMarkerSlot]. */
    blocks: List<ClosedFloatingPointRange<Float>> = emptyList(),
): List<PlacedMarker> {
    var prevBottom = Float.NEGATIVE_INFINITY
    return markers.filter { it.timeMs in canvasStart..canvasEnd }.sortedBy { it.timeMs }.map { m ->
        var overBlocks = false
        val anchor = m.ruleTimes.map { ((it - canvasStart) / 3_600_000.0 * dpPerHour).toFloat() }.average().toFloat()
        val height = lineCount(m) * lineHeightDp
        val first = m.ruleTimes.minOf { ((it - canvasStart) / 3_600_000.0 * dpPerHour).toFloat() }
        val natural = if (above) {
            // On the free side of its tick: above if no block is there, else below; both covered: over the blocks, still
            // above when there is room (at the very top of the canvas there is none, so below).
            val aboveTop = (first - height - gapDp).takeIf { it >= 0f }
            when (stackedMarkerSlot(first, height, gapDp, blocks)) {
                MarkerSlot.Above -> aboveTop ?: (first + gapDp)
                MarkerSlot.Below -> first + gapDp
                MarkerSlot.OverBlocks -> { overBlocks = true; aboveTop ?: (first + gapDp) }
            }
        } else {
            (anchor - height / 2f).coerceIn(0f, maxOf(0f, canvasHeightDp - height))
        }
        var top = maxOf(natural, prevBottom + gapDp)
        if (avoid != null && top < avoid.endInclusive + gapDp && top + height > avoid.start - gapDp) {
            val clearAbove = avoid.start - gapDp - height
            // Below is not clamped to the canvas: a label running into the card's bottom padding stays readable, while
            // clamping or squeezing it above would overlap the pill or the previous label.
            top = if (clearAbove >= 0f && clearAbove >= prevBottom + gapDp) clearAbove else avoid.endInclusive + gapDp
        }
        prevBottom = top + height
        PlacedMarker(m, anchor, top, height, overBlocks)
    }
}

/** True when the hour label centred at [markDp] (half a line of [hourHalfLineDp] each way) would overlap [placed]; the marker wins. */
fun hourLabelCoveredByMarker(markDp: Float, placed: PlacedMarker, hourHalfLineDp: Float): Boolean =
    markDp - hourHalfLineDp < placed.labelTopDp + placed.labelHeightDp && markDp + hourHalfLineDp > placed.labelTopDp

/** The words of the time-column labels ("Start", "Stop", "Off", "Interrupted"). */
data class MarkerWords(val start: String, val stop: String, val off: String, val interrupted: String = off)

/**
 * The visible lines of a marker's time-column label. A start or stop is the word over the time ("Start" / "7:30"). A
 * collapsed pair is always "Off" over "12:05–12:20", which keeps the time column narrow.
 */
fun markerLines(marker: TrackingMarker, words: MarkerWords, time: (Long) -> String): List<String> {
    val stopped = marker.stoppedMs?.let(time)
    val started = marker.startedMs?.let(time)
    val killed = marker.reason == SessionEndReason.KILLED
    return when {
        stopped != null && started != null -> listOf(if (killed) words.interrupted else words.off, "$stopped–$started")
        stopped != null -> listOf(if (killed) words.interrupted else words.stop, stopped)
        else -> listOf(words.start, started.orEmpty())
    }
}

/** A stretch of the day when tracking was off, drawn as a faint hatch. Visual only: gaps (which are about missing data) are separate. */
/** [killed]: the stop was the process dying (not a user stop or pause); only such a stretch is clipped out of visits. */
data class OffStretch(val start: Long, val end: Long, val killed: Boolean = false)

/**
 * The stretches between a stop and the next start. A collapsed pair is its own short stretch. A stop never followed by a
 * start runs to [now] on today (only past time is hatched), or to [dayEnd] on a past day. Stretches are not clamped
 * to the canvas here.
 */
fun offStretches(markers: List<TrackingMarker>, dayEnd: Long, now: Long?): List<OffStretch> {
    val sorted = markers.sortedBy { it.timeMs }
    return sorted.mapIndexedNotNull { i, m ->
        val stop = m.stoppedMs ?: return@mapIndexedNotNull null
        val end = m.startedMs ?: sorted.getOrNull(i + 1)?.timeMs ?: minOf(now ?: dayEnd, dayEnd)
        if (end > stop) OffStretch(stop, end, killed = m.reason == SessionEndReason.KILLED) else null
    }
}

/**
 * The [OffStretch.killed] stretches of [stretches] minus the time covered by [visits] (start to end ranges): a visit that carries on across a short stop and
 * restart must not look like time off. A stretch partly inside a visit keeps its outside parts. A deliberate stop or pause
 * keeps its hatch whatever the visit does.
 */
fun clipOutOfVisits(stretches: List<OffStretch>, visits: List<LongRange>): List<OffStretch> {
    val sorted = visits.sortedBy { it.first }
    return stretches.flatMap { s ->
        if (!s.killed) return@flatMap listOf(s)
        var from = s.start
        val parts = mutableListOf<OffStretch>()
        for (v in sorted) {
            if (v.last <= from) continue
            if (v.first >= s.end) break
            if (v.first > from) parts += s.copy(start = from, end = v.first)
            from = maxOf(from, v.last)
        }
        if (from < s.end) parts += s.copy(start = from)
        parts
    }
}

/** The earliest time tracking started on the day (the first marker that carries a start), or null when no marker does. */
fun firstTrackingStart(markers: List<TrackingMarker>): Long? = markers.filter { it.startedMs != null }.minOfOrNull { it.timeMs }

/** Where a large-font tracking label goes relative to its tick. */
enum class MarkerSlot { Above, Below, OverBlocks }

/**
 * The side of the tick at [tickDp] a stacked label [heightDp] tall (with [gapDp] to the tick) goes: above when no block
 * spans that space, otherwise below when that is free, otherwise [MarkerSlot.OverBlocks] (drawn on top of the blocks).
 * Above is also not free at the very top of the canvas (negative top).
 */
fun stackedMarkerSlot(tickDp: Float, heightDp: Float, gapDp: Float, blocks: List<ClosedFloatingPointRange<Float>>): MarkerSlot {
    fun free(top: Float) = blocks.none { it.start < top + heightDp && it.endInclusive > top }
    val aboveTop = tickDp - heightDp - gapDp
    return when {
        aboveTop >= 0f && free(aboveTop) -> MarkerSlot.Above
        free(tickDp + gapDp) -> MarkerSlot.Below
        else -> MarkerSlot.OverBlocks
    }
}
