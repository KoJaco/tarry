package me.tarry.app.tracking

import me.tarry.core.data.model.ManualOverrideEndMode
import java.time.Instant
import javax.inject.Inject

/**
 * @property pauseEndMode end mode of the manual PAUSE override in force, null if none. UNTIL_STOPPED means the
 *   user pressed Stop (T7), which is IDLE; any other pause is PAUSED.
 * @property pausedUntil when the pause ends; null if open-ended.
 * @property storedCause the cause persisted by a failed start or session, used while the service is not running.
 */
data class TrackingStateInputs(
    val health: HealthSnapshot,
    val pauseEndMode: ManualOverrideEndMode? = null,
    val pausedUntil: Instant? = null,
    val storedCause: AttentionCause? = null,
)

data class ReducedTrackingState(
    val state: TrackingState,
    val attentionCause: AttentionCause? = null,
    val pausedUntil: Instant? = null,
)

/**
 * Pure reducer behind the toggle. Precedence: user Stop/Pause, then outages (ATTENTION), then TRACKING or IDLE.
 * Outside a window only standing configuration problems (permissions, exact alarms) raise ATTENTION.
 */
class TrackingStateReducer @Inject constructor(private val evaluator: HealthEvaluator) {
    fun reduce(inputs: TrackingStateInputs): ReducedTrackingState {
        inputs.pauseEndMode?.let { mode ->
            return if (mode == ManualOverrideEndMode.UNTIL_STOPPED) {
                ReducedTrackingState(TrackingState.IDLE)
            } else {
                ReducedTrackingState(TrackingState.PAUSED, pausedUntil = inputs.pausedUntil)
            }
        }
        val h = inputs.health
        val issue = if (h.shouldRun) evaluator.evaluate(h) else evaluator.standingIssue(h.preconditions)
        if (issue != null) return ReducedTrackingState(TrackingState.ATTENTION, issue.cause)
        if (!h.shouldRun) return ReducedTrackingState(TrackingState.IDLE)
        // Inside the start grace period, a failed start has already recorded why.
        val stored = inputs.storedCause
        if (!h.serviceRunning && stored != null) return ReducedTrackingState(TrackingState.ATTENTION, stored)
        return ReducedTrackingState(TrackingState.TRACKING)
    }
}
