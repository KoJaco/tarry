package me.tarry.app.segmentation

import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionEndReason
import me.tarry.segmentation.TimeRange

/**
 * Session ranges for the segmenter. A KILLED session whose end is followed by the next session's start in strictly
 * less than [shortInterruptionMs] (the same rule as crash recovery's short outage) is extended to that start, so a
 * quick restart leaves no NO_SESSION gap. Longer outages and other end reasons stay as stored; an open session
 * ends at [nowMs].
 */
internal fun segmentationSessions(sessions: List<TrackingSession>, nowMs: Long, shortInterruptionMs: Long): List<TimeRange> {
    val sorted = sessions.sortedBy { it.start }
    return sorted.mapIndexed { i, s ->
        val end = maxOf(s.end ?: nowMs, s.start)
        val next = sorted.getOrNull(i + 1)
        if (s.end != null && s.endReason == SessionEndReason.KILLED && next != null &&
            next.start >= end && next.start - end < shortInterruptionMs
        ) {
            TimeRange(s.start, next.start)
        } else {
            TimeRange(s.start, end)
        }
    }
}

/**
 * Clips [windows] to start no earlier than [firstSessionStartMs], the earliest session ever recorded: nothing could
 * have been captured before it, so no NO_SESSION gap belongs there. A window wholly before it is dropped.
 */
internal fun boundedWindows(windows: List<TimeRange>, firstSessionStartMs: Long?): List<TimeRange> {
    if (firstSessionStartMs == null) return windows
    return windows.mapNotNull {
        if (it.endMs <= firstSessionStartMs) null else TimeRange(maxOf(it.startMs, firstSessionStartMs), it.endMs)
    }
}
