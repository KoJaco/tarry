package me.tarry.app.tracking

/** Outcome of [PingFilter.offer]. */
enum class FilterDecision(val accepted: Boolean) {
    ACCEPT(true),
    DROP_ACCURACY(false),
    DROP_SPEED(false),

    /** Same position as a recently offered fix with no better accuracy: a re-emitted cached position, not a new reading. */
    DROP_STALE(false),
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
 *    (also by `elapsedRealtimeNanos` when both fixes carry it);
 * 2b. a fix at the accuracy limit ([TrackingConfig.maxAccuracyM]) with exactly the lat/lng of one of the last
 *    [TrackingConfig.staleRepeatMemory] offered fixes is dropped as DROP_STALE. A repeat with better accuracy is a real
 *    reading (a still phone on a steady Wi-Fi position) and falls through to the jitter and keepalive rules.
 *    Dogfood showed the fused provider re-emitting its last known position
 *    with a fresh `Location.time` and accuracy inflated to the cap, so a time check alone cannot catch these;
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
    private val recent = ArrayDeque<Fix>()

    fun offer(fix: Fix, mode: CaptureMode): FilterDecision {
        if (fix.accuracyM > config.maxAccuracyM) return FilterDecision.DROP_ACCURACY
        val last = lastAccepted
        if (last != null) {
            val dtMs = fix.timeMs - last.timeMs
            val distance = fix.distanceTo(last)
            if (dtMs <= 0) return FilterDecision.DROP_SPEED
            val elapsed = fix.elapsedRealtimeNanos
            val lastElapsed = last.elapsedRealtimeNanos
            if (elapsed != null && lastElapsed != null && elapsed <= lastElapsed) return FilterDecision.DROP_SPEED
        }
        val repeated = fix.accuracyM >= config.maxAccuracyM &&
            recent.any { it.lat == fix.lat && it.lng == fix.lng }
        remember(fix)
        if (repeated) return FilterDecision.DROP_STALE
        if (last != null) {
            val dtMs = fix.timeMs - last.timeMs
            val distance = fix.distanceTo(last)
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

    private fun remember(fix: Fix) {
        recent.addLast(fix)
        while (recent.size > config.staleRepeatMemory) recent.removeFirst()
    }

    /** Forget the last accepted fix (new session). */
    fun reset() {
        lastAccepted = null
        recent.clear()
    }
}
