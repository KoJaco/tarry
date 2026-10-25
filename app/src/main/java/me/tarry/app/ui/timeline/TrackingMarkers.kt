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

/** A [marker] with where its label sits (dp from the canvas top) and the rule positions. */
data class PlacedMarker(val marker: TrackingMarker, val ruleDps: List<Float>, val labelTopDp: Float)

/**
 * Positions [markers] on a canvas starting at [canvasStart] with [dpPerHour]. The label hangs just under the rule, or
 * just above it when it would not fit before [canvasHeightDp]; a label that would overlap the previous one is pushed
 * down below it (the rules stay at their true time). Markers outside the canvas are dropped.
 */
fun placeTrackingMarkers(
    markers: List<TrackingMarker>,
    canvasStart: Long,
    canvasEnd: Long,
    dpPerHour: Float,
    canvasHeightDp: Float,
    labelHeightDp: Float,
    gapDp: Float,
): List<PlacedMarker> {
    var prevBottom = Float.NEGATIVE_INFINITY
    return markers.filter { it.timeMs in canvasStart..canvasEnd }.sortedBy { it.timeMs }.map { m ->
        val rules = m.ruleTimes.map { ((it - canvasStart) / 3_600_000.0 * dpPerHour).toFloat() }
        val y = rules.first()
        val natural = if (y + gapDp + labelHeightDp <= canvasHeightDp) y + gapDp else maxOf(0f, y - gapDp - labelHeightDp)
        val top = maxOf(natural, prevBottom)
        prevBottom = top + labelHeightDp
        PlacedMarker(m, rules, top)
    }
}
