package me.tarry.app.tracking

import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.settings.Settings

/** Parameters for a fused location request, derived from [TrackingConfig]. */
data class LocationRequestParams(
    val priority: LocationPriority,
    val intervalMs: Long,
    val maxUpdateDelayMs: Long,
)

/** Side effects the service must perform after a machine step. */
sealed interface CaptureEffect {
    data class RequestLocation(val params: LocationRequestParams) : CaptureEffect
    data class AddStayGeofence(val lat: Double, val lng: Double, val radiusM: Float) : CaptureEffect
    data object RemoveGeofence : CaptureEffect
}

data class MachineStep(val mode: CaptureMode, val effects: List<CaptureEffect>)

/**
 * Pure capture-mode state machine (not thread-safe). Feed it activity changes, geofence exits and
 * fixes already accepted by [PingFilter]; apply the returned effects.
 *
 * STATIONARY is entered on activity STILL or when accepted fixes stay within
 * [TrackingConfig.stationaryEntryRadiusM] for [TrackingConfig.stationaryEntryDurationMs].
 * The distance rule applies when [activityRecognitionAvailable] is false (distance-only gating)
 * or when the last activity is not a moving one (so a vehicle stopped in traffic is not demoted).
 * MOVING is entered on a moving activity, a geofence exit, or a fix beyond the stay geofence.
 */
class CaptureModeMachine(
    private val config: TrackingConfig,
    activityRecognitionAvailable: Boolean = true,
) {
    private var activityRecognitionAvailable = activityRecognitionAvailable

    /**
     * Radius of the stay geofence created at the next STATIONARY entry (set by the coordinator from the user setting).
     * A stop that is already active keeps the radius it was created with.
     */
    var stayRadiusM: Float = Settings.DEFAULT_STAY_RADIUS_M.toFloat()

    var mode: CaptureMode = CaptureMode.MOVING
        private set

    private var activity: ActivityType? = null
    private var anchor: Fix? = null
    private var lastFix: Fix? = null
    private var geofenceCentre: Fix? = null
    private var geofenceRadiusM: Float = Settings.DEFAULT_STAY_RADIUS_M.toFloat()
    private var stillPending = false
    private var nextSource = PingSource.FUSED

    /** Session start: always begin MOVING so the first fix is quick. */
    fun start(): MachineStep {
        mode = CaptureMode.MOVING
        activity = null
        anchor = null
        lastFix = null
        geofenceCentre = null
        stillPending = false
        nextSource = PingSource.FUSED
        return MachineStep(mode, listOf(CaptureEffect.RemoveGeofence, requestMoving()))
    }

    /** Source for the next saved ping: GEOFENCE_EXIT once after an exit, otherwise FUSED. */
    fun takeNextPingSource(): PingSource = nextSource.also { nextSource = PingSource.FUSED }

    /** Drops a pending GEOFENCE_EXIT tag (the fix it was meant for is not saved). */
    fun discardPendingSource() {
        nextSource = PingSource.FUSED
    }

    /** Activity Recognition registered after the session began: STILL/moving transitions now take effect. */
    fun onActivityRecognitionAvailable() {
        activityRecognitionAvailable = true
    }

    fun onActivity(type: ActivityType): MachineStep {
        if (!activityRecognitionAvailable) return step()
        activity = type
        return when (type) {
            ActivityType.STILL -> {
                val fix = lastFix
                if (fix == null) {
                    stillPending = true
                    step()
                } else {
                    enterStationary(fix)
                }
            }
            ActivityType.WALKING, ActivityType.IN_VEHICLE, ActivityType.ON_BICYCLE -> {
                stillPending = false
                enterMoving()
            }
            ActivityType.UNKNOWN -> step()
        }
    }

    fun onGeofenceExit(): MachineStep {
        stillPending = false
        if (mode != CaptureMode.STATIONARY) return step()
        // Only a real exit from a stop is tagged: a late OS event after the safety net already fired is not.
        nextSource = PingSource.GEOFENCE_EXIT
        return enterMoving()
    }

    /** [fix] must already have passed [PingFilter]: accepted, or only held back from saving by the displacement gate or STATIONARY jitter. */
    fun onAcceptedFix(fix: Fix): MachineStep {
        lastFix = fix
        if (mode == CaptureMode.STATIONARY) {
            val centre = geofenceCentre
            // Safety net if the geofence exit event is late or lost.
            return if (centre != null && fix.distanceTo(centre) > geofenceRadiusM) {
                nextSource = PingSource.GEOFENCE_EXIT
                enterMoving()
            } else {
                step()
            }
        }
        if (stillPending) return enterStationary(fix)
        if (!distanceGatingActive()) {
            anchor = null
            return step()
        }
        val a = anchor
        return when {
            a == null || fix.distanceTo(a) > config.stationaryEntryRadiusM -> {
                anchor = fix
                step()
            }
            fix.timeMs - a.timeMs >= config.stationaryEntryDurationMs -> enterStationary(fix)
            else -> step()
        }
    }

    private fun distanceGatingActive(): Boolean =
        !activityRecognitionAvailable || activity !in MOVING_ACTIVITIES

    private fun enterStationary(at: Fix): MachineStep {
        if (mode == CaptureMode.STATIONARY) return step()
        mode = CaptureMode.STATIONARY
        stillPending = false
        anchor = null
        geofenceCentre = at
        geofenceRadiusM = stayRadiusM
        return MachineStep(
            mode,
            listOf(
                CaptureEffect.RequestLocation(
                    LocationRequestParams(
                        priority = config.stationaryPriority,
                        intervalMs = config.stationaryFixIntervalMs,
                        maxUpdateDelayMs = 0L,
                    ),
                ),
                CaptureEffect.AddStayGeofence(at.lat, at.lng, geofenceRadiusM),
            ),
        )
    }

    private fun enterMoving(): MachineStep {
        if (mode == CaptureMode.MOVING) return step()
        mode = CaptureMode.MOVING
        anchor = lastFix
        geofenceCentre = null
        return MachineStep(mode, listOf(CaptureEffect.RemoveGeofence, requestMoving()))
    }

    private fun requestMoving() = CaptureEffect.RequestLocation(
        LocationRequestParams(
            priority = config.movingPriority,
            intervalMs = config.movingIntervalMs,
            maxUpdateDelayMs = config.movingMaxUpdateDelayMs,
        ),
    )

    private fun step() = MachineStep(mode, emptyList())

    private companion object {
        val MOVING_ACTIVITIES = setOf(ActivityType.WALKING, ActivityType.IN_VEHICLE, ActivityType.ON_BICYCLE)
    }
}
