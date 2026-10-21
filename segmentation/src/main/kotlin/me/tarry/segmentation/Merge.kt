package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

/**
 * Merges adjacent stays (docs/specs/phase-2b.md T5) over time-ordered [stays] and the stretch's time-ordered
 * [pings]. A pair (a, b) merges unless [distinctPlaces] says they are different known places, and when either
 * the path between them is shorter than [SegmentationParams.minTripDistanceM], or the gap is shorter than
 * [SegmentationParams.shortTripMergeMs] with centroids within [SegmentationParams.placeMatchRadiusM].
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
    var path = 0.0
    var lat = a.lat
    var lng = a.lng
    for (p in between(a, b, pings)) {
        path += haversineMeters(lat, lng, p.lat, p.lng)
        lat = p.lat
        lng = p.lng
    }
    path += haversineMeters(lat, lng, b.lat, b.lng)
    if (path < params.minTripDistanceM) return true
    val gap = b.range.startMs - a.range.endMs
    return gap < params.shortTripMergeMs &&
        haversineMeters(a.lat, a.lng, b.lat, b.lng) <= params.placeMatchRadiusM
}

private fun combine(a: StayCandidate, b: StayCandidate, pings: List<Ping>, params: SegmentationParams): StayCandidate {
    val inside = between(a, b, pings).filter {
        haversineMeters(it.lat, it.lng, a.lat, a.lng) <= params.stayRadiusM ||
            haversineMeters(it.lat, it.lng, b.lat, b.lng) <= params.stayRadiusM
    }
    val all = (a.pings + inside + b.pings).sortedBy { it.timeMs }
    val (lat, lng) = weightedCentroid(all)
    return StayCandidate(
        range = TimeRange(a.range.startMs, b.range.endMs),
        lat = lat,
        lng = lng,
        pings = all,
        bridged = a.bridged || b.bridged,
        open = b.open,
    )
}
