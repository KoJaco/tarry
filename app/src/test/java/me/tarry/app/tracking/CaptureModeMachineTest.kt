package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.PingSource
import org.junit.Test

class CaptureModeMachineTest {
    private val config = TrackingConfig()
    private val min = 60_000L

    private fun machine(activityAvailable: Boolean = true) =
        CaptureModeMachine(config, activityAvailable).also { it.start() }

    @Test
    fun startsMovingWithMovingRequest() {
        val step = CaptureModeMachine(config).start()
        assertThat(step.mode).isEqualTo(CaptureMode.MOVING)
        val request = step.effects.filterIsInstance<CaptureEffect.RequestLocation>().single().params
        assertThat(request.priority).isEqualTo(config.movingPriority)
        assertThat(request.intervalMs).isEqualTo(config.movingIntervalMs)
        assertThat(request.minDisplacementM).isEqualTo(config.movingMinDisplacementM)
        assertThat(request.maxUpdateDelayMs).isEqualTo(config.movingMaxUpdateDelayMs)
    }

    @Test
    fun stillActivityAfterFixEntersStationaryWithGeofenceAtFix() {
        val m = machine()
        val fix = fixAt(0)
        m.onAcceptedFix(fix)
        val step = m.onActivity(ActivityType.STILL)
        assertThat(step.mode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(step.effects).contains(CaptureEffect.AddStayGeofence(fix.lat, fix.lng, config.stayGeofenceRadiusM))
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
        val step = m.onAcceptedFix(fixAt(min, northM = config.stayGeofenceRadiusM + 50.0))
        assertThat(step.mode).isEqualTo(CaptureMode.MOVING)
        assertThat(step.effects).contains(CaptureEffect.RemoveGeofence)
    }
}
