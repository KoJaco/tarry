package me.tarry.app.tracking

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.repository.TimelineRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Posts the alert notification (separate, higher-importance channel than the ongoing one). */
interface HealthAlertNotifier {
    fun post(cause: AttentionCause)

    fun clear()
}

/**
 * Owns the health Gap lifecycle and the ATTENTION cause (single writer, so rule 6 holds in one place).
 * Every operation is serialised by one mutex: the health worker, fix batches and crash recovery all reach it, and
 * each does a read-modify-write of `openHealthGapId`.
 *
 * - [reportOutage] opens a Gap starting at the last heartbeat (or the active stretch start, or now), stores its id
 *   in `openHealthGapId`, sets the cause and posts the alert. Further reports for the same outage only extend `end`
 *   (upsert, same id). An issue with [HealthIssue.recordGap] false is ATTENTION and alert only (see [reportAttention]).
 * - [recover] ends the Gap, clears the id and cause, and cancels the alert. [closeOutage] does so only if a Gap is open.
 *
 * No double counting with crash recovery's SERVICE_KILLED Gap: a new Gap adopts an existing Gap of the same cause
 * that overlaps or lies within [TrackingConfig.gapMergeToleranceMs]; against a Gap of another cause its start is
 * clamped to that Gap's end. Crash recovery in turn extends the open health Gap instead of writing its own.
 */
@Singleton
class HealthGapRecorder @Inject constructor(
    private val statusStore: TrackingStatusStore,
    private val timelineRepo: TimelineRepository,
    private val notifier: HealthAlertNotifier,
    private val clock: TrackingClock,
    private val config: TrackingConfig,
    private val windowChecker: ScheduleWindowChecker,
) {
    private val mutex = Mutex()

    /** [notBeforeMs] bounds the Gap start so a stale heartbeat from before the active stretch is not used. */
    suspend fun reportOutage(issue: HealthIssue, notBeforeMs: Long? = null) = mutex.withLock {
        val now = clock.nowMs()
        if (!issue.recordGap) {
            // Nothing is being lost any more: an already-open Gap ends here.
            closeGapLocked(now)
            reportAttentionLocked(issue.cause)
            return@withLock
        }
        val status = statusStore.current()
        val open = status.openHealthGapId?.let { timelineRepo.gap(it) }
        // An open Gap whose last extension predates the current active stretch belongs to an earlier stretch:
        // extending it would stretch it across the inactive time in between.
        val continuous = open != null && (notBeforeMs == null || open.end >= notBeforeMs)
        if (open != null && continuous && open.cause == issue.gapCause) {
            timelineRepo.saveGap(open.copy(end = maxOf(open.end, now), updatedAt = now))
            if (status.attentionCause != issue.cause) {
                statusStore.setAttentionCause(issue.cause)
                notifier.post(issue.cause)
            }
            return@withLock
        }
        // The cause changed (or the id is stale): close the old Gap and start a new one.
        if (open != null) {
            val closeAt = if (continuous) clampedEnd(open, now) else open.end
            timelineRepo.saveGap(open.copy(end = closeAt, updatedAt = now))
        }
        val start = (status.lastHeartbeatMs ?: notBeforeMs ?: now).coerceAtLeast(notBeforeMs ?: 0L).coerceAtMost(now)
        val near = timelineRepo.gapsInRange(start - config.gapMergeToleranceMs, now + 1)
            .filter { it.id != open?.id }
        val adopted = near.filter { it.cause == issue.gapCause }.maxByOrNull { it.end }
        val gap = if (adopted != null) {
            adopted.copy(end = maxOf(adopted.end, now), updatedAt = now)
        } else {
            val clamped = near.maxOfOrNull { it.end }?.let { maxOf(start, minOf(it, now)) } ?: start
            Gap(start = clamped, end = now, zoneId = clock.zone().id, cause = issue.gapCause, updatedAt = now)
        }
        timelineRepo.saveGap(gap)
        statusStore.setOpenHealthGapId(gap.id)
        statusStore.setAttentionCause(issue.cause)
        notifier.post(issue.cause)
    }

    /**
     * ATTENTION and one alert for a standing problem that costs no capture time (service running with fresh fixes, or
     * outside a window): no Gap, and the alert is posted only when the cause changes.
     */
    suspend fun reportAttention(cause: AttentionCause) = mutex.withLock { reportAttentionLocked(cause) }

    private suspend fun closeGapLocked(endMs: Long) {
        val id = statusStore.current().openHealthGapId ?: return
        timelineRepo.gap(id)?.let { timelineRepo.saveGap(it.copy(end = clampedEnd(it, endMs), updatedAt = clock.nowMs())) }
        statusStore.setOpenHealthGapId(null)
    }

    /**
     * Where an open Gap may end when closed at [endMs]: never past the end of the active interval that contains its
     * current end, so closing it later (a worker after a reboot, at 03:00) cannot stretch it over inactive time.
     */
    private suspend fun clampedEnd(open: Gap, endMs: Long): Long {
        if (endMs <= open.end) return maxOf(endMs, open.start)
        val first = windowChecker.activeIntervals(Instant.ofEpochMilli(open.end), Instant.ofEpochMilli(endMs)).firstOrNull()
        return if (first != null && first.start.toEpochMilli() <= open.end) minOf(endMs, first.end.toEpochMilli()) else open.end
    }

    /** The current cause if it is attention-only (no Gap open), else null. */
    suspend fun attentionOnlyCause(): AttentionCause? = mutex.withLock {
        val status = statusStore.current()
        if (status.openHealthGapId == null) status.attentionCause else null
    }

    private suspend fun reportAttentionLocked(cause: AttentionCause) {
        if (statusStore.current().attentionCause == cause) return
        statusStore.setAttentionCause(cause)
        notifier.post(cause)
    }

    /**
     * Crash recovery: [intervals] are the active stretches the dead time covered. The open health Gap (which started
     * at the last heartbeat) is extended only through the first interval if that touches it, capped at that
     * interval's end, and is then closed (the id is cleared; the process that owned it is gone). Returns the
     * intervals still to be recorded by the caller.
     */
    suspend fun adoptOpenGap(intervals: List<WindowBounds>): List<WindowBounds> = mutex.withLock {
        val id = statusStore.current().openHealthGapId ?: return@withLock intervals
        val open = timelineRepo.gap(id)
        statusStore.setOpenHealthGapId(null)
        val first = intervals.firstOrNull()
        if (open == null || first == null || first.start.toEpochMilli() > open.end + config.gapMergeToleranceMs ||
            first.end.toEpochMilli() < open.start - config.gapMergeToleranceMs
        ) {
            return@withLock intervals
        }
        timelineRepo.saveGap(
            open.copy(
                start = minOf(open.start, first.start.toEpochMilli()),
                end = maxOf(open.end, first.end.toEpochMilli()),
                updatedAt = clock.nowMs(),
            ),
        )
        intervals.drop(1)
    }

    /** Capture works again (or no longer should): ends the Gap at [endMs] and clears the cause and alert. */
    suspend fun recover(endMs: Long = clock.nowMs()) = mutex.withLock { recoverLocked(endMs) }

    /**
     * Like [recover], but only when a health Gap is open. Fixes arriving or a window ending must not wipe an
     * attention-only cause (that would churn: clear, then the next health check re-posts it).
     */
    suspend fun closeOutage(endMs: Long = clock.nowMs()) = mutex.withLock {
        if (statusStore.current().openHealthGapId != null) recoverLocked(endMs)
    }

    /** Clears [cause] (and closes its Gap) if it is the current one; other causes are left alone. */
    suspend fun recoverCause(cause: AttentionCause, endMs: Long = clock.nowMs()) = mutex.withLock {
        if (statusStore.current().attentionCause == cause) recoverLocked(endMs)
    }

    private suspend fun recoverLocked(endMs: Long) {
        val status = statusStore.current()
        if (status.openHealthGapId == null && status.attentionCause == null) return
        status.openHealthGapId?.let { id ->
            timelineRepo.gap(id)?.let { open ->
                timelineRepo.saveGap(open.copy(end = clampedEnd(open, endMs), updatedAt = clock.nowMs()))
            }
        }
        statusStore.setOpenHealthGapId(null)
        statusStore.setAttentionCause(null)
        notifier.clear()
    }
}
