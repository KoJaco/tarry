package me.tarry.app.segmentation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import kotlinx.coroutines.CancellationException
import me.tarry.app.tracking.ScheduleWindowChecker
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.core.data.db.dao.TrackingSessionDao

/**
 * Runs one segmentation pass. Retries a failed pass up to [MAX_RETRIES] times, then gives up until the next trigger
 * and records the failure in [TrackingStatusStore] so the Sessions screen can show it (rule 6). The give-up itself
 * returns success so that runs chained behind it still execute.
 *
 * A periodic run that finds no open session and no active window cancels the periodic work after its pass: capture ended by a path that
 * never called the trigger (crash recovery, for example) must not leave it running.
 */
@HiltWorker
class SegmentationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: SegmentationRunner,
    private val clock: TrackingClock,
    private val scheduler: SegmentationScheduler,
    private val statusStore: TrackingStatusStore,
    private val sessionDao: TrackingSessionDao,
    private val windowChecker: ScheduleWindowChecker,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            runner.run(clock.nowMs())
            statusStore.recordSegmentationSuccess(clock.nowMs())
            scheduler.enqueueGeocode()
            stopPeriodicIfCaptureEnded()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                runCatching {
                    statusStore.recordSegmentationFailure(clock.nowMs(), e.message ?: e::class.java.simpleName)
                    stopPeriodicIfCaptureEnded()
                }
                // success, not failure: the failure is recorded above, and a FAILED run would mark every run appended
                // behind it (APPEND_OR_REPLACE in [SegmentationScheduler.enqueueNow]) FAILED too, so they would never run.
                Result.success()
            }
        }

    private suspend fun stopPeriodicIfCaptureEnded() {
        if (SegmentationScheduler.TAG_PERIODIC !in tags || sessionDao.getOpenSession() != null) return
        // A window that is starting has no session yet (the service inserts it a moment later): keep the work then.
        val now = Instant.ofEpochMilli(clock.nowMs())
        if (windowChecker.activeIntervals(now, now.plusMillis(1)).isEmpty()) scheduler.stopPeriodic()
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
