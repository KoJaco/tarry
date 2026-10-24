package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

/**
 * Merges adjacent stays (docs/specs/phase-2b.md T5) over time-ordered [stays] and the stretch's time-ordered
 * [pings]. A pair (a, b) merges unless [distinctPlaces] says they are different known places, and when either
 * the net displacement between them is smaller than [SegmentationParams.minTripDistanceM], or the gap is shorter than
 * [SegmentationParams.shortTripMergeMs] with centroids within [SegmentationParams.placeMatchRadiusM].
 * A pair separated by a silence longer than [SegmentationParams.maxGapToBridgeMs] never merges (that is a gap).
 * Repeats until no pair qualifies, so chains collapse.
 */
internal fun mergeStays(
    stays: List<StayCandidate>,
    pings: List<Ping>,
    params: SegmentationParams,
    distinctPlaces: (StayCandidate, StayCandidate) -> Boolean = { _, _ -> false },
): List<StayCandidate> {
    val out = ArrayList<StayCandidate>()
    for (next in stays) {
        var current = next
        while (out.isNotEmpty()) {
            val prev = out.last()
            if (!shouldMerge(prev, current, pings, params, distinctPlaces)) break
            out.removeAt(out.lastIndex)
            current = combine(prev, current, pings, params)
        }
        out += current
    }
    return out
}

private fun between(a: StayCandidate, b: StayCandidate, pings: List<Ping>): List<Ping> =
    pings.filter { it.timeMs > a.range.endMs && it.timeMs < b.range.startMs }

private fun shouldMerge(
    a: StayCandidate,
    b: StayCandidate,
    pings: List<Ping>,
    params: SegmentationParams,
    distinctPlaces: (StayCandidate, StayCandidate) -> Boolean,
): Boolean {
    if (distinctPlaces(a, b)) return false
    // A silence too long to bridge is a signal gap (T7 reports it); merging would hide it.
    if (longestSilence(a, b, pings) > params.maxGapToBridgeMs) return false
    // Stays this noisy cannot be told apart below their own noise scale (capped), so that is the least trip.
    val noise = minOf(params.placeMaxRadiusM, params.placeNoiseFactor * maxOf(stayNoiseM(a, params), stayNoiseM(b, params)))
    if (netDisplacement(a, b, pings, params) < maxOf(params.minTripDistanceM, noise)) return true
    val gap = b.range.startMs - a.range.endMs
    return gap < params.shortTripMergeMs &&
        haversineMeters(a.lat, a.lng, b.lat, b.lng) <= maxOf(noiseScaledRadiusM(a, params), noiseScaledRadiusM(b, params))
}

/**
 * The distance the pings between [a] and [b] get from [a]'s centroid (and [b]'s centroid itself): the furthest of the fixes no worse
 * than [SegmentationParams.goodAccuracyM] when there are two or more. Otherwise a robust quantile ([SegmentationParams.robustDisplacementQuantile]) is used, and with no good fix at all, all fixes after dropping the least accurate
 * third. Net displacement, not path length: noisy fixes at one spot add up to kilometres of path.
 */
private fun netDisplacement(a: StayCandidate, b: StayCandidate, pings: List<Ping>, params: SegmentationParams): Double {
    val inside = between(a, b, pings)
    val good = inside.filter { it.accuracyM <= params.goodAccuracyM }
    var used = good
    if (used.isEmpty() && inside.isNotEmpty()) {
        used = inside.sortedBy { it.accuracyM }.take(maxOf(1, inside.size - inside.size / 3))
    }
    val distances = used.map { haversineMeters(a.lat, a.lng, it.lat, it.lng) }
    // Two or more good fixes are trusted, so the furthest one counts (an out-and-back walk must survive); only the
    // poor-fix fallback needs the robust quantile.
    val furthest = if (good.size >= 2) distances.max() else quantile(distances, params.robustDisplacementQuantile)
    return maxOf(furthest, haversineMeters(a.lat, a.lng, b.lat, b.lng))
}

/** Longest stretch with no ping from the end of [a] to the start of [b]. */
private fun longestSilence(a: StayCandidate, b: StayCandidate, pings: List<Ping>): Long {
    var longest = 0L
    var last = a.range.endMs
    for (p in between(a, b, pings)) {
        longest = maxOf(longest, p.timeMs - last)
        last = p.timeMs
    }
    return maxOf(longest, b.range.startMs - last)
}

private fun combine(a: StayCandidate, b: StayCandidate, pings: List<Ping>, params: SegmentationParams): StayCandidate {
    val inside = between(a, b, pings).filter {
        haversineMeters(it.lat, it.lng, a.lat, a.lng) <= params.stayRadiusM ||
            haversineMeters(it.lat, it.lng, b.lat, b.lng) <= params.stayRadiusM
    }
    val all = (a.pings + inside + b.pings).sortedBy { it.timeMs }
    val (lat, lng) = weightedCentroid(all, params)
    return StayCandidate(
        range = TimeRange(a.range.startMs, b.range.endMs),
        lat = lat,
        lng = lng,
        pings = all,
        bridged = a.bridged || b.bridged || longestSilence(a, b, pings) > params.signalGapMs,
        open = b.open,
    )
}
