package me.tarry.app.tracking

import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import javax.inject.Inject
import javax.inject.Singleton

/** Starts and stops the foreground service. Implemented on Android by [AndroidServiceLauncher]. */
interface ServiceLauncher {
    /** Starts the service for [trigger]. Throws [ServiceStartDeniedException] if the OS refuses a background start. */
    fun start(trigger: SessionTrigger)

    fun stop()
}

/** The OS refused to start the foreground service (ForegroundServiceStartNotAllowedException). */
class ServiceStartDeniedException(cause: Throwable? = null) : Exception(cause)

/** Why a foreground-service start failed, reported back to [TrackingController.onStartFailure]. */
enum class StartFailure {
    /** `startForegroundService` was not allowed (no exact-alarm exemption). */
    FGS_NOT_ALLOWED,

    /** `startForeground(type=location)` threw SecurityException (location permission not eligible). */
    SECURITY,
}

/**
 * The single entry point that starts or stops capture (phase 2a design notes). Alarms, receivers,
 * the toggle, notification actions and the health worker all go through here.
 *
 * T6 adds `reconcile()` and the schedule; T7 adds manual overrides (`start(endMode)`, `pause`, `resume`).
 */
@Singleton
class TrackingController @Inject constructor(
    private val coordinator: CaptureCoordinator,
    private val launcher: ServiceLauncher,
    private val statusStore: TrackingStatusStore,
) {
    /**
     * Starts the foreground service; the service then opens the session. Never fails silently: a
     * denied start is recorded as ATTENTION in [TrackingStatusStore].
     */
    suspend fun startCapture(trigger: SessionTrigger) {
        try {
            launcher.start(trigger)
        } catch (e: ServiceStartDeniedException) {
            onStartFailure(StartFailure.FGS_NOT_ALLOWED)
        }
    }

    /** Closes the session with [endReason] and stops the service. */
    suspend fun stopCapture(endReason: SessionEndReason) {
        coordinator.end(endReason)
        launcher.stop()
    }

    /** Closes a session left open by a dead process (KILLED, plus a Gap when inside a window). */
    suspend fun recoverCrashedSession() = coordinator.recoverCrashedSession()

    /** Notification action "Pause 1 h". */
    suspend fun pause1h() {
        // TODO(2a-T7): persist a PAUSE ManualOverride (1 h), re-arm the alarm so capture resumes on its own.
        stopCapture(SessionEndReason.USER_PAUSE)
    }

    /** Called by the service or [startCapture] when the OS refused to run the location FGS. */
    suspend fun onStartFailure(failure: StartFailure) {
        when (failure) {
            StartFailure.FGS_NOT_ALLOWED -> statusStore.setAttentionCause(AttentionCause.EXACT_ALARM_DENIED)
            StartFailure.SECURITY -> coordinator.recordLocationPermissionFailure()
        }
        // TODO(2a-T8): open the matching health Gap (PERMISSION_LOST / SERVICE_KILLED) and post the alert notification.
        launcher.stop()
    }

    suspend fun onActivity(type: ActivityType) = coordinator.onActivity(type)

    suspend fun onGeofenceExit() = coordinator.onGeofenceExit()
}
