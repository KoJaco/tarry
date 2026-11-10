package me.tarry.core.data.repository

import kotlinx.coroutines.flow.first
import me.tarry.core.data.settings.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * The one formula for the earliest instant a segmentation rewind may start at (edits and `SegmentationUpgrade`):
 * `max(now - retention days, prunedBefore + margin)`. The first term is the retention window (0 for Forever); the
 * second follows what was actually pruned, so lengthening the retention later cannot move the floor back past
 * deleted pings.
 */
object RetentionFloor {
    /** Pings are pruned this far behind the retention window, so a run widened past the floor still has its pings. */
    const val PRUNE_MARGIN_MS: Long = 2 * 24 * 60 * 60_000L

    fun compute(nowMs: Long, retentionDays: Int?, prunedBeforeMs: Long?, marginMs: Long = PRUNE_MARGIN_MS): Long {
        val window = retentionDays?.let { nowMs - TimeUnit.DAYS.toMillis(it.toLong()) } ?: 0L
        val pruned = prunedBeforeMs?.plus(marginMs) ?: 0L
        return maxOf(window, pruned, 0L)
    }

    suspend fun compute(settings: SettingsRepository, nowMs: Long, marginMs: Long = PRUNE_MARGIN_MS): Long =
        compute(nowMs, settings.settings.first().rawPingRetentionDays, settings.prunedBeforeMs(), marginMs)
}

/**
 * The pruned-before watermark implied by restored data: if the earliest session starts more than [overlapMs] before
 * the earliest ping, the pings in between were pruned, so the watermark is the earliest ping; otherwise null (clear).
 */
fun restoredPrunedBefore(earliestSessionStartMs: Long?, earliestPingMs: Long?, overlapMs: Long): Long? =
    if (earliestSessionStartMs != null && earliestPingMs != null && earliestSessionStartMs < earliestPingMs - overlapMs) {
        earliestPingMs
    } else {
        null
    }
