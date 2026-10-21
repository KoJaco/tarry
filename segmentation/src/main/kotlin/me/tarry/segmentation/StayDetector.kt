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
            current = Growing(ping, params)
            continue
        }
        val distance = haversineMeters(c.lat, c.lng, ping.lat, ping.lng)
        val silence = ping.timeMs - c.last.timeMs
        when {
            silence > params.maxGapToBridgeMs -> {
                c.closeInto(stays, params, open = false)
                current = Growing(ping, params)
            }
            distance <= params.stayRadiusM -> c.add(ping, bridging = silence > params.signalGapMs)
            distance - ping.accuracyM <= params.stayRadiusM -> Unit // ambiguous outlier
            else -> {
                c.closeInto(stays, params, open = false)
                current = Growing(ping, params)
            }
        }
    }
    current?.closeInto(stays, params, open = true)
    return settleStarts(stays, pings, params)
}

private fun pingWeight(ping: Ping, params: SegmentationParams): Double {
    val acc = maxOf(ping.accuracyM.toDouble(), params.minWeightAccuracyM)
    return 1.0 / (acc * acc)
}

/** Accuracy-weighted centroid (lat, lng) of non-empty [pings]; the same rule [detectStays] applies incrementally. */
internal fun weightedCentroid(pings: List<Ping>, params: SegmentationParams): Pair<Double, Double> {
    var weight = 0.0
    var latSum = 0.0
    var lngSum = 0.0
    for (p in pings) {
        val w = pingWeight(p, params)
        weight += w
        latSum += p.lat * w
        lngSum += p.lng * w
    }
    return latSum / weight to lngSum / weight
}

private class Growing(first: Ping, private val params: SegmentationParams) {
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
        val w = pingWeight(ping, params)
        weight += w
        latSum += ping.lat * w
        lngSum += ping.lng * w
    }

    /** Adds this candidate to [out] when it qualifies; returns whether it did. */
    fun closeInto(out: MutableList<StayCandidate>, params: SegmentationParams, open: Boolean): Boolean {
        val range = TimeRange(pings.first().timeMs, last.timeMs)
        if (!open && range.durationMs < params.minStayDurationMs) return false
        out += StayCandidate(range, lat, lng, pings.toList(), bridged, open)
        return true
    }

}

/** Bound on start-settling rounds; each round only moves the start, so this is reached only by oscillation. */
private const val MAX_SETTLE_ROUNDS = 10

/**
 * Settles each stay's start on the stay itself instead of on where greedy growth happened to begin. Growth that
 * starts on an approach ping drags the centroid toward the approach and swallows pings that are not at the stay,
 * and which ping it starts on depends on where the run's pings begin, so an overlap re-run would disagree with a
 * full run. Each round drops leading pings beyond [SegmentationParams.stayRadiusM] of the centroid, then pulls the
 * start back over the immediately preceding pings within it (never into the previous stay, never across a silence
 * longer than [SegmentationParams.maxGapToBridgeMs]), recomputing the centroid, until nothing changes. A closed
 * stay trimmed below [SegmentationParams.minStayDurationMs] is dropped.
 */
private fun settleStarts(stays: List<StayCandidate>, pings: List<Ping>, params: SegmentationParams): List<StayCandidate> {
    val out = ArrayList<StayCandidate>(stays.size)
    for (stay in stays) {
        val floor = out.lastOrNull()?.range?.endMs ?: Long.MIN_VALUE
        var own = stay.pings
        repeat(MAX_SETTLE_ROUNDS) {
            val before = own.first().timeMs
            // A leading ping is judged against the centroid of the pings after it (never one it pulled toward
            // itself), the same test the extension below applies, so there is one answer rather than two.
            while (own.size > 1) {
                val (rLat, rLng) = weightedCentroid(own.subList(1, own.size), params)
                if (haversineMeters(own[0].lat, own[0].lng, rLat, rLng) <= params.stayRadiusM) break
                own = own.drop(1)
            }
            var i = pings.indexOfFirst { it.timeMs >= own.first().timeMs }
            var next = own.first()
            while (i > 0) {
                val p = pings[i - 1]
                if (p.timeMs <= floor || next.timeMs - p.timeMs > params.maxGapToBridgeMs) break
                val (cLat, cLng) = weightedCentroid(own, params)
                if (haversineMeters(p.lat, p.lng, cLat, cLng) > params.stayRadiusM) break
                own = listOf(p) + own
                next = p
                i--
            }
            if (own.first().timeMs == before) return@repeat // settled
        }
        val range = TimeRange(own.first().timeMs, stay.range.endMs)
        if (!stay.open && range.durationMs < params.minStayDurationMs) continue
        // The interior set also depends on growth order (which outliers were skipped). Rebuild it from every ping in
        // the range within the radius of the centroid, twice, so the centroid is a property of the range alone.
        repeat(2) {
            val (cLat, cLng) = weightedCentroid(own, params)
            val rebuilt = pings.filter {
                it.timeMs >= range.startMs && it.timeMs <= range.endMs &&
                    haversineMeters(it.lat, it.lng, cLat, cLng) <= params.stayRadiusM
            }
            if (rebuilt.isNotEmpty()) own = rebuilt
        }
        val (lat, lng) = weightedCentroid(own, params)
        out += stay.copy(
            range = range,
            lat = lat,
            lng = lng,
            pings = own,
            bridged = own.zipWithNext().any { (a, b) -> b.timeMs - a.timeMs > params.signalGapMs },
        )
    }
    return out
}
