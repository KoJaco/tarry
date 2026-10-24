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
import androidx.work.WorkInfo
import kotlinx.coroutines.flow.Flow
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

    /**
     * One-time run now. APPEND_OR_REPLACE: a request made while a run is in progress is chained behind it (the run may
     * already have read its inputs, so it does not cover the new edit); [SegmentationRunner] serialises the runs. A
     * chain that is already failed or cancelled when this is called is replaced rather than appended to (a chain whose
     * predecessor fails later would fail its dependents, which is why [SegmentationWorker] never returns failure).
     * N quick edits therefore mean N serial runs; each is cheap, and coalescing them would risk skipping an edit.
     */
    fun enqueueNow() {
        // Each call appends one more run to the chain (see above); the runner mutex keeps them from overlapping.
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<SegmentationWorker>().build())
    }

    /** State of the one-time "run now" work; empty if it never ran. */
    fun observeNow(): Flow<List<WorkInfo>> = workManager.getWorkInfosForUniqueWorkFlow(NOW)

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
