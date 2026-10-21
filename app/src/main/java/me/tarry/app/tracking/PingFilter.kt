package me.tarry.app.tracking

/** Outcome of [PingFilter.offer]. */
enum class FilterDecision(val accepted: Boolean) {
    ACCEPT(true),
    DROP_ACCURACY(false),
    DROP_SPEED(false),
    DROP_JITTER(false),

    /** Not saved (moved less than [TrackingConfig.movingMinDisplacementM] in MOVING) but still fed to the machine. */
    DROP_DISPLACEMENT(false),
}

/**
 * Capture-time ping filter. Stateful over the last accepted fix; not thread-safe.
 *
 * Rules, in order:
 * 1. accuracy worse than [TrackingConfig.maxAccuracyM] is dropped;
 * 2. a fix implying more than [TrackingConfig.maxSpeedMps] from the last accepted fix is dropped
 *    (a fix not newer than the last accepted one is also dropped, as DROP_SPEED, since no speed can be implied);
 * 3. in STATIONARY, a fix inside its own accuracy circle around the last accepted fix is dropped as jitter,
 *    unless [TrackingConfig.stationaryKeepaliveMs] has passed since the last accepted fix (a keepalive ping, so a
 *    long stay is not read as a recording gap);
 * 4. in MOVING, a fix less than [TrackingConfig.movingMinDisplacementM] from the last accepted fix is not persisted
 *    (DROP_DISPLACEMENT). Fused delivers every fix, so the stationary rule and the heartbeat keep working; the
 *    gate is applied here rather than in the location request.
 */
class PingFilter(private val config: TrackingConfig) {
    var lastAccepted: Fix? = null
        private set

    fun offer(fix: Fix, mode: CaptureMode): FilterDecision {
        if (fix.accuracyM > config.maxAccuracyM) return FilterDecision.DROP_ACCURACY
        val last = lastAccepted
        if (last != null) {
            val dtMs = fix.timeMs - last.timeMs
            val distance = fix.distanceTo(last)
            if (dtMs <= 0) return FilterDecision.DROP_SPEED
            if (distance / (dtMs / 1000.0) > config.maxSpeedMps) return FilterDecision.DROP_SPEED
            if (mode == CaptureMode.STATIONARY && distance <= fix.accuracyM &&
                dtMs < config.stationaryKeepaliveMs
            ) {
                return FilterDecision.DROP_JITTER
            }
            if (mode == CaptureMode.MOVING && distance < config.movingMinDisplacementM) {
                return FilterDecision.DROP_DISPLACEMENT
            }
        }
        lastAccepted = fix
        return FilterDecision.ACCEPT
    }

    /** Forget the last accepted fix (new session). */
    fun reset() {
        lastAccepted = null
    }
}
