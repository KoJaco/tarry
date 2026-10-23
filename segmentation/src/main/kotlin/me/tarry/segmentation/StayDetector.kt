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
 * centroid, and closes once the pings clearly outside it are sustained: at least
 * [SegmentationParams.departureMinFixes] of them spanning [SegmentationParams.departureMinMs], with no ping inside the
 * radius between them, and coherent: network-grade closing fixes (worse than [SegmentationParams.goodAccuracyM]) must
 * agree with each other within their combined accuracy or move steadily away from the centroid, while GPS-grade ones
 * are trusted. A shorter or incoherent excursion is an outlier and ignored. A closed candidate is kept when it spans at least
 * [SegmentationParams.minStayDurationMs]. The candidate still open when the pings run out is always returned
 * (with [StayCandidate.open]), however short: the caller decides whether it is a stay yet and resumes from it.
 *
 * - **Outliers:** a ping outside the radius whose accuracy circle still reaches it (`distance - accuracy <=
 *   stayRadius`) is ambiguous: it neither grows nor closes the candidate, and does not interrupt a departure (indoor
 *   drift, accuracy spikes, a slow boat).
 * - **Bridging:** a silence of up to [SegmentationParams.maxGapToBridgeMs] between two candidate pings is
 *   bridged; one longer than [SegmentationParams.signalGapMs] marks the stay as bridged (lower confidence).
 *   A longer silence closes the candidate even if the next ping is at the same spot (T7 then reports the gap).
 */
internal fun detectStays(pings: List<Ping>, params: SegmentationParams): List<StayCandidate> {
    val stays = ArrayList<StayCandidate>()
    var current: Growing? = null
    // Indices of the clearly-outside pings of the current excursion (pings that may close the stay), oldest first.
    val outside = ArrayList<Int>()
    var i = 0
    while (i < pings.size) {
        val ping = pings[i]
        val c = current
        if (c == null) {
            current = Growing(ping, params)
            outside.clear()
            i++
            continue
        }
        val distance = haversineMeters(c.lat, c.lng, ping.lat, ping.lng)
        val silence = ping.timeMs - c.last.timeMs
        when {
            silence > params.maxGapToBridgeMs || ping.timeMs - pings[i - 1].timeMs > params.maxGapToBridgeMs -> {
                c.closeInto(stays, params, open = false)
                current = Growing(ping, params)
                outside.clear()
            }
            distance <= params.stayRadiusM -> {
                c.add(ping, bridging = silence > params.signalGapMs)
                outside.clear()
            }
            distance - ping.accuracyM <= params.stayRadiusM -> Unit
            // Ambiguous: neither grows nor closes the stay, and does not interrupt a departure (a slow boat's
            // fixes keep being ambiguous without it being home). The closing fixes' coherence guards against noise.
            else -> {
                outside += i
                // Fixes before the coherent run disagree with it: noise, not part of the departure.
                val run = coherentRun(outside, pings, c.lat, c.lng, params)
                if (run.size < outside.size) {
                    val keep = ArrayList(run)
                    outside.clear()
                    outside.addAll(keep)
                }
                val sustained = outside.size >= params.departureMinFixes &&
                    ping.timeMs - pings[outside.first()].timeMs >= params.departureMinMs
                if (sustained) {
                    // A real departure: the stay ends before the excursion, which is re-read as what comes next.
                    c.closeInto(stays, params, open = false)
                    current = null
                    i = outside.first()
                    outside.clear()
                    continue
                }
            }
        }
        i++
    }
    current?.closeInto(stays, params, open = true)
    return settleStarts(stays, pings, params)
}

/** The [q] quantile (0..1) of [values]; the upper element at exact ranks. Empty gives 0. */
internal fun quantile(values: List<Double>, q: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    return sorted[minOf(sorted.lastIndex, (q * sorted.size).toInt())]
}

/**
 * How noisy a stay's fixes are: the larger of the robust spread of its fixes around the centroid (the
 * [SegmentationParams.robustDisplacementQuantile] distance) and their median accuracy when that is worse than
 * [SegmentationParams.goodAccuracyM] (good accuracy is not noise). Clean GPS gives a few metres,
 * a network-only boat day gives 80 m or more.
 */
internal fun stayNoiseM(stay: StayCandidate, params: SegmentationParams): Double {
    if (stay.pings.isEmpty()) return 0.0
    val spread = quantile(
        stay.pings.map { haversineMeters(stay.lat, stay.lng, it.lat, it.lng) },
        params.robustDisplacementQuantile,
    )
    val accuracy = quantile(stay.pings.map { it.accuracyM.toDouble() }, 0.5)
    return maxOf(spread, if (accuracy > params.goodAccuracyM) accuracy else 0.0)
}

/** Radius a stay's place gets and matches new places with: [SegmentationParams.placeMatchRadiusM] up to the cap. */
internal fun noiseScaledRadiusM(stay: StayCandidate, params: SegmentationParams): Double =
    minOf(params.placeMaxRadiusM, maxOf(params.placeMatchRadiusM, params.placeNoiseFactor * stayNoiseM(stay, params)))

/** Two fixes agree when they are no further apart than their combined accuracy. */
private fun agree(a: Ping, b: Ping): Boolean =
    haversineMeters(a.lat, a.lng, b.lat, b.lng) <= a.accuracyM + b.accuracyM

/** Whether the fixes at [run] (oldest first) are one coherent departure from the centroid ([lat], [lng]). */
private fun isCoherent(run: List<Int>, pings: List<Ping>, lat: Double, lng: Double, params: SegmentationParams): Boolean {
    // GPS-grade fixes are trusted as they are (a real loop comes back the way it went); only poor ones must cohere.
    val poor = run.filter { pings[it].accuracyM > params.goodAccuracyM }
    if (poor.size < 2) return true
    var allAgree = true
    for (a in poor.indices) for (b in a + 1 until poor.size) {
        if (!agree(pings[poor[a]], pings[poor[b]])) allAgree = false
    }
    return allAgree || progressing(poor, pings, lat, lng, params)
}

/**
 * Whether the fixes at [run] move steadily away from the centroid: no fix falls back by more than the pair's
 * combined accuracy, and the last is at least [SegmentationParams.departureProgressM] further than the first.
 */
private fun progressing(run: List<Int>, pings: List<Ping>, lat: Double, lng: Double, params: SegmentationParams): Boolean {
    if (run.size < 2) return false
    fun d(i: Int) = haversineMeters(lat, lng, pings[i].lat, pings[i].lng)
    for (k in 1 until run.size) {
        if (d(run[k]) < d(run[k - 1]) - pings[run[k]].accuracyM - pings[run[k - 1]].accuracyM) return false
    }
    return d(run.last()) >= d(run.first()) + params.departureProgressM
}

/** The longest suffix of [outside] that is a coherent departure (see [isCoherent]). */
private fun coherentRun(outside: List<Int>, pings: List<Ping>, lat: Double, lng: Double, params: SegmentationParams): List<Int> {
    var start = outside.size
    while (start > 0 && isCoherent(outside.subList(start - 1, outside.size), pings, lat, lng, params)) start--
    return outside.subList(start, outside.size).toList()
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
