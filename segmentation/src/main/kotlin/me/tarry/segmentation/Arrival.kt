package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

/**
 * Arrival and departure attribution over merged [stays] and the stretch's time-ordered [pings]: travel stays
 * separate from visits, but movement near a stay belongs to the visit.
 *
 * - **Arrival:** the stay's start moves back over the pings within [SegmentationParams.arrivalRadiusM] of the stay
 *   centroid, to the last vehicle fix when there is one (parking and walking in is the visit, not the drive). A
 *   ping beyond the radius stops it, so a longer walk-in stays travel.
 * - **Departure:** in reverse, the stay's end moves forward over the pings that stay within the radius, up to the
 *   first vehicle fix or the first ping that leaves it.
 *
 * Fixes whose accuracy circle still reaches the radius count as inside, like the stay test. Never crosses a silence
 * longer than [SegmentationParams.maxGapToBridgeMs] and never enters a neighbouring stay. The centroid is unchanged.
 */
internal fun attributeArrivals(
    stays: List<StayCandidate>,
    pings: List<Ping>,
    params: SegmentationParams,
): List<StayCandidate> {
    val out = stays.toMutableList()
    for (i in out.indices) {
        val limit = stays.getOrNull(i + 1)?.range?.startMs ?: Long.MAX_VALUE
        out[i] = extendEnd(out[i], stays.getOrNull(i + 1), limit, pings, params)
    }
    for (i in out.indices) {
        val floor = out.getOrNull(i - 1)?.range?.endMs ?: Long.MIN_VALUE
        out[i] = extendStart(out[i], stays.getOrNull(i - 1), floor, pings, params)
    }
    return out
}

/**
 * The extension radius toward [neighbour]: [SegmentationParams.arrivalRadiusM], but at most half the distance between
 * the two centroids, so the walk between nearby places stays a trip. A capped radius is exact (no accuracy slack).
 */
private fun clearlyOutside(p: Ping, stay: StayCandidate, neighbour: StayCandidate?, params: SegmentationParams): Boolean {
    val half = neighbour?.let { haversineMeters(stay.lat, stay.lng, it.lat, it.lng) / 2 } ?: Double.MAX_VALUE
    val d = haversineMeters(stay.lat, stay.lng, p.lat, p.lng)
    return if (half < params.arrivalRadiusM) d > half else d - p.accuracyM > params.arrivalRadiusM
}

private fun extendEnd(stay: StayCandidate, next: StayCandidate?, limitMs: Long, pings: List<Ping>, params: SegmentationParams): StayCandidate {
    var end = stay.range.endMs
    var k = pings.indexOfFirst { it.timeMs > end }
    if (k < 0) return stay
    while (k < pings.size) {
        val p = pings[k]
        if (p.timeMs >= limitMs || p.timeMs - end > params.maxGapToBridgeMs) break
        if (p.activity == Activity.IN_VEHICLE || clearlyOutside(p, stay, next, params)) break
        end = p.timeMs
        k++
    }
    return if (end == stay.range.endMs) stay else stay.copy(range = TimeRange(stay.range.startMs, end))
}

private fun extendStart(stay: StayCandidate, prev: StayCandidate?, floorMs: Long, pings: List<Ping>, params: SegmentationParams): StayCandidate {
    var start = stay.range.startMs
    var k = pings.indexOfLast { it.timeMs < start }
    while (k >= 0) {
        val p = pings[k]
        if (p.timeMs <= floorMs || start - p.timeMs > params.maxGapToBridgeMs) break
        if (clearlyOutside(p, stay, prev, params)) break
        start = p.timeMs
        if (p.activity == Activity.IN_VEHICLE) break // the vehicle stopped here
        k--
    }
    return if (start == stay.range.startMs) stay else stay.copy(range = TimeRange(start, stay.range.endMs))
}
