package me.tarry.app.ui.timeline

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

private class Edge(val time: Long, val isStart: Boolean, val reason: SessionEndReason?)

/**
 * The markers for `[dayStart, dayEnd)`: each session's start and end that fall inside the day, in time order. A session
 * crossing midnight contributes only its edge inside the day; an open session only its start. A stop and the next start
 * at most [mergeWindowMs] later become one marker.
 */
fun trackingMarkers(
    sessions: List<TrackingSession>,
    dayStart: Long,
    dayEnd: Long,
    mergeWindowMs: Long = TrackingMarkerConfig.MergeWindowMs,
): List<TrackingMarker> {
    val edges = sessions.flatMap { s ->
        listOfNotNull(
            if (s.start >= dayStart && s.start < dayEnd) Edge(s.start, true, null) else null,
            s.end?.takeIf { it > dayStart && it < dayEnd }?.let { Edge(it, false, s.endReason) },
        )
    }.sortedWith(compareBy<Edge>({ it.time }, { it.isStart })) // a stop sorts before a start at the same instant
    val out = mutableListOf<TrackingMarker>()
    var i = 0
    while (i < edges.size) {
        val e = edges[i]
        val next = edges.getOrNull(i + 1)
        if (!e.isStart && next != null && next.isStart && next.time - e.time <= mergeWindowMs) {
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
data class PlacedMarker(val marker: TrackingMarker, val anchorDp: Float, val labelTopDp: Float, val labelHeightDp: Float)

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
): List<PlacedMarker> {
    var prevBottom = Float.NEGATIVE_INFINITY
    return markers.filter { it.timeMs in canvasStart..canvasEnd }.sortedBy { it.timeMs }.map { m ->
        val anchor = m.ruleTimes.map { ((it - canvasStart) / 3_600_000.0 * dpPerHour).toFloat() }.average().toFloat()
        val height = lineCount(m) * lineHeightDp
        val first = m.ruleTimes.minOf { ((it - canvasStart) / 3_600_000.0 * dpPerHour).toFloat() }
        val natural = if (above) {
            // Just above its tick; at the very top of the canvas there is no room above, so just below.
            (first - height - gapDp).takeIf { it >= 0f } ?: (first + gapDp)
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
        PlacedMarker(m, anchor, top, height)
    }
}

/** True when the hour label centred at [markDp] (half a line of [hourHalfLineDp] each way) would overlap [placed]; the marker wins. */
fun hourLabelCoveredByMarker(markDp: Float, placed: PlacedMarker, hourHalfLineDp: Float): Boolean =
    markDp - hourHalfLineDp < placed.labelTopDp + placed.labelHeightDp && markDp + hourHalfLineDp > placed.labelTopDp

/** The words of the time-column labels ("Start", "Stop", "Off"). */
data class MarkerWords(val start: String, val stop: String, val off: String)

/**
 * The visible lines of a marker's time-column label. A start or stop is the word over the time ("Start" / "7:30"). A
 * collapsed pair is always "Off" over "12:05–12:20", which keeps the time column narrow.
 */
fun markerLines(marker: TrackingMarker, words: MarkerWords, time: (Long) -> String): List<String> {
    val stopped = marker.stoppedMs?.let(time)
    val started = marker.startedMs?.let(time)
    return when {
        stopped != null && started != null -> listOf(words.off, "$stopped–$started")
        stopped != null -> listOf(words.stop, stopped)
        else -> listOf(words.start, started.orEmpty())
    }
}

/** A stretch of the day when tracking was off, drawn as a faint hatch. Visual only: gaps (which are about missing data) are separate. */
data class OffStretch(val start: Long, val end: Long)

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
        if (end > stop) OffStretch(stop, end) else null
    }
}

/** The earliest time tracking started on the day (the first marker that carries a start), or null when no marker does. */
fun firstTrackingStart(markers: List<TrackingMarker>): Long? = markers.filter { it.startedMs != null }.minOfOrNull { it.timeMs }
