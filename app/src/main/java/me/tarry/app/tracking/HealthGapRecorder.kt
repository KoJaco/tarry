package me.tarry.app.tracking

import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.repository.TimelineRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Posts the alert notification (separate, higher-importance channel than the ongoing one). */
interface HealthAlertNotifier {
    fun post(cause: AttentionCause)

    fun clear()
}

/**
 * Owns the health Gap lifecycle and the ATTENTION cause (single writer, so rule 6 holds in one place).
 *
 * - [reportOutage] opens a Gap starting at the last heartbeat (or now), stores its id in `openHealthGapId`,
 *   sets the cause and posts the alert. Further reports for the same outage only extend `end` (upsert, same id).
 * - [recover] ends the Gap, clears the id and cause, and cancels the alert.
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
) {
    /** [notBeforeMs] bounds the Gap start so a stale heartbeat from before the active stretch is not used. */
    suspend fun reportOutage(issue: HealthIssue, notBeforeMs: Long? = null) {
        val now = clock.nowMs()
        val status = statusStore.current()
        val open = status.openHealthGapId?.let { timelineRepo.gap(it) }
        if (open != null && open.cause == issue.gapCause) {
            timelineRepo.saveGap(open.copy(end = maxOf(open.end, now), updatedAt = now))
            if (status.attentionCause != issue.cause) {
                statusStore.setAttentionCause(issue.cause)
                notifier.post(issue.cause)
            }
            return
        }
        // The cause changed (or the id is stale): close the old Gap and start a new one.
        if (open != null) timelineRepo.saveGap(open.copy(end = maxOf(open.start, now), updatedAt = now))
        val start = (status.lastHeartbeatMs ?: now).coerceAtLeast(notBeforeMs ?: 0L).coerceAtMost(now)
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

    /** Extends the open health Gap to [untilMs]. Returns false if no Gap is open. Used by crash recovery. */
    suspend fun extendOpenGap(untilMs: Long): Boolean {
        val open = statusStore.current().openHealthGapId?.let { timelineRepo.gap(it) } ?: return false
        timelineRepo.saveGap(open.copy(end = maxOf(open.end, untilMs), updatedAt = clock.nowMs()))
        return true
    }

    /** Capture works again (or no longer should): ends the Gap at [endMs] and clears the cause and alert. */
    suspend fun recover(endMs: Long = clock.nowMs()) {
        val status = statusStore.current()
        if (status.openHealthGapId == null && status.attentionCause == null) return
        status.openHealthGapId?.let { id ->
            timelineRepo.gap(id)?.let { open ->
                timelineRepo.saveGap(open.copy(end = maxOf(endMs, open.start), updatedAt = clock.nowMs()))
            }
        }
        statusStore.setOpenHealthGapId(null)
        statusStore.setAttentionCause(null)
        notifier.clear()
    }
}
