package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

/** A stay before trip, merge and place steps. [range] runs from its first to its last ping. */
internal data class StayCandidate(
    val range: TimeRange,
    val lat: Double,
    val lng: Double,
    val pings: List<Ping>,
    val bridged: Boolean,
    /** The stay was still growing when the pings ran out. */
    val open: Boolean,
)

/**
 * Stay-point detection (docs/specs/tarry-mvp.md §Segmentation steps 2-3) over time-ordered [pings] from one
 * unfrozen stretch.
 *
 * A candidate grows while each ping is within [SegmentationParams.stayRadiusM] of its running accuracy-weighted
 * centroid, and closes on the first ping clearly outside it. A closed candidate is kept when it spans at least
 * [SegmentationParams.minStayDurationMs]. The candidate still open when the pings run out is always returned
 * (with [StayCandidate.open]), however short: the caller decides whether it is a stay yet and resumes from it.
 *
 * - **Outliers:** a ping outside the radius whose accuracy circle still reaches it (`distance - accuracy <=
 *   stayRadius`) is ambiguous: it neither grows nor closes the candidate (indoor drift, accuracy spikes).
 * - **Bridging:** a silence of up to [SegmentationParams.maxGapToBridgeMs] between two candidate pings is
 *   bridged; one longer than [SegmentationParams.signalGapMs] marks the stay as bridged (lower confidence).
 *   A longer silence closes the candidate even if the next ping is at the same spot (T7 then reports the gap).
 */
internal fun detectStays(pings: List<Ping>, params: SegmentationParams): List<StayCandidate> {
    val stays = ArrayList<StayCandidate>()
    var current: Growing? = null
    for (ping in pings) {
        val c = current
        if (c == null) {
            current = Growing(ping)
            continue
        }
        val distance = haversineMeters(c.lat, c.lng, ping.lat, ping.lng)
        val silence = ping.timeMs - c.last.timeMs
        when {
            silence > params.maxGapToBridgeMs -> {
                c.closeInto(stays, params, open = false)
                current = Growing(ping)
            }
            distance <= params.stayRadiusM -> c.add(ping, bridging = silence > params.signalGapMs)
            distance - ping.accuracyM <= params.stayRadiusM -> Unit // ambiguous outlier
            else -> {
                c.closeInto(stays, params, open = false)
                current = Growing(ping)
            }
        }
    }
    current?.closeInto(stays, params, open = true)
    return stays
}

/** Floor on accuracy for weighting, so one very confident fix cannot dominate the centroid. */
private const val MIN_WEIGHT_ACCURACY_M = 5.0

private fun pingWeight(ping: Ping): Double {
    val acc = maxOf(ping.accuracyM.toDouble(), MIN_WEIGHT_ACCURACY_M)
    return 1.0 / (acc * acc)
}

/** Accuracy-weighted centroid (lat, lng) of non-empty [pings]; the same rule [detectStays] applies incrementally. */
internal fun weightedCentroid(pings: List<Ping>): Pair<Double, Double> {
    var weight = 0.0
    var latSum = 0.0
    var lngSum = 0.0
    for (p in pings) {
        val w = pingWeight(p)
        weight += w
        latSum += p.lat * w
        lngSum += p.lng * w
    }
    return latSum / weight to lngSum / weight
}

private class Growing(first: Ping) {
    private val pings = arrayListOf(first)
    private var weight = 0.0
    private var latSum = 0.0
    private var lngSum = 0.0
    var bridged = false
        private set

    init {
        accumulate(first)
    }

    val last: Ping get() = pings.last()
    val lat: Double get() = latSum / weight
    val lng: Double get() = lngSum / weight

    fun add(ping: Ping, bridging: Boolean) {
        pings += ping
        accumulate(ping)
        if (bridging) bridged = true
    }

    private fun accumulate(ping: Ping) {
        val w = pingWeight(ping)
        weight += w
        latSum += ping.lat * w
        lngSum += ping.lng * w
    }

    fun closeInto(out: MutableList<StayCandidate>, params: SegmentationParams, open: Boolean) {
        val range = TimeRange(pings.first().timeMs, last.timeMs)
        if (!open && range.durationMs < params.minStayDurationMs) return
        out += StayCandidate(range, lat, lng, pings.toList(), bridged, open)
    }
}
