package me.tarry.app.segmentation

import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionEndReason
import me.tarry.segmentation.TimeRange

/**
 * Session ranges for the segmenter. A KILLED session whose end is followed by the next session's start in strictly
 * less than [shortInterruptionMs] (the same rule as crash recovery's short outage) is extended to that start, so a
 * quick restart leaves no NO_SESSION gap; the last session bridges to [nowMs] the same way. Longer outages and other
 * end reasons stay as stored; an open session ends at [nowMs].
 */
internal fun segmentationSessions(sessions: List<TrackingSession>, nowMs: Long, shortInterruptionMs: Long): List<TimeRange> {
    val sorted = sessions.sortedBy { it.start }
    return sorted.mapIndexed { i, s ->
        val end = maxOf(s.end ?: nowMs, s.start)
        // The last session bridges to now: a run between crash recovery and the restart must not show the outage
        // either. If no restart comes, the health path records the Gap once the short interruption has passed.
        // This also bridges the zero-length KILLED session a failed start writes; that failure's own SERVICE_KILLED
        // health Gap reaches segmentation as a health gap, so the time still shows.
        val nextStart = sorted.getOrNull(i + 1)?.start ?: nowMs
        if (s.end != null && s.endReason == SessionEndReason.KILLED && nextStart >= end && nextStart - end < shortInterruptionMs) {
            TimeRange(s.start, nextStart)
        } else {
            TimeRange(s.start, end)
        }
    }
}

/**
 * Clips [windows] to start no earlier than [firstSessionStartMs], the earliest session ever recorded: nothing could
 * have been captured before it, so no NO_SESSION gap belongs there. A window wholly before it is dropped, and with no
 * session ever ([firstSessionStartMs] null) there are no windows at all.
 */
internal fun boundedWindows(windows: List<TimeRange>, firstSessionStartMs: Long?): List<TimeRange> {
    if (firstSessionStartMs == null) return emptyList()
    return windows.mapNotNull {
        if (it.endMs <= firstSessionStartMs) null else TimeRange(maxOf(it.startMs, firstSessionStartMs), it.endMs)
    }
}

/** Where the tracking lock began (UTC epoch ms), or null while tracking is allowed. */
fun interface AccessLockSource {
    suspend fun lockedSinceMs(): Long?
}

/**
 * Clips [windows] to end no later than [lockedSinceMs], the moment tracking was locked: nothing was expected to be
 * captured after it, so no NO_SESSION gap belongs there. A window wholly after it is dropped; null leaves them alone.
 */
internal fun windowsBeforeLock(windows: List<TimeRange>, lockedSinceMs: Long?): List<TimeRange> {
    if (lockedSinceMs == null) return windows
    return windows.mapNotNull {
        if (it.startMs >= lockedSinceMs) null else TimeRange(it.startMs, minOf(it.endMs, lockedSinceMs))
    }
}
