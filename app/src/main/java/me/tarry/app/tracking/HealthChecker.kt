package me.tarry.app.tracking

import me.tarry.core.data.model.ManualOverrideType
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the inputs for the health evaluator and the state reducer from live data. */
@Singleton
class TrackingSnapshotProvider @Inject constructor(
    private val inputsProvider: ScheduleInputsProvider,
    private val calculator: EffectiveScheduleCalculator,
    private val statusStore: TrackingStatusStore,
    private val preconditions: PreconditionChecker,
    private val coordinator: CaptureCoordinator,
    private val clock: TrackingClock,
) {
    suspend fun capture(): TrackingStateInputs {
        val now = Instant.ofEpochMilli(clock.nowMs())
        val zone = clock.zone()
        val effective = calculator.calculate(inputsProvider.inputs(now, now, zone), now, zone)
        val status = statusStore.current()
        val pause = effective.winningOverride?.takeIf { it.type == ManualOverrideType.PAUSE }
        return TrackingStateInputs(
            health = HealthSnapshot(
                nowMs = now.toEpochMilli(),
                shouldRun = effective.isActive,
                shouldRunSinceMs = effective.activeSince?.toEpochMilli(),
                serviceRunning = coordinator.isActive,
                lastHeartbeatMs = status.lastHeartbeatMs,
                captureMode = status.captureMode,
                alarmDegraded = status.alarmDegraded,
                preconditions = preconditions.check(),
            ),
            pauseEndMode = pause?.endMode,
            pausedUntil = effective.pausedUntil,
            storedCause = status.attentionCause,
        )
    }
}

/**
 * One health check (the logic behind [HealthCheckWorker]). Inside a window with an outage it records the cause,
 * opens or extends the Gap, posts the alert and asks the controller to reconcile. A precondition problem while
 * fixes still arrive, or a standing problem outside a window, is ATTENTION and one alert only (no Gap). Healthy
 * it clears everything. The reconcile runs as SYSTEM: a worker is not exempt from background FGS-start limits,
 * so a restart goes through the short alarm (ADR 0003).
 */
@Singleton
class HealthChecker @Inject constructor(
    private val snapshots: TrackingSnapshotProvider,
    private val evaluator: HealthEvaluator,
    private val recorder: HealthGapRecorder,
    private val controller: TrackingController,
) {
    private fun issueFor(health: HealthSnapshot): HealthIssue? =
        if (health.shouldRun) evaluator.evaluate(health) else evaluator.standingIssue(health.preconditions)

    /**
     * Clears an attention-only cause (no Gap behind it) that no longer applies, so the toggle and alert update when the
     * user fixes a setting instead of waiting for the next periodic check.
     */
    suspend fun clearResolved() {
        val stored = recorder.attentionOnlyCause() ?: return
        // Same ordering as check(): recovery first, then the decision.
        controller.recoverCrashedSession()
        val issue = issueFor(snapshots.capture().health)
        when {
            issue == null -> recorder.recoverCause(stored)
            // A different problem now applies: report it exactly as a periodic check would.
            issue.cause != stored -> check()
        }
    }

    /** Returns the outage found, or null if healthy. */
    suspend fun check(): HealthIssue? {
        // After a reboot the worker can run before BOOT_COMPLETED's recovery: close the dead session (and its Gap)
        // first, so the open health Gap is settled before anything is reported or recovered. No lock is held here,
        // so the order controller -> coordinator -> recorder holds.
        controller.recoverCrashedSession()
        val health = snapshots.capture().health
        // Outside a window only standing configuration problems count, and they never open a Gap (ADR 0003).
        val issue = issueFor(health)
        if (issue == null) {
            recorder.recover(health.nowMs)
            return null
        }
        recorder.reportOutage(issue, health.shouldRunSinceMs)
        // A warning-only issue loses no capture time, so there is nothing to restart.
        if (issue.recordGap) controller.reconcile(ReconcileSource.SYSTEM)
        return issue
    }
}
