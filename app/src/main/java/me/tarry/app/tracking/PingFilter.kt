package me.tarry.app.tracking

/** Outcome of [PingFilter.offer]. */
enum class FilterDecision(val accepted: Boolean) {
    ACCEPT(true),
    DROP_ACCURACY(false),
    DROP_SPEED(false),

    /** Same position as a recently offered fix with no better accuracy: a re-emitted cached position, not a new reading. */
    DROP_STALE(false),
}

/**
 * Capture-time ping filter. Stateful over the last accepted fix; not thread-safe.
 *
 * Capture keeps every fix that passes two caps; segmentation decides what is noise. Rules, in order:
 * 1. accuracy worse than [TrackingConfig.maxAccuracyM] is dropped;
 * 2. a fix implying more than [TrackingConfig.maxSpeedMps] from the comparison fix is dropped
 *    (a fix not newer than the last accepted one is also dropped, as DROP_SPEED, since no speed can be implied;
 *    also by `elapsedRealtimeNanos` when both fixes carry it);
 * 2b. a fix at the accuracy limit ([TrackingConfig.maxAccuracyM]) with exactly the lat/lng of one of the last
 *    [TrackingConfig.staleRepeatMemory] offered fixes is dropped as DROP_STALE. A repeat with better accuracy is a real
 *    reading (a still phone on a steady Wi-Fi position) and is kept.
 *    Dogfood showed the fused provider re-emitting its last known position
 *    with a fresh `Location.time` and accuracy inflated to the cap, so a time check alone cannot catch these.
 *
 * The comparison fix is normally the last accepted one. After a spike (an accepted fix more than
 * [TrackingConfig.spikeMinM] from the fix before it) a fix that returns within [TrackingConfig.spikeReturnM] of that
 * earlier fix is compared with the earlier fix instead, so the spike neither rejects the return on speed nor stays the
 * anchor. The spike itself was already kept.
 */
class PingFilter(private val config: TrackingConfig) {
    var lastAccepted: Fix? = null
        private set
    private var beforeLast: Fix? = null
    private val recent = ArrayDeque<Fix>()

    fun offer(fix: Fix): FilterDecision {
        if (fix.accuracyM > config.maxAccuracyM) return FilterDecision.DROP_ACCURACY
        val last = lastAccepted
        if (last != null) {
            val dtMs = fix.timeMs - last.timeMs
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
            val before = beforeLast
            val returnedFromSpike = before != null &&
                last.distanceTo(before) > config.spikeMinM &&
                fix.distanceTo(before) <= config.spikeReturnM
            val anchor = if (returnedFromSpike) before!! else last
            val dtMs = fix.timeMs - anchor.timeMs
            if (fix.distanceTo(anchor) / (dtMs / 1000.0) > config.maxSpeedMps) return FilterDecision.DROP_SPEED
            beforeLast = anchor
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
        beforeLast = null
        recent.clear()
    }
}
