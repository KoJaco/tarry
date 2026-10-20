package me.tarry.app.tracking

import me.tarry.core.data.model.GapCause
import javax.inject.Inject

/**
 * Everything the health decision needs, as plain data.
 *
 * @property shouldRun the effective schedule says capture should be active now.
 * @property shouldRunSinceMs when the current active stretch began (window or manual start); null if not [shouldRun].
 * @property serviceRunning a capture session is live in this process.
 */
data class HealthSnapshot(
    val nowMs: Long,
    val shouldRun: Boolean,
    val shouldRunSinceMs: Long?,
    val serviceRunning: Boolean,
    val lastHeartbeatMs: Long?,
    val captureMode: CaptureMode?,
    val alarmDegraded: Boolean,
    val preconditions: Preconditions,
)

/**
 * An outage: why tracking should run but is not, and the [GapCause] its Gap row carries.
 * [recordGap] false means ATTENTION and an alert only: no capture time is being lost (the service is running with
 * fresh fixes, or no window is active), so no Gap is written.
 */
data class HealthIssue(val cause: AttentionCause, val gapCause: GapCause, val recordGap: Boolean = true)

/**
 * Pure decision logic shared by [HealthChecker] (the worker) and [TrackingStateReducer].
 *
 * Cause mapping:
 * - fine / background location missing -> NO_FINE_LOCATION / NO_BACKGROUND_LOCATION, Gap PERMISSION_LOST
 * - location services off -> LOCATION_OFF, Gap LOCATION_OFF
 * - exact alarms denied (or the alarm was armed inexact and the service is not running) -> EXACT_ALARM_DENIED,
 *   Gap SERVICE_KILLED (the service could not run)
 * - no session after [TrackingConfig.serviceStartGraceMs] -> SERVICE_NOT_RUNNING, Gap SERVICE_KILLED
 * - session running but no heartbeat for [TrackingConfig.noHeartbeatThresholdMs] -> NO_FIX_WHILE_MOVING when not
 *   STATIONARY, otherwise SERVICE_NOT_RUNNING (capture is silent for service-level reasons); Gap NO_FIX
 */
class HealthEvaluator @Inject constructor(private val config: TrackingConfig) {

    /** The outage while capture should be running, or null when healthy (or when it should not run). */
    fun evaluate(s: HealthSnapshot): HealthIssue? {
        if (!s.shouldRun) return null
        val p = s.preconditions
        val since = s.shouldRunSinceMs ?: s.nowMs
        val precondition = permissionIssue(p) ?: when {
            !p.locationEnabled -> HealthIssue(AttentionCause.LOCATION_OFF, GapCause.LOCATION_OFF)
            !p.exactAlarms || (s.alarmDegraded && !s.serviceRunning) -> EXACT_ALARM_ISSUE
            else -> null
        }
        if (precondition != null) {
            // Fixes are still arriving: a warning, not lost capture time, so no Gap.
            val fresh = s.serviceRunning && s.nowMs - (s.lastHeartbeatMs ?: since) <= config.noHeartbeatThresholdMs
            return if (fresh) precondition.copy(recordGap = false) else precondition
        }
        if (!s.serviceRunning) {
            return if (s.nowMs - since > config.serviceStartGraceMs) SERVICE_ISSUE else null
        }
        val heartbeat = s.lastHeartbeatMs ?: since
        if (s.nowMs - heartbeat <= config.noHeartbeatThresholdMs) return null
        val cause = if (s.captureMode == CaptureMode.STATIONARY) {
            AttentionCause.SERVICE_NOT_RUNNING
        } else {
            AttentionCause.NO_FIX_WHILE_MOVING
        }
        return HealthIssue(cause, GapCause.NO_FIX)
    }

    /** Configuration problems that matter even outside a window, so they surface before the window starts (ADR 0003). */
    fun standingIssue(p: Preconditions): HealthIssue? =
        (permissionIssue(p) ?: if (!p.exactAlarms) EXACT_ALARM_ISSUE else null)?.copy(recordGap = false)

    private fun permissionIssue(p: Preconditions): HealthIssue? = when {
        !p.fineLocation -> HealthIssue(AttentionCause.NO_FINE_LOCATION, GapCause.PERMISSION_LOST)
        !p.backgroundLocation -> HealthIssue(AttentionCause.NO_BACKGROUND_LOCATION, GapCause.PERMISSION_LOST)
        else -> null
    }

    private companion object {
        val EXACT_ALARM_ISSUE = HealthIssue(AttentionCause.EXACT_ALARM_DENIED, GapCause.SERVICE_KILLED)
        val SERVICE_ISSUE = HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.SERVICE_KILLED)
    }
}
