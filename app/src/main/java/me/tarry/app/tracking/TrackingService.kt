package me.tarry.app.tracking

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.tarry.core.data.model.SessionTrigger
import javax.inject.Inject

/**
 * Location-type foreground service. It only hosts the notification and the process priority; the
 * capture logic lives in [CaptureCoordinator] and start/stop decisions in [TrackingController].
 * Non-sticky: a killed service is recovered by the controller, never silently restarted.
 */
@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var controller: TrackingController

    @Inject lateinit var coordinator: CaptureCoordinator

    @Inject lateinit var notifications: TrackingNotifications

    @Inject lateinit var statusStore: TrackingStatusStore

    @Inject @CaptureScope lateinit var captureScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
            ?.let { name -> SessionTrigger.entries.firstOrNull { it.name == name } }
            ?: SessionTrigger.SCHEDULE
        try {
            ServiceCompat.startForeground(
                this,
                TrackingNotifications.NOTIFICATION_ID,
                notifications.build(CaptureMode.MOVING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
            // ADR 0003: background location missing, so the app is not eligible for a location FGS.
            return fail(StartFailure.SECURITY, startId)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException surfacing from startForeground.
            return fail(StartFailure.FGS_NOT_ALLOWED, startId)
        }
        if (notificationJob == null) {
            notificationJob = serviceScope.launch {
                statusStore.status.map { it.captureMode }.filterNotNull().distinctUntilChanged()
                    .collect { notifications.update(it) }
            }
        }
        captureScope.launch { controller.onServiceStarted(trigger) }
        return START_NOT_STICKY
    }

    private fun fail(failure: StartFailure, startId: Int): Int {
        captureScope.launch { controller.onStartFailure(failure) }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TRIGGER = "trigger"

        fun startIntent(context: Context, trigger: SessionTrigger): Intent =
            Intent(context, TrackingService::class.java).putExtra(EXTRA_TRIGGER, trigger.name)

        fun stopIntent(context: Context): Intent = Intent(context, TrackingService::class.java)
    }
}
