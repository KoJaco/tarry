package me.tarry.app.tracking

import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.PingSource

/** Parameters for a fused location request, derived from [TrackingConfig]. */
data class LocationRequestParams(
    val priority: LocationPriority,
    val intervalMs: Long,
    val minDisplacementM: Float,
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
    private val activityRecognitionAvailable: Boolean = true,
) {
    var mode: CaptureMode = CaptureMode.MOVING
        private set

    private var activity: ActivityType? = null
    private var anchor: Fix? = null
    private var lastFix: Fix? = null
    private var geofenceCentre: Fix? = null
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
        nextSource = PingSource.GEOFENCE_EXIT
        return if (mode == CaptureMode.STATIONARY) enterMoving() else step()
    }

    /** [fix] must already have been accepted by [PingFilter]. */
    fun onAcceptedFix(fix: Fix): MachineStep {
        lastFix = fix
        if (mode == CaptureMode.STATIONARY) {
            val centre = geofenceCentre
            // Safety net if the geofence exit event is late or lost.
            return if (centre != null && fix.distanceTo(centre) > config.stayGeofenceRadiusM) {
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
        return MachineStep(
            mode,
            listOf(
                CaptureEffect.RequestLocation(
                    LocationRequestParams(
                        priority = config.stationaryPriority,
                        intervalMs = config.stationaryFixIntervalMs,
                        minDisplacementM = 0f,
                        maxUpdateDelayMs = 0L,
                    ),
                ),
                CaptureEffect.AddStayGeofence(at.lat, at.lng, config.stayGeofenceRadiusM),
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
            minDisplacementM = config.movingMinDisplacementM,
            maxUpdateDelayMs = config.movingMaxUpdateDelayMs,
        ),
    )

    private fun step() = MachineStep(mode, emptyList())

    private companion object {
        val MOVING_ACTIVITIES = setOf(ActivityType.WALKING, ActivityType.IN_VEHICLE, ActivityType.ON_BICYCLE)
    }
}
