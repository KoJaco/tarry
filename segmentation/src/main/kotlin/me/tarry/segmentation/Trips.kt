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
 */
internal fun buildTrips(stays: List<StayCandidate>, pings: List<Ping>): List<TripCandidate> {
    val trips = ArrayList<TripCandidate>()
    if (stays.isEmpty()) {
        if (pings.size >= 2) {
            trips += trip(pings.first().timeMs, pings.last().timeMs, null, null, pings, null, null)
        }
        return trips
    }

    val first = stays.first()
    val leading = pings.filter { it.timeMs < first.range.startMs }
    if (leading.isNotEmpty()) {
        trips += trip(leading.first().timeMs, first.range.startMs, null, 0, leading, null, first)
    }
    for (i in 0 until stays.lastIndex) {
        val a = stays[i]
        val b = stays[i + 1]
        val inside = pings.filter { it.timeMs > a.range.endMs && it.timeMs < b.range.startMs }
        trips += trip(a.range.endMs, b.range.startMs, i, i + 1, inside, a, b)
    }
    val last = stays.last()
    val trailing = pings.filter { it.timeMs > last.range.endMs }
    if (trailing.isNotEmpty()) {
        trips += trip(last.range.endMs, trailing.last().timeMs, stays.lastIndex, null, trailing, last, null)
    }
    return trips
}

private fun trip(
    startMs: Long,
    endMs: Long,
    from: Int?,
    to: Int?,
    inside: List<Ping>,
    fromStay: StayCandidate?,
    toStay: StayCandidate?,
): TripCandidate {
    var distance = 0.0
    var prevLat = fromStay?.lat
    var prevLng = fromStay?.lng
    fun step(lat: Double, lng: Double) {
        val pl = prevLat
        val pg = prevLng
        if (pl != null && pg != null) distance += haversineMeters(pl, pg, lat, lng)
        prevLat = lat
        prevLng = lng
    }
    inside.forEach { step(it.lat, it.lng) }
    if (toStay != null) step(toStay.lat, toStay.lng)
    return TripCandidate(TimeRange(startMs, endMs), from, to, distance, dominantMode(inside))
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
