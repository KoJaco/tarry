package me.tarry.app.segmentation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.tarry.app.tracking.TrackingConfig
import java.util.concurrent.TimeUnit

/** Daily prune of old raw pings. The OS wakes it (rule 5); the logic is in [PingRetention]. */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val retention: PingRetention,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        retention.prune()
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "ping_retention"

        /** Enqueues the unique periodic worker; KEEP leaves an existing schedule untouched. */
        fun enqueue(context: Context, config: TrackingConfig) {
            val request = PeriodicWorkRequestBuilder<RetentionWorker>(config.retentionWorkerPeriodHours, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
