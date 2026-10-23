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
 * Only pottering is absorbed, or the walk-in/walk-out of a vehicle trip: on a trip with no vehicle fix, extension
 * stops where a sustained directed walk begins ([directedFrom]), so a real walk between places keeps its duration.
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
        val vehicle = vehicleBetween(stays.getOrNull(i), stays.getOrNull(i + 1), pings)
        out[i] = extendEnd(out[i], stays.getOrNull(i + 1), limit, pings, params, vehicle)
    }
    for (i in out.indices) {
        val floor = out.getOrNull(i - 1)?.range?.endMs ?: Long.MIN_VALUE
        val vehicle = vehicleBetween(stays.getOrNull(i - 1), stays.getOrNull(i), pings)
        out[i] = extendStart(out[i], stays.getOrNull(i - 1), floor, pings, params, vehicle)
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

/** Whether any vehicle fix lies between [a] and [b] (null is the run edge): the trip there is a vehicle trip. */
private fun vehicleBetween(a: StayCandidate?, b: StayCandidate?, pings: List<Ping>): Boolean {
    val from = a?.range?.endMs ?: Long.MIN_VALUE
    val to = b?.range?.startMs ?: Long.MAX_VALUE
    return pings.any { it.timeMs > from && it.timeMs < to && it.activity == Activity.IN_VEHICLE }
}

/**
 * Whether the fixes from index [k] over the next [SegmentationParams.directedWindowMs] (backward when [forward] is
 * false) are a sustained, directed walk away from the [stay] centroid (towards it, read backward): all GPS-grade,
 * covering at least [SegmentationParams.directedMinSpeedMps] over the window with a path efficiency of at least
 * [SegmentationParams.directedMinEfficiency], and ending clearly further from the centroid than they start.
 * Pottering (slow, wandering, or poor fixes) never passes.
 */
private fun directedFrom(pings: List<Ping>, k: Int, forward: Boolean, stay: StayCandidate, params: SegmentationParams): Boolean {
    val p = pings[k]
    var j = k
    val step = if (forward) 1 else -1
    while (j in pings.indices && Math.abs(pings[j].timeMs - p.timeMs) < params.directedWindowMs) j += step
    if (j !in pings.indices || Math.abs(pings[j].timeMs - p.timeMs) > 2 * params.directedWindowMs) return false
    val window = if (forward) pings.subList(k, j + 1) else pings.subList(j, k + 1)
    if (window.any { it.accuracyM > params.goodAccuracyM }) return false
    val seconds = (window.last().timeMs - window.first().timeMs) / 1000.0
    val path = window.zipWithNext().sumOf { (a, b) -> haversineMeters(a.lat, a.lng, b.lat, b.lng) }
    val net = haversineMeters(window.first().lat, window.first().lng, window.last().lat, window.last().lng)
    if (path <= 0.0 || net < params.directedMinSpeedMps * seconds || net / path < params.directedMinEfficiency) return false
    val dNear = haversineMeters(stay.lat, stay.lng, p.lat, p.lng)
    val dFar = haversineMeters(stay.lat, stay.lng, pings[j].lat, pings[j].lng)
    return dFar - dNear >= 0.5 * net
}

private fun extendEnd(stay: StayCandidate, next: StayCandidate?, limitMs: Long, pings: List<Ping>, params: SegmentationParams, vehicleTrip: Boolean): StayCandidate {
    var end = stay.range.endMs
    if (!vehicleTrip) {
        // A directed walk that began inside the stay's own radius belongs to the trip: retract the end to where it began.
        var i = pings.indexOfLast { it.timeMs <= end }
        while (i >= 1 && pings[i - 1].timeMs > stay.range.startMs && directedFrom(pings, i, forward = true, stay = stay, params = params)) i--
        if (i >= 0) end = minOf(end, pings[i].timeMs)
    }
    var k = pings.indexOfFirst { it.timeMs > end }
    if (k < 0) return if (end == stay.range.endMs) stay else stay.copy(range = TimeRange(stay.range.startMs, end))
    while (k < pings.size) {
        val p = pings[k]
        if (p.timeMs >= limitMs || p.timeMs - end > params.maxGapToBridgeMs) break
        if (p.activity == Activity.IN_VEHICLE || clearlyOutside(p, stay, next, params)) break
        if (!vehicleTrip && directedFrom(pings, k, forward = true, stay = stay, params = params)) break
        end = p.timeMs
        k++
    }
    return if (end == stay.range.endMs) stay else stay.copy(range = TimeRange(stay.range.startMs, end))
}

private fun extendStart(stay: StayCandidate, prev: StayCandidate?, floorMs: Long, pings: List<Ping>, params: SegmentationParams, vehicleTrip: Boolean): StayCandidate {
    var start = stay.range.startMs
    if (!vehicleTrip) {
        // The directed walk-in that already ran inside the stay's radius belongs to the trip: advance the start past it.
        var i = pings.indexOfFirst { it.timeMs >= start }
        while (i in 0 until pings.lastIndex && pings[i + 1].timeMs < stay.range.endMs && directedFrom(pings, i, forward = false, stay = stay, params = params)) i++
        if (i >= 0) start = maxOf(start, pings[i].timeMs)
    }
    var k = pings.indexOfLast { it.timeMs < start }
    if (k >= 0 && start != stay.range.startMs && pings[k].timeMs >= stay.range.startMs) k = -1 // retracted: no extension
    while (k >= 0) {
        val p = pings[k]
        if (p.timeMs <= floorMs || start - p.timeMs > params.maxGapToBridgeMs) break
        if (clearlyOutside(p, stay, prev, params)) break
        if (!vehicleTrip && directedFrom(pings, k, forward = false, stay = stay, params = params)) break
        start = p.timeMs
        if (p.activity == Activity.IN_VEHICLE) break // the vehicle stopped here
        k--
    }
    return if (start == stay.range.startMs) stay else stay.copy(range = TimeRange(start, stay.range.endMs))
}
