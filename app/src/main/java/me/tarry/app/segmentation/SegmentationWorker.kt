package me.tarry.app.segmentation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import me.tarry.app.tracking.TrackingClock

/** Runs one segmentation pass. Retries a failed pass up to [MAX_RETRIES] times, then gives up until the next trigger. */
@HiltWorker
class SegmentationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: SegmentationRunner,
    private val clock: TrackingClock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            runner.run(clock.nowMs())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

    companion object {
        const val MAX_RETRIES = 3
    }
}
