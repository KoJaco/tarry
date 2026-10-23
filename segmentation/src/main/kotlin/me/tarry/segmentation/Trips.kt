package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

/** A trip before place and gap steps; [fromStay]/[toStay] index the merged stay list, null at a run edge. */
internal data class TripCandidate(
    val range: TimeRange,
    val fromStay: Int?,
    val toStay: Int?,
    val distanceM: Double,
    val mode: TripMode,
)

/**
 * Builds trips between time-ordered [stays] (as returned by [mergeStays]) from the stretch's time-ordered [pings]
 * (docs/specs/phase-2b.md T4). Leading and trailing pings outside any stay become edge trips; with no stays,
 * two or more pings form one trip with both ends null.
 *
 * An edge trip whose net displacement from its stay is under [SegmentationParams.minTripDistanceM] is jitter, not a
 * trip, whatever the activity says. A trip into a stay whose vehicle part ends further than
 * [SegmentationParams.arrivalRadiusM] from it, followed by on foot movement, splits into a VEHICLE and a WALK leg
 * (the first leg keeps [TripCandidate.fromStay], the second keeps [TripCandidate.toStay]).
 */
internal fun buildTrips(
    stays: List<StayCandidate>,
    pings: List<Ping>,
    params: SegmentationParams = SegmentationParams(),
): List<TripCandidate> {
    val trips = ArrayList<TripCandidate>()
    if (stays.isEmpty()) {
        val trusted = pings.filter { it.accuracyM <= params.goodAccuracyM }.ifEmpty { pings }
        val first = trusted.firstOrNull()
        val furthest = first?.let { f -> trusted.maxOf { haversineMeters(f.lat, f.lng, it.lat, it.lng) } } ?: 0.0
        if (pings.size >= 2 && furthest >= params.minTripDistanceM) {
            trips += trip(pings.first().timeMs, pings.last().timeMs, null, null, pings, null, null, params, pings)
        }
        return trips
    }

    val first = stays.first()
    val leading = pings.filter { it.timeMs < first.range.startMs }
    if (leading.isNotEmpty()) {
        trips += trip(leading.first().timeMs, first.range.startMs, null, 0, leading, null, first, params, pings)
            .filter { it.distanceM >= params.minTripDistanceM }
    }
    for (i in 0 until stays.lastIndex) {
        val a = stays[i]
        val b = stays[i + 1]
        val inside = pings.filter { it.timeMs > a.range.endMs && it.timeMs < b.range.startMs }
        trips += trip(a.range.endMs, b.range.startMs, i, i + 1, inside, a, b, params, pings)
    }
    val last = stays.last()
    val trailing = pings.filter { it.timeMs > last.range.endMs }
    if (trailing.isNotEmpty()) {
        trips += trip(last.range.endMs, trailing.last().timeMs, stays.lastIndex, null, trailing, last, null, params, pings)
            .filter { it.distanceM >= params.minTripDistanceM }
    }
    return trips
}

/** A point on a trip path; stay centroids have accuracy 0. */
internal data class PathPoint(val lat: Double, val lng: Double, val accuracyM: Float, val timeMs: Long)

internal fun Ping.toPathPoint() = PathPoint(lat, lng, accuracyM, timeMs)

/**
 * Where a trip meets [stay]: the stay's own edge ping (its end when [atEnd], else its start) from [pings], so a stay
 * whose edge was moved by arrival attribution is not measured to its far centroid; the centroid when no ping is there.
 */
internal fun stayEdge(stay: StayCandidate, atEnd: Boolean, pings: List<Ping>): PathPoint {
    val at = if (atEnd) stay.range.endMs else stay.range.startMs
    return pings.firstOrNull { it.timeMs == at }?.toPathPoint() ?: PathPoint(stay.lat, stay.lng, 0f, at)
}

/**
 * Length of the path through [points]. Interior fixes worse than [SegmentationParams.goodAccuracyM] are skipped when
 * better ones exist (the first and last point always stay). A hop then counts when it is longer than the two fixes'
 * combined accuracy, or, at [SegmentationParams.movingSpeedMps] or faster (so a curve is not cut into chords), when it
 * is longer than the worse of the two accuracies; and only where the path is going somewhere
 * ([SegmentationParams.movingMinEfficiency] over a window of fixes around it), so jitter at one spot never counts.
 * A skipped hop keeps the anchor: the next counted hop is measured from the last counted point, and whatever remains
 * to the final point is added as a chord, so the distance carries over instead of being lost.
 */
internal fun pathDistance(points: List<PathPoint>, params: SegmentationParams): Double {
    if (points.size < 2) return 0.0
    val good = points.subList(1, points.lastIndex).filter { it.accuracyM <= params.goodAccuracyM }
    val used = if (good.isEmpty()) points else listOf(points.first()) + good + points.last()
    fun between(a: PathPoint, b: PathPoint) = haversineMeters(a.lat, a.lng, b.lat, b.lng)
    fun goingSomewhere(i: Int): Boolean {
        val window = used.subList(maxOf(0, i - 3), minOf(used.size, i + 3))
        val path = window.zipWithNext().sumOf { (a, b) -> between(a, b) }
        return path <= 0.0 || between(window.first(), window.last()) / path >= params.movingMinEfficiency
    }
    var distance = 0.0
    var anchor = used.first()
    for (i in 1 until used.size) {
        val p = used[i]
        val hop = between(anchor, p)
        val seconds = (p.timeMs - anchor.timeMs) / 1000.0
        val moving = seconds > 0 && hop / seconds >= params.movingSpeedMps && hop > maxOf(anchor.accuracyM, p.accuracyM)
        if ((moving || hop > anchor.accuracyM + p.accuracyM) && goingSomewhere(i)) {
            distance += hop
            anchor = p
        }
    }
    if (anchor !== used.last()) distance += between(anchor, used.last())
    return distance
}

private fun trip(
    startMs: Long,
    endMs: Long,
    from: Int?,
    to: Int?,
    inside: List<Ping>,
    fromStay: StayCandidate?,
    toStay: StayCandidate?,
    params: SegmentationParams,
    all: List<Ping>,
): List<TripCandidate> {
    fun fromEdge() = fromStay?.let { stayEdge(it, atEnd = true, pings = all) }
    fun toEdge() = toStay?.let { stayEdge(it, atEnd = false, pings = all) }

    // An edge trip that never gets away from its stay is jitter.
    val anchor = fromStay ?: toStay
    if ((fromStay == null) != (toStay == null) && anchor != null) {
        val trusted = inside.filter { it.accuracyM <= params.goodAccuracyM }.ifEmpty { inside }
        // Robust (not the single furthest fix) and no smaller than the stay's own noise scale.
        val away = quantile(trusted.map { haversineMeters(anchor.lat, anchor.lng, it.lat, it.lng) }, params.robustDisplacementQuantile)
        val noise = minOf(params.placeMaxRadiusM, params.placeNoiseFactor * stayNoiseM(anchor, params))
        if (away < maxOf(params.minTripDistanceM, noise)) return emptyList()
    }

    // The vehicle part ends far from the stay, then on foot: two legs.
    val lastVehicle = inside.indexOfLast { it.activity == Activity.IN_VEHICLE }
    // Only when the tail really is walking: mostly WALKING fixes at a walking pace. Activity recognition lags, so a
    // car still rolling after IN_VEHICLE ends stays part of the VEHICLE trip.
    if (toStay != null && lastVehicle >= 0 && lastVehicle < inside.lastIndex &&
        inside.count { it.activity == Activity.IN_VEHICLE } >= 2 &&
        haversineMeters(inside[lastVehicle].lat, inside[lastVehicle].lng, toStay.lat, toStay.lng) > params.arrivalRadiusM
    ) {
        val stop = inside[lastVehicle]
        val tail = inside.subList(lastVehicle + 1, inside.size)
        val tailSeconds = (toStay.range.startMs - stop.timeMs) / 1000.0
        val tailSpeed = haversineMeters(stop.lat, stop.lng, toStay.lat, toStay.lng) / maxOf(tailSeconds, 1.0)
        val walking = tail.count { it.activity == Activity.WALKING } * 2 > tail.size && tailSpeed <= params.walkMaxSpeedMps
        if (!walking) {
            val path = listOfNotNull(fromEdge()) + inside.map { it.toPathPoint() } + listOfNotNull(toEdge())
            return listOf(TripCandidate(TimeRange(startMs, endMs), from, to, pathDistance(path, params), TripMode.VEHICLE))
        }
        val driven = inside.subList(0, lastVehicle + 1)
        val walked = inside.subList(lastVehicle + 1, inside.size)
        val drive = listOfNotNull(fromEdge()) + driven.map { it.toPathPoint() }
        val walk = listOf(stop.toPathPoint()) + walked.map { it.toPathPoint() } + listOfNotNull(toEdge())
        return listOf(
            TripCandidate(TimeRange(startMs, stop.timeMs), from, null, pathDistance(drive, params), TripMode.VEHICLE),
            TripCandidate(TimeRange(stop.timeMs, endMs), null, to, pathDistance(walk, params), TripMode.WALK),
        )
    }

    val path = listOfNotNull(fromEdge()) + inside.map { it.toPathPoint() } + listOfNotNull(toEdge())
    return listOf(TripCandidate(TimeRange(startMs, endMs), from, to, pathDistance(path, params), dominantMode(inside)))
}

private fun dominantMode(pings: List<Ping>): TripMode {
    val vehicle = pings.count { it.activity == Activity.IN_VEHICLE }
    val walk = pings.count { it.activity == Activity.WALKING || it.activity == Activity.ON_BICYCLE }
    return when {
        vehicle == 0 && walk == 0 -> TripMode.UNKNOWN
        vehicle >= walk -> TripMode.VEHICLE
        else -> TripMode.WALK
    }
}
