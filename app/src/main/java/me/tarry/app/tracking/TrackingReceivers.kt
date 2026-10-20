package me.tarry.app.tracking

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs [block] off the main thread while keeping the receiver alive. */
private fun BroadcastReceiver.runAsync(scope: CoroutineScope, block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** Receives Activity Recognition transitions (non-exported). Only ENTER transitions are forwarded. */
@AndroidEntryPoint
class ActivityTransitionReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TrackingController

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val types = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
            .filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .map { detectedActivityToType(it.activityType) }
        if (types.isEmpty()) return
        runAsync(scope) { types.forEach { controller.onActivity(it) } }
    }
}

/** Receives the stay-geofence EXIT transition (non-exported). */
@AndroidEntryPoint
class GeofenceExitReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TrackingController

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        runAsync(scope) { controller.onGeofenceExit() }
    }
}

/** Notification actions "Pause 1 h" and "Stop" (non-exported). */
@AndroidEntryPoint
class TrackingActionReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TrackingController

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TrackingIntents.ACTION_PAUSE_1H -> runAsync(scope) { controller.pause(PauseKind.PAUSE_1H) }
            TrackingIntents.ACTION_STOP -> runAsync(scope) { controller.stop() }
        }
    }
}

/** The schedule alarm (non-exported): a window edge or a deferred start. ADR 0003: this is the FGS-start exemption path. */
@AndroidEntryPoint
class ScheduleAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TrackingController

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        runAsync(scope) { controller.reconcile(ReconcileSource.ALARM) }
    }
}

/**
 * Re-arms after anything that invalidates the alarm: boot, app update, clock or zone change, or a change
 * to the exact-alarm permission. System broadcasts reach non-exported receivers, so this stays non-exported.
 * It never starts the service directly (boot-completed receivers may not start a location FGS);
 * [ReconcileSource.SYSTEM] defers a start to an alarm.
 */
@AndroidEntryPoint
class ScheduleRearmReceiver : BroadcastReceiver() {
    @Inject lateinit var controller: TrackingController

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runAsync(scope) { controller.reconcile(ReconcileSource.SYSTEM) }
        }
    }
}
