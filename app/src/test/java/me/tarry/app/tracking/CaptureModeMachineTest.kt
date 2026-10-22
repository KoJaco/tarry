package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.settings.Settings
import org.junit.Test

class CaptureModeMachineTest {
    private val config = TrackingConfig()
    private val min = 60_000L
    private val DEFAULT_RADIUS = Settings.DEFAULT_STAY_RADIUS_M.toFloat()

    private fun machine(activityAvailable: Boolean = true) =
        CaptureModeMachine(config, activityAvailable).also { it.start() }

    /** Two consecutive fixes clearly outside the geofence, a minute apart (the sustained safety net). Returns the second step. */
    private fun leaveGeofence(m: CaptureModeMachine, northM: Double): MachineStep {
        m.onAcceptedFix(fixAt(min, northM = northM))
        return m.onAcceptedFix(fixAt(2 * min, northM = northM))
    }

    @Test
    fun startsMovingWithMovingRequest() {
        val step = CaptureModeMachine(config).start()
        assertThat(step.mode).isEqualTo(CaptureMode.MOVING)
        val request = step.effects.filterIsInstance<CaptureEffect.RequestLocation>().single().params
        assertThat(request.priority).isEqualTo(config.movingPriority)
        assertThat(request.intervalMs).isEqualTo(config.movingIntervalMs)
        assertThat(request.maxUpdateDelayMs).isEqualTo(config.movingMaxUpdateDelayMs)
    }

    @Test
    fun stillActivityAfterFixEntersStationaryWithGeofenceAtFix() {
        val m = machine()
        val fix = fixAt(0)
        m.onAcceptedFix(fix)
        val step = m.onActivity(ActivityType.STILL)
        assertThat(step.mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(step.effects).contains(CaptureEffect.AddStayGeofence(fix.lat, fix.lng, DEFAULT_RADIUS))
        val request = step.effects.filterIsInstance<CaptureEffect.RequestLocation>().single().params
        assertThat(request.priority).isEqualTo(config.stationaryPriority)
        assertThat(request.intervalMs).isEqualTo(config.stationaryFixIntervalMs)
    }

    @Test
    fun stillActivityBeforeAnyFixDefersToFirstFix() {
        val m = machine()
        assertThat(m.onActivity(ActivityType.STILL).mode).isEqualTo(CaptureMode.MOVING)
        val step = m.onAcceptedFix(fixAt(0))
        assertThat(step.mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(step.effects.filterIsInstance<CaptureEffect.AddStayGeofence>()).hasSize(1)
    }

    @Test
    fun movingActivityLeavesStationaryAndRemovesGeofence() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        val step = m.onActivity(ActivityType.WALKING)
        assertThat(step.mode).isEqualTo(CaptureMode.MOVING)
        assertThat(step.effects).contains(CaptureEffect.RemoveGeofence)
        assertThat(step.effects.filterIsInstance<CaptureEffect.RequestLocation>()).hasSize(1)
    }

    @Test
    fun geofenceExitWakesMovingAndTagsNextPing() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        val step = m.onGeofenceExit()
        assertThat(step.mode).isEqualTo(CaptureMode.MOVING)
        assertThat(step.effects).contains(CaptureEffect.RemoveGeofence)
        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.GEOFENCE_EXIT)
        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.FUSED)
    }

    @Test
    fun noMovementForEntryDurationEntersStationary() {
        val m = machine()
        assertThat(m.onAcceptedFix(fixAt(0)).mode).isEqualTo(CaptureMode.MOVING)
        assertThat(m.onAcceptedFix(fixAt(config.stationaryEntryDurationMs - 1, northM = 10.0)).mode)
            .isEqualTo(CaptureMode.MOVING)
        assertThat(m.onAcceptedFix(fixAt(config.stationaryEntryDurationMs, northM = 10.0)).mode)
            .isEqualTo(CaptureMode.STATIONARY)
    }

    @Test
    fun movementBeyondEntryRadiusResetsTheStillClock() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onAcceptedFix(fixAt(2 * min, northM = config.stationaryEntryRadiusM + 20.0))
        val step = m.onAcceptedFix(fixAt(4 * min, northM = config.stationaryEntryRadiusM + 20.0))
        assertThat(step.mode).isEqualTo(CaptureMode.MOVING) // only 2 min since new anchor
    }

    @Test
    fun movingActivitySuppressesDistanceGatingWhenRecognitionAvailable() {
        val m = machine(activityAvailable = true)
        m.onActivity(ActivityType.IN_VEHICLE)
        m.onAcceptedFix(fixAt(0))
        assertThat(m.onAcceptedFix(fixAt(10 * min)).mode).isEqualTo(CaptureMode.MOVING)
    }

    @Test
    fun withoutActivityRecognitionDistanceOnlyGatingApplies() {
        val m = machine(activityAvailable = false)
        // activity events are ignored entirely
        assertThat(m.onActivity(ActivityType.IN_VEHICLE).effects).isEmpty()
        m.onAcceptedFix(fixAt(0))
        assertThat(m.onAcceptedFix(fixAt(config.stationaryEntryDurationMs)).mode).isEqualTo(CaptureMode.STATIONARY)
    }

    @Test
    fun stationaryFixBeyondGeofenceRadiusFallsBackToMoving() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        val step = leaveGeofence(m, DEFAULT_RADIUS + 50.0)
        assertThat(step.mode).isEqualTo(CaptureMode.MOVING)
        assertThat(step.effects).contains(CaptureEffect.RemoveGeofence)
    }

    @Test
    fun safetyNetExitTagsTheTriggeringFixAsGeofenceExit() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        assertThat(leaveGeofence(m, DEFAULT_RADIUS + 50.0).mode).isEqualTo(CaptureMode.MOVING)
        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.GEOFENCE_EXIT)
        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.FUSED)
    }

    @Test
    fun discardPendingSourceClearsTheExitTag() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        m.onGeofenceExit()
        m.discardPendingSource()
        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.FUSED)
    }

    @Test
    fun activityTransitionsAreIgnoredUntilRecognitionBecomesAvailable() {
        val m = machine(activityAvailable = false)
        m.onAcceptedFix(fixAt(0))
        assertThat(m.onActivity(ActivityType.STILL).effects).isEmpty()
        assertThat(m.mode).isEqualTo(CaptureMode.MOVING)

        m.onActivityRecognitionAvailable()
        assertThat(m.onActivity(ActivityType.STILL).mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(m.onActivity(ActivityType.WALKING).mode).isEqualTo(CaptureMode.MOVING)
    }

    @Test
    fun radiusChangeWhileStationaryAppliesOnlyToTheNextStop() {
        val m = machine()
        m.stayRadiusM = DEFAULT_RADIUS
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)

        // Changed mid-stop: the safety net for this stop still uses 100 m.
        m.stayRadiusM = 300f
        val within = leaveGeofence(m, 150.0)
        assertThat(within.mode).isEqualTo(CaptureMode.MOVING)
        m.takeNextPingSource()

        // Next stop uses the new radius.
        val step = m.onActivity(ActivityType.STILL)
        assertThat(step.effects.filterIsInstance<CaptureEffect.AddStayGeofence>().single().radiusM).isEqualTo(300f)
    }

    @Test
    fun lateGeofenceExitAfterTheSafetyNetDoesNotTagTheNextFix() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        leaveGeofence(m, DEFAULT_RADIUS + 50.0)
        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.GEOFENCE_EXIT)

        m.onGeofenceExit()
        m.onAcceptedFix(fixAt(4 * min, northM = DEFAULT_RADIUS + 400.0))

        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.FUSED)
    }

    @Test
    fun berthedBoatReportingInVehicleDemotesToStationary() {
        val m = machine()
        m.onActivity(ActivityType.IN_VEHICLE)
        // 20 s fixes hopping a few tens of metres around the berth, noisy accuracy, activity never changes.
        var step = MachineStep(m.mode, emptyList())
        var t = 0L
        val hops = listOf(0.0, 40.0, -30.0, 20.0, -45.0, 35.0, -10.0, 25.0, -20.0, 30.0, 0.0, 15.0, -35.0, 10.0, 5.0, -5.0, 20.0, -15.0)
        for (north in hops) {
            step = m.onAcceptedFix(fixAt(t, northM = north, accuracyM = 40f))
            t += 20_000L
            if (step.mode == CaptureMode.STATIONARY) break
        }
        assertThat(step.mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(step.effects.filterIsInstance<CaptureEffect.AddStayGeofence>()).hasSize(1)
        // Still IN_VEHICLE, but no fresh transition: stays STATIONARY while the fixes stay inside the geofence.
        assertThat(m.onAcceptedFix(fixAt(t, northM = 10.0, accuracyM = 40f)).mode).isEqualTo(CaptureMode.STATIONARY)
        // A fresh moving transition re-enters MOVING.
        m.onActivity(ActivityType.STILL)
        assertThat(m.onActivity(ActivityType.IN_VEHICLE).mode).isEqualTo(CaptureMode.MOVING)
    }

    @Test
    fun noProgressNeedsTheFixCountAndTheMinimumDuration() {
        val m = machine()
        m.onActivity(ActivityType.IN_VEHICLE)
        // Many fixes in under the minimum duration: not yet.
        for (i in 0 until 10) assertThat(m.onAcceptedFix(fixAt(i * 20_000L, northM = (i % 2) * 5.0)).mode).isEqualTo(CaptureMode.MOVING)
    }

    @Test
    fun driveWithThreeMinuteTrafficStopStaysMoving() {
        val m = machine()
        m.onActivity(ActivityType.IN_VEHICLE)
        var t = 0L
        var north = 0.0
        // Driving 10 m/s.
        repeat(6) { assertThat(m.onAcceptedFix(fixAt(t, northM = north)).mode).isEqualTo(CaptureMode.MOVING); t += 20_000L; north += 200.0 }
        // Stopped at the lights for 3 minutes (9 fixes in the same place).
        repeat(10) { assertThat(m.onAcceptedFix(fixAt(t, northM = north)).mode).isEqualTo(CaptureMode.MOVING); t += 20_000L }
        // Moves off again.
        repeat(6) { north += 200.0; assertThat(m.onAcceptedFix(fixAt(t, northM = north)).mode).isEqualTo(CaptureMode.MOVING); t += 20_000L }
    }

    @Test
    fun geofenceExitReentersMovingAfterDemotion() {
        val m = machine()
        m.onActivity(ActivityType.IN_VEHICLE)
        var t = 0L
        while (m.mode == CaptureMode.MOVING && t < 20 * 60_000L) { m.onAcceptedFix(fixAt(t)); t += 20_000L }
        assertThat(m.mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(m.onGeofenceExit().mode).isEqualTo(CaptureMode.MOVING)
        assertThat(m.takeNextPingSource()).isEqualTo(PingSource.GEOFENCE_EXIT)
    }

    @Test
    fun singleOutsideFixOrNoisyAccuracyDoesNotLeaveStationary() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        // One hop beyond the radius is an outlier.
        assertThat(m.onAcceptedFix(fixAt(min, northM = DEFAULT_RADIUS + 50.0)).mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(m.onAcceptedFix(fixAt(2 * min, northM = 5.0)).mode).isEqualTo(CaptureMode.STATIONARY)
        // Outside by distance, but the accuracy circle reaches the stop: not clearly outside.
        assertThat(m.onAcceptedFix(fixAt(3 * min, northM = DEFAULT_RADIUS + 50.0, accuracyM = 80f)).mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(m.onAcceptedFix(fixAt(4 * min, northM = DEFAULT_RADIUS + 50.0, accuracyM = 80f)).mode).isEqualTo(CaptureMode.STATIONARY)
    }

    @Test
    fun berthedBoatStaysStationaryOverHalfAnHourOfNoisyHops() {
        val m = machine()
        m.onActivity(ActivityType.IN_VEHICLE)
        // Spec shape: 20 s fixes hopping 100-270 m at 30-98 m accuracy; activity never leaves IN_VEHICLE.
        val hops = listOf(120.0, -150.0, 270.0, -100.0, 180.0, -230.0, 140.0, -110.0, 200.0, -260.0, 160.0)
        val accuracies = listOf(30f, 98f, 60f, 45f, 80f, 70f, 35f, 90f, 55f, 98f, 40f)
        // Quiet first, so capture demotes to STATIONARY.
        var t = 0L
        while (m.mode == CaptureMode.MOVING && t < 20 * min) { m.onAcceptedFix(fixAt(t, northM = (t / 20_000L % 2) * 10.0, accuracyM = 40f)); t += 20_000L }
        assertThat(m.mode).isEqualTo(CaptureMode.STATIONARY)
        val noisyStart = t
        var promotions = 0
        var i = 0
        while (t <= noisyStart + 40 * min) {
            val before = m.mode
            val step = m.onAcceptedFix(fixAt(t, northM = hops[i % hops.size], accuracyM = accuracies[(i * 3) % accuracies.size]))
            if (before == CaptureMode.STATIONARY && step.mode == CaptureMode.MOVING) promotions++
            t += 20_000L
            i++
        }
        assertThat(m.mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(promotions).isEqualTo(0)
    }

    @Test
    fun realDepartureStillPromotesOnTheSecondOutsideFix() {
        val m = machine()
        m.onAcceptedFix(fixAt(0))
        m.onActivity(ActivityType.STILL)
        assertThat(m.onAcceptedFix(fixAt(3 * min, northM = 500.0)).mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(m.onAcceptedFix(fixAt(6 * min, northM = 1500.0)).mode).isEqualTo(CaptureMode.MOVING)
    }
}
