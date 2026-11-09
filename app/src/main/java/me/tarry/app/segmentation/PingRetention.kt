package me.tarry.app.segmentation

import kotlinx.coroutines.flow.first
import me.tarry.app.tracking.TrackingClock
import me.tarry.core.data.db.dao.LocationPingDao
import me.tarry.core.data.db.dao.SegmentationCheckpointDao
import me.tarry.core.data.db.dao.TrackingSessionDao
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.segmentation.SegmentationParams
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes raw pings older than the retention setting. Visits, trips and places are never touched. The cutoff never
 * reaches into the open tracking session, nor into the range the next segmentation run reads (its checkpoint less the
 * overlap), so pruning cannot change what segmentation produces.
 */
@Singleton
class PingRetention @Inject constructor(
    private val settings: SettingsRepository,
    private val pingDao: LocationPingDao,
    private val sessionDao: TrackingSessionDao,
    private val checkpointDao: SegmentationCheckpointDao,
    private val params: SegmentationParams,
    private val clock: TrackingClock,
) {
    /** Returns how many pings were deleted. Forever deletes nothing. */
    suspend fun prune(): Int {
        val days = settings.settings.first().rawPingRetentionDays ?: return 0
        val cutoff = cutoff(
            nowMs = clock.nowMs(),
            days = days,
            openSessionStartMs = sessionDao.getOpenSession()?.start,
            segmentationFromMs = checkpointDao.getById()?.processedUntil?.let { it - params.overlapMs },
        )
        return pingDao.deleteOlderThan(cutoff)
    }

    companion object {
        /** Pings with a timestamp before the result are deleted: `now - days`, lowered to every bound given. */
        fun cutoff(nowMs: Long, days: Int, openSessionStartMs: Long?, segmentationFromMs: Long?): Long =
            listOfNotNull(nowMs - TimeUnit.DAYS.toMillis(days.toLong()), openSessionStartMs, segmentationFromMs).min()
    }
}
