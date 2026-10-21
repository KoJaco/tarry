package me.tarry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.HealthChecker
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.ui.TarryApp
import me.tarry.app.ui.theme.TarryTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var trackingState: TrackingStateRepository

    @Inject lateinit var controller: TrackingController

    @Inject lateinit var healthChecker: HealthChecker

    @Inject lateinit var segmentationScheduler: SegmentationScheduler

    @Inject @CaptureScope lateinit var captureScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TarryTheme {
                TarryApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Permissions and settings can change while we were away: re-evaluate now, and let the controller
        // fix what it can (the app is in the foreground, so it may start the service directly).
        trackingState.refresh()
        segmentationScheduler.enqueueNow()
        captureScope.launch {
            controller.reconcile(ReconcileSource.UI)
            healthChecker.clearResolved()
        }
    }
}
