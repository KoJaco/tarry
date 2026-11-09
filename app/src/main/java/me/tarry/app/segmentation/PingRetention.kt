package me.tarry.app.segmentation

import kotlinx.coroutines.flow.first
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingConfig
import me.tarry.core.data.db.dao.LocationPingDao
import me.tarry.core.data.db.dao.SegmentationCheckpointDao
import me.tarry.core.data.db.dao.TrackingSessionDao
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.segmentation.SegmentationParams
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes raw pings older than the retention setting, less [TrackingConfig.retentionPruneMarginMs]. Visits, trips and
 * places are never touched. The cutoff never reaches into the open tracking session, nor into the range the next
 * segmentation run reads from its checkpoint (or, with no checkpoint yet, the first session) less the overlap.
 *
 * The guarantee: a run, or an edit/upgrade rewind clamped to `now - days` (`RewindFloor`, `SegmentationUpgrade`), reads
 * only pings that still exist, as long as the run's widening (to the start of an overlapping unfrozen visit, trip or
 * segmentation gap, and a trip's origin visit) stays within the margin of the floor. Longer rows straddling the floor
 * are an accepted limit: the margin covers a normal stay, not a multi-day one.
 */
@Singleton
class PingRetention @Inject constructor(
    private val settings: SettingsRepository,
    private val pingDao: LocationPingDao,
    private val sessionDao: TrackingSessionDao,
    private val checkpointDao: SegmentationCheckpointDao,
    private val params: SegmentationParams,
    private val clock: TrackingClock,
    private val config: TrackingConfig = TrackingConfig(),
) {
    /** Returns how many pings were deleted. Forever deletes nothing. */
    suspend fun prune(): Int {
        val days = settings.settings.first().rawPingRetentionDays ?: return 0
        // Mirrors SegmentationRunner: with no checkpoint yet its first run starts at the first session.
        val processedUntil = checkpointDao.getById()?.processedUntil
            ?: sessionDao.getInRange(0, Long.MAX_VALUE).firstOrNull()?.start
        val cutoff = cutoff(
            nowMs = clock.nowMs(),
            days = days,
            marginMs = config.retentionPruneMarginMs,
            openSessionStartMs = sessionDao.getOpenSession()?.start,
            segmentationFromMs = processedUntil?.let { it - params.overlapMs },
        )
        return pingDao.deleteOlderThan(cutoff)
    }

    companion object {
        /** Pings with a timestamp before the result are deleted: `now - days - margin`, lowered to every bound given. */
        fun cutoff(nowMs: Long, days: Int, marginMs: Long, openSessionStartMs: Long?, segmentationFromMs: Long?): Long =
            listOfNotNull(nowMs - TimeUnit.DAYS.toMillis(days.toLong()) - marginMs, openSessionStartMs, segmentationFromMs).min()
    }
}
