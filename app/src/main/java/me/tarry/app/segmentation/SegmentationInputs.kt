package me.tarry.app.segmentation

import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.settings.LockSpan
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

/** The spans tracking was locked (closed ones and the open one), or none while it never was. */
fun interface AccessLockSource {
    suspend fun lockedSpans(): List<LockSpan>
}

/**
 * Removes every locked span from [windows]: nothing was expected to be captured while tracking was locked, so no
 * NO_SESSION gap belongs there, whether the lock is still on or ended long ago. A window straddling a span is cut around it.
 */
internal fun windowsOutsideLocks(windows: List<TimeRange>, spans: List<LockSpan>): List<TimeRange> {
    if (spans.isEmpty()) return windows
    var result = windows
    for (span in spans.sortedBy { it.startMs }) {
        result = result.flatMap {
            if (it.endMs <= span.startMs || it.startMs >= span.endMs) {
                listOf(it)
            } else {
                listOfNotNull(
                    if (it.startMs < span.startMs) TimeRange(it.startMs, span.startMs) else null,
                    if (it.endMs > span.endMs) TimeRange(span.endMs, it.endMs) else null,
                )
            }
        }
    }
    return result
}
