package me.tarry.app.segmentation

import me.tarry.app.map.GeoPoint
import me.tarry.segmentation.geo.haversineMeters

/**
 * Visit positions for the radius sheet's map, split the same way [radiusCounts] splits them, so the dots always agree
 * with the counts shown: `stay.size == counts.stay`, `join.size == counts.join`, `leave.size == counts.leave`.
 * [neighbours] are other places' detected visits that stay where they are; [frozen] are this place's edited visits
 * (drawn hollow: they never change). Visits without coordinates have no dot.
 */
data class RadiusDots(
    val stay: List<GeoPoint>,
    val join: List<GeoPoint>,
    val leave: List<GeoPoint>,
    val neighbours: List<GeoPoint>,
    val frozen: List<GeoPoint>,
) {
    /** Dots inside the circle that would be this place's detected visits. */
    val inside: List<GeoPoint> get() = stay + join

    /** Detected dots that are not this place's at this radius. */
    val outside: List<GeoPoint> get() = leave + neighbours
}

/** Pure; mirrors the rules of [radiusCounts] (a neighbour joins when within the radius and closer to this centre than to its own). */
fun radiusDots(input: RadiusPreviewInput, radiusM: Float): RadiusDots {
    val stay = mutableListOf<GeoPoint>()
    val join = mutableListOf<GeoPoint>()
    val leave = mutableListOf<GeoPoint>()
    val neighbours = mutableListOf<GeoPoint>()
    val frozen = mutableListOf<GeoPoint>()
    for (v in input.visits) {
        val lat = v.lat ?: continue
        val lng = v.lng ?: continue
        val p = GeoPoint(lat, lng)
        val d = haversineMeters(lat, lng, input.centre.lat, input.centre.lng)
        if (v.placeId == input.placeId) {
            if (v.frozen) frozen += p else if (d <= radiusM) stay += p else leave += p
        } else if (!v.frozen) {
            val own = input.neighbourCentres[v.placeId]
            val joins = own != null && d <= radiusM && d < haversineMeters(lat, lng, own.lat, own.lng)
            if (joins) join += p else neighbours += p
        }
    }
    return RadiusDots(stay, join, leave, neighbours, frozen)
}
