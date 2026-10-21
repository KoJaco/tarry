package me.tarry.app.segmentation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.core.data.db.dao.TrackingSessionDao

/**
 * Runs one segmentation pass. Retries a failed pass up to [MAX_RETRIES] times, then gives up until the next trigger
 * and records the failure in [TrackingStatusStore] so the Sessions screen can show it (rule 6).
 *
 * A periodic run that finds no open session cancels the periodic work after its pass: capture ended by a path that
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
                Result.failure()
            }
        }

    private suspend fun stopPeriodicIfCaptureEnded() {
        if (SegmentationScheduler.TAG_PERIODIC in tags && sessionDao.getOpenSession() == null) scheduler.stopPeriodic()
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
