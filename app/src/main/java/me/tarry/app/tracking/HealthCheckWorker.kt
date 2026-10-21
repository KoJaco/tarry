package me.tarry.app.tracking

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Periodic health check. The OS wakes it (rule 5); all logic is in [HealthChecker]. */
@HiltWorker
class HealthCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val checker: HealthChecker,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        checker.check()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "tracking_health_check"

        /** Enqueues the unique periodic worker; KEEP leaves an existing schedule untouched. */
        fun enqueue(context: Context, config: TrackingConfig) {
            val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(config.healthWorkerPeriodMin, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
