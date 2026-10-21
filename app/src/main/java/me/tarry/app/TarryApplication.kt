package me.tarry.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.HealthCheckWorker
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingController
import javax.inject.Inject

@HiltAndroidApp
class TarryApplication : Application(), Configuration.Provider {
    @Inject lateinit var controller: TrackingController

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var trackingConfig: TrackingConfig

    @Inject lateinit var segmentationScheduler: SegmentationScheduler

    @Inject @CaptureScope lateinit var captureScope: CoroutineScope

    // Hilt injects in super.onCreate(), before WorkManager first asks for this.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        HealthCheckWorker.enqueue(this, trackingConfig)
        segmentationScheduler.ensureDaily()
        // Seeds the schedule on first launch, recovers a crashed session and re-arms the alarm.
        // SYSTEM: process start may be a boot or background wake, which must not start a location FGS directly.
        captureScope.launch { controller.reconcile(ReconcileSource.SYSTEM) }
    }
}
