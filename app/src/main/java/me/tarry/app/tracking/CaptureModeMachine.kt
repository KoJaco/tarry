package me.tarry.app.tracking

import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.settings.Settings

/** Parameters for a fused location request, derived from [TrackingConfig]. */
data class LocationRequestParams(
    val priority: LocationPriority,
    val intervalMs: Long,
    val maxUpdateDelayMs: Long,
    /** Fastest rate fixes may arrive; null means the same as [intervalMs]. */
    val minUpdateIntervalMs: Long? = null,
    val waitForAccurateLocation: Boolean = false,
    /** Oldest fix to deliver as an update; null leaves the platform default. */
    val maxUpdateAgeMs: Long? = null,
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
 * MOVING is left again, whatever the activity says, when accepted fixes make no progress (see
 * [TrackingConfig.noProgressFixes]); only a fresh moving transition or a geofence exit re-enters it.
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

    /** True while STATIONARY and the latest fix was clearly outside the stop (the exit is waiting for a second one). */
    val leavingStationary: Boolean get() = mode == CaptureMode.STATIONARY && exitBase != null

    var mode: CaptureMode = CaptureMode.MOVING
        private set

    private var activity: ActivityType? = null
    private var anchor: Fix? = null
    private var lastFix: Fix? = null
    private var geofenceCentre: Fix? = null
    private var geofenceRadiusM: Float = Settings.DEFAULT_STAY_RADIUS_M.toFloat()
    private var stillPending = false
    private var nextSource = PingSource.FUSED
    private var progressBase: Fix? = null
    private var progressCount = 0
    private var exitBase: Fix? = null
    private var exitCount = 0
    private val recentFixes = ArrayDeque<Fix>()

    /** Time of the fix the current STATIONARY stay was entered at; null while MOVING. */
    var stationarySinceMs: Long? = null
        private set

    /** Session start: always begin MOVING so the first fix is quick. */
    fun start(): MachineStep {
        mode = CaptureMode.MOVING
        activity = null
        anchor = null
        lastFix = null
        recentFixes.clear()
        geofenceCentre = null
        stationarySinceMs = null
        stillPending = false
        nextSource = PingSource.FUSED
        resetProgress()
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

    /** [fix] must already have been accepted by [PingFilter] (capture keeps every accepted fix; there is no displacement or jitter gate). */
    fun onAcceptedFix(fix: Fix): MachineStep {
        lastFix = fix
        val repeated = recentFixes.any { it.lat == fix.lat && it.lng == fix.lng }
        recentFixes.addLast(fix)
        while (recentFixes.size > config.staleRepeatMemory) recentFixes.removeFirst()
        if (mode == CaptureMode.STATIONARY) {
            val centre = geofenceCentre
            // Safety net if the geofence exit event is late or lost: accuracy-aware and sustained, so one noisy hop
            // (a berthed boat) does not flap the mode.
            if (centre == null || fix.distanceTo(centre) - fix.accuracyM <= geofenceRadiusM) {
                exitBase = null
                exitCount = 0
                return step()
            }
            // An exact repeat of a recent coordinate is a provider re-emitting an anchor (network fixes alternate
            // between a few), not a new reading: it is never evidence of leaving.
            if (repeated) return step()
            val base = exitBase ?: fix.also { exitBase = it }
            exitCount++
            if (exitCount < config.stationaryExitFixes || fix.timeMs - base.timeMs < config.stationaryExitMinMs) return step()
            nextSource = PingSource.GEOFENCE_EXIT
            return enterMoving()
        }
        if (stillPending) return enterStationary(fix)
        if (noProgress(fix)) return enterStationary(fix)
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

    /** True once [TrackingConfig.noProgressFixes] fixes in a row, over the minimum duration, stayed near the run's first. */
    private fun noProgress(fix: Fix): Boolean {
        val base = progressBase
        val radius = stayRadiusM * config.noProgressRadiusFactor
        if (base == null || fix.distanceTo(base) - fix.accuracyM > radius) {
            progressBase = fix
            progressCount = 1
            return false
        }
        progressCount++
        return progressCount >= config.noProgressFixes && fix.timeMs - base.timeMs >= config.noProgressMinDurationMs
    }

    private fun resetProgress() {
        exitBase = null
        exitCount = 0
        progressBase = null
        progressCount = 0
    }

    private fun distanceGatingActive(): Boolean =
        !activityRecognitionAvailable || activity !in MOVING_ACTIVITIES

    private fun enterStationary(at: Fix): MachineStep {
        if (mode == CaptureMode.STATIONARY) return step()
        mode = CaptureMode.STATIONARY
        stillPending = false
        anchor = null
        resetProgress()
        geofenceCentre = at
        stationarySinceMs = at.timeMs
        geofenceRadiusM = stayRadiusM
        return MachineStep(
            mode,
            listOf(
                CaptureEffect.RequestLocation(
                    LocationRequestParams(
                        priority = config.stationaryPriority,
                        intervalMs = config.stationaryFixIntervalMs,
                        maxUpdateDelayMs = 0L,
                        minUpdateIntervalMs = config.stationaryFixIntervalMs,
                        waitForAccurateLocation = config.waitForAccurateLocation,
                        maxUpdateAgeMs = config.maxUpdateAgeMs,
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
        resetProgress()
        geofenceCentre = null
        stationarySinceMs = null
        return MachineStep(mode, listOf(CaptureEffect.RemoveGeofence, requestMoving()))
    }

    private fun requestMoving() = CaptureEffect.RequestLocation(
        LocationRequestParams(
            priority = config.movingPriority,
            intervalMs = config.movingIntervalMs,
            maxUpdateDelayMs = config.movingMaxUpdateDelayMs,
            minUpdateIntervalMs = config.movingIntervalMs,
            waitForAccurateLocation = config.waitForAccurateLocation,
            maxUpdateAgeMs = config.maxUpdateAgeMs,
        ),
    )

    private fun step() = MachineStep(mode, emptyList())

    private companion object {
        val MOVING_ACTIVITIES = setOf(ActivityType.WALKING, ActivityType.IN_VEHICLE, ActivityType.ON_BICYCLE)
    }
}
