package me.tarry.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.TrackingController
import javax.inject.Inject

@HiltAndroidApp
class TarryApplication : Application() {
    @Inject lateinit var controller: TrackingController

    @Inject @CaptureScope lateinit var captureScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Seeds the schedule on first launch, recovers a crashed session and re-arms the alarm.
        // SYSTEM: process start may be a boot or background wake, which must not start a location FGS directly.
        captureScope.launch { controller.reconcile(ReconcileSource.SYSTEM) }
    }
}
