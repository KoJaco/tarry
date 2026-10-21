package me.tarry.app.segmentation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import me.tarry.app.tracking.TrackingConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager wiring for [SegmentationWorker]. The OS wakes the app (rule 5); nothing here polls.
 *
 * Deliberate deviation from "one unique-work name": WorkManager cannot share one name between periodic and one-time
 * work, so there are three names ([NOW], [PERIODIC], [DAILY]). Overlap is prevented by the mutex in
 * [SegmentationRunner], not by work names.
 */
@Singleton
class SegmentationScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: TrackingConfig,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** One-time run now. KEEP: a run that is already queued or running covers the request. */
    fun enqueueNow() {
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SegmentationWorker>().build())
    }

    /** Geocode places that have no address yet. Needs a network; KEEP: a queued or running batch covers the request. */
    fun enqueueGeocode() {
        val request = OneTimeWorkRequestBuilder<GeocodeWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, config.geocodeBackoffSeconds, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(GEOCODE, ExistingWorkPolicy.KEEP, request)
    }

    /** Periodic run while capture is on. */
    fun startPeriodic() {
        val request = PeriodicWorkRequestBuilder<SegmentationWorker>(config.segmentationPeriodMin, TimeUnit.MINUTES)
            .addTag(TAG_PERIODIC)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun stopPeriodic() {
        workManager.cancelUniqueWork(PERIODIC)
    }

    /** Safety-net run once a day while charging. */
    fun ensureDaily() {
        val request = PeriodicWorkRequestBuilder<SegmentationWorker>(config.segmentationDailyPeriodHours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val NOW = "segmentation-now"
        const val PERIODIC = "segmentation-periodic"
        const val DAILY = "segmentation-daily"
        const val GEOCODE = "geocode"
        const val TAG_PERIODIC = "segmentation-periodic-tag"
    }
}
