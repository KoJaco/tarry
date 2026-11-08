package me.tarry.app.map

import me.tarry.segmentation.geo.haversineMeters

/** A visit for the map. [position] is its place centroid; [radiusM] its place radius (drawn as the visit's area). */
data class PinInput(
    val visitId: String,
    val start: Long,
    val position: GeoPoint?,
    val jobColorArgb: Int?,
    val jobId: String?,
    val end: Long = start,
    val radiusM: Float? = null,
)

data class VisitPin(
    val visitId: String,
    val number: Int,
    val position: GeoPoint,
    val jobColorArgb: Int?,
    val jobId: String?,
    val radiusM: Float? = null,
    val start: Long = 0,
    val end: Long = 0,
)

/** Numbers every visit 1..n in start order (ties by id) but emits pins only for visits that have a position. */
fun visitPins(visits: List<PinInput>): List<VisitPin> =
    visits.sortedWith(compareBy({ it.start }, { it.visitId }))
        .mapIndexedNotNull { i, v ->
            v.position?.let { VisitPin(v.visitId, i + 1, it, v.jobColorArgb, v.jobId, v.radiusM, v.start, v.end) }
        }

/** Smallest box containing [points]; null when empty. */
fun boundsOf(points: Iterable<GeoPoint>): GeoBounds? {
    var south = Double.POSITIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    var any = false
    for (p in points) {
        any = true
        if (p.lat < south) south = p.lat
        if (p.lat > north) north = p.lat
        if (p.lng < west) west = p.lng
        if (p.lng > east) east = p.lng
    }
    return if (any) GeoBounds(south, west, north, east) else null
}

/** Place ids within [withinM] of [pin] with their distance in metres, nearest first. */
fun placesNear(
    pin: GeoPoint,
    places: List<Pair<String, GeoPoint>>,
    withinM: Double = MapConfig().pinSnapM,
): List<Pair<String, Double>> =
    places.map { (id, p) -> id to haversineMeters(pin.lat, pin.lng, p.lat, p.lng) }
        .filter { it.second <= withinM }
        .sortedBy { it.second }

/** One drawn marker: the visits (in number order) whose pins share a spot. Takes the first visit's id and colours. */
data class PinGroup(val first: VisitPin, val numbers: List<Int>) {
    /** "6" for a lone visit, "6, 7" for visits at the same spot. */
    val label: String get() = numbers.joinToString(", ")

    /** Marker radius in px at the map's scale: the single-pin radius, widened so a longer label stays inside the circle. */
    val radius: Float get() = if (label.length <= 2) PinRadius else PinRadius + (label.length - 2) * PinRadiusPerChar
}

private const val PinRadius = 13f
private const val PinRadiusPerChar = 3.5f

/**
 * Pins at the same spot (the same place centre, to about 10 cm) become one [PinGroup] so their numbers are not printed over
 * each other. Groups follow the order of their first pin; a lone pin is a group of one.
 */
fun groupCoincidentPins(pins: List<VisitPin>): List<PinGroup> =
    pins.groupBy { Math.round(it.position.lat * 1e6) to Math.round(it.position.lng * 1e6) }
        .values
        .map { PinGroup(it.first(), it.map { pin -> pin.number }) }
