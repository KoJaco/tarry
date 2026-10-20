package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.ManualOverrideEndMode
import org.junit.Test
import java.time.Instant

class HealthLogicTest {
    private val config = TrackingConfig()
    private val evaluator = HealthEvaluator(config)
    private val reducer = TrackingStateReducer(evaluator)

    private val now = 100_000_000L
    private val windowStart = now - 60 * 60_000L

    private fun snapshot(
        shouldRun: Boolean = true,
        since: Long? = windowStart,
        running: Boolean = true,
        heartbeat: Long? = now - 1_000L,
        mode: CaptureMode? = CaptureMode.MOVING,
        degraded: Boolean = false,
        pre: Preconditions = Preconditions(),
    ) = HealthSnapshot(now, shouldRun, if (shouldRun) since else null, running, heartbeat, mode, degraded, pre)

    private fun reduce(
        health: HealthSnapshot = snapshot(),
        pause: ManualOverrideEndMode? = null,
        until: Instant? = null,
        stored: AttentionCause? = null,
    ) = reducer.reduce(TrackingStateInputs(health, pause, until, stored))

    // --- evaluator ---

    @Test
    fun healthyWhenRunningWithFreshHeartbeat() {
        assertThat(evaluator.evaluate(snapshot())).isNull()
    }

    @Test
    fun notRunningOutsideScheduleIsNeverAnOutage() {
        val s = snapshot(shouldRun = false, running = false, pre = Preconditions(fineLocation = false))
        assertThat(evaluator.evaluate(s)).isNull()
    }

    @Test
    fun missingFineLocationIsPermissionLost() {
        val issue = evaluator.evaluate(snapshot(pre = Preconditions(fineLocation = false, backgroundLocation = false)))
        assertThat(issue).isEqualTo(HealthIssue(AttentionCause.NO_FINE_LOCATION, GapCause.PERMISSION_LOST))
    }

    @Test
    fun missingBackgroundLocationIsPermissionLost() {
        val issue = evaluator.evaluate(snapshot(pre = Preconditions(backgroundLocation = false)))
        assertThat(issue).isEqualTo(HealthIssue(AttentionCause.NO_BACKGROUND_LOCATION, GapCause.PERMISSION_LOST))
    }

    @Test
    fun locationServicesOffIsLocationOff() {
        val issue = evaluator.evaluate(snapshot(pre = Preconditions(locationEnabled = false)))
        assertThat(issue).isEqualTo(HealthIssue(AttentionCause.LOCATION_OFF, GapCause.LOCATION_OFF))
    }

    @Test
    fun exactAlarmsDeniedIsAnOutageEvenWhileRunning() {
        val issue = evaluator.evaluate(snapshot(pre = Preconditions(exactAlarms = false)))
        assertThat(issue?.cause).isEqualTo(AttentionCause.EXACT_ALARM_DENIED)
    }

    @Test
    fun degradedAlarmOnlyCountsWhileTheServiceIsNotRunning() {
        assertThat(evaluator.evaluate(snapshot(degraded = true, running = true))).isNull()
        assertThat(evaluator.evaluate(snapshot(degraded = true, running = false))?.cause)
            .isEqualTo(AttentionCause.EXACT_ALARM_DENIED)
    }

    @Test
    fun notRunningIsForgivenInsideTheStartGrace() {
        val s = snapshot(running = false, since = now - config.serviceStartGraceMs)
        assertThat(evaluator.evaluate(s)).isNull()
    }

    @Test
    fun notRunningAfterTheGraceIsServiceNotRunningWithAKilledGap() {
        val s = snapshot(running = false, since = now - config.serviceStartGraceMs - 1)
        assertThat(evaluator.evaluate(s)).isEqualTo(HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.SERVICE_KILLED))
    }

    @Test
    fun staleHeartbeatWhileMovingIsNoFix() {
        val s = snapshot(heartbeat = now - config.noHeartbeatThresholdMs - 1, mode = CaptureMode.MOVING)
        assertThat(evaluator.evaluate(s)).isEqualTo(HealthIssue(AttentionCause.NO_FIX_WHILE_MOVING, GapCause.NO_FIX))
    }

    @Test
    fun heartbeatExactlyAtTheThresholdIsStillHealthy() {
        assertThat(evaluator.evaluate(snapshot(heartbeat = now - config.noHeartbeatThresholdMs))).isNull()
    }

    @Test
    fun staleHeartbeatWhileStationaryIsReportedAsServiceNotRunning() {
        val s = snapshot(heartbeat = now - config.noHeartbeatThresholdMs - 1, mode = CaptureMode.STATIONARY)
        assertThat(evaluator.evaluate(s)).isEqualTo(HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.NO_FIX))
    }

    @Test
    fun missingHeartbeatFallsBackToTheStartOfTheStretch() {
        val s = snapshot(heartbeat = null, since = now - config.noHeartbeatThresholdMs - 1)
        assertThat(evaluator.evaluate(s)?.gapCause).isEqualTo(GapCause.NO_FIX)
    }

    // --- reducer: states ---

    @Test
    fun trackingWhenHealthyAndScheduled() {
        assertThat(reduce().state).isEqualTo(TrackingState.TRACKING)
    }

    @Test
    fun idleOutsideTheSchedule() {
        val r = reduce(snapshot(shouldRun = false, running = false))
        assertThat(r).isEqualTo(ReducedTrackingState(TrackingState.IDLE))
    }

    @Test
    fun pauseWithDurationIsPausedWithResumeTime() {
        val until = Instant.ofEpochMilli(now + 60_000)
        val r = reduce(snapshot(shouldRun = false, running = false), ManualOverrideEndMode.DURATION, until)
        assertThat(r).isEqualTo(ReducedTrackingState(TrackingState.PAUSED, pausedUntil = until))
    }

    @Test
    fun pauseUntilNextWindowIsPaused() {
        val r = reduce(snapshot(shouldRun = false), ManualOverrideEndMode.NEXT_WINDOW, Instant.ofEpochMilli(now))
        assertThat(r.state).isEqualTo(TrackingState.PAUSED)
    }

    @Test
    fun pauseUntilStoppedMeansTheUserStoppedSoIdle() {
        val r = reduce(snapshot(shouldRun = false), ManualOverrideEndMode.UNTIL_STOPPED, Instant.ofEpochMilli(now))
        assertThat(r).isEqualTo(ReducedTrackingState(TrackingState.IDLE))
    }

    @Test
    fun userPauseWinsOverBrokenPreconditions() {
        val h = snapshot(shouldRun = false, pre = Preconditions(fineLocation = false))
        assertThat(reduce(h, ManualOverrideEndMode.DURATION).state).isEqualTo(TrackingState.PAUSED)
    }

    // --- reducer: every cause ---

    @Test
    fun attentionNoFineLocation() =
        assertAttention(AttentionCause.NO_FINE_LOCATION, snapshot(pre = Preconditions(fineLocation = false)))

    @Test
    fun attentionNoBackgroundLocation() =
        assertAttention(AttentionCause.NO_BACKGROUND_LOCATION, snapshot(pre = Preconditions(backgroundLocation = false)))

    @Test
    fun attentionLocationOff() =
        assertAttention(AttentionCause.LOCATION_OFF, snapshot(pre = Preconditions(locationEnabled = false)))

    @Test
    fun attentionServiceNotRunning() = assertAttention(
        AttentionCause.SERVICE_NOT_RUNNING,
        snapshot(running = false, since = now - config.serviceStartGraceMs - 1),
    )

    @Test
    fun attentionNoFixWhileMoving() =
        assertAttention(AttentionCause.NO_FIX_WHILE_MOVING, snapshot(heartbeat = now - config.noHeartbeatThresholdMs - 1))

    @Test
    fun attentionExactAlarmDeniedFromPrecondition() =
        assertAttention(AttentionCause.EXACT_ALARM_DENIED, snapshot(pre = Preconditions(exactAlarms = false)))

    @Test
    fun attentionExactAlarmDeniedFromDegradedAlarmWhenWindowIsDue() =
        assertAttention(AttentionCause.EXACT_ALARM_DENIED, snapshot(degraded = true, running = false))

    private fun assertAttention(cause: AttentionCause, health: HealthSnapshot) {
        assertThat(reduce(health)).isEqualTo(ReducedTrackingState(TrackingState.ATTENTION, cause))
    }

    @Test
    fun failedStartCauseShowsWhileTheServiceIsNotRunning() {
        val r = reduce(snapshot(running = false, since = now - 1_000), stored = AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(r).isEqualTo(ReducedTrackingState(TrackingState.ATTENTION, AttentionCause.NO_BACKGROUND_LOCATION))
    }

    @Test
    fun staleStoredCauseIsIgnoredWhileRunningHealthy() {
        assertThat(reduce(stored = AttentionCause.LOCATION_OFF).state).isEqualTo(TrackingState.TRACKING)
    }

    @Test
    fun startingInsideTheGraceWithNoRecordedFailureIsTracking() {
        assertThat(reduce(snapshot(running = false, since = now - 1_000)).state).isEqualTo(TrackingState.TRACKING)
    }

    @Test
    fun standingPermissionProblemsSurfaceOutsideTheScheduleToo() {
        val h = snapshot(shouldRun = false, running = false, pre = Preconditions(backgroundLocation = false))
        assertThat(reduce(h)).isEqualTo(ReducedTrackingState(TrackingState.ATTENTION, AttentionCause.NO_BACKGROUND_LOCATION))
    }

    @Test
    fun locationOffOutsideTheScheduleIsNotAttention() {
        val h = snapshot(shouldRun = false, running = false, pre = Preconditions(locationEnabled = false))
        assertThat(reduce(h).state).isEqualTo(TrackingState.IDLE)
    }
}
