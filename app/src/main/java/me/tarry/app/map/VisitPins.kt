package me.tarry.app.map

import me.tarry.segmentation.geo.haversineMeters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

/**
 * One drawn marker: the visits (in number order) whose pins share a spot. Takes the first visit's id and colours.
 * [durationMs] is the total time of all its visits and [visitIds] their ids.
 */
data class PinGroup(
    val first: VisitPin,
    val numbers: List<Int>,
    val durationMs: Long = 0L,
    val visitIds: List<String> = listOf(first.visitId),
) {
    /** "6" for a lone visit, "6, 7" for visits at the same spot. */
    val label: String get() = numbers.joinToString(", ")

    /**
     * Marker radius in px at the map's scale: sized by the total time spent here ([dwellRadius]), widened when a longer
     * label ("6, 7") needs more room than that.
     */
    val radius: Float get() = max(dwellRadius(durationMs), labelRadius(label))

    /** True when the group contains the visit the scrubber is on. */
    fun isActive(activeVisit: String?): Boolean = activeVisit != null && activeVisit in visitIds
}

private const val MinR = 7f
private const val MaxR = 28f
private const val CapMs = 4 * 60 * 60 * 1000L

/** Pin radius for [durationMs] spent: circle area grows with time, from 7 up to 28 at four hours. */
fun dwellRadius(durationMs: Long): Float =
    MinR + (MaxR - MinR) * sqrt(min(durationMs.coerceAtLeast(0L), CapMs) / CapMs.toFloat())

/** Room a label needs: nothing extra for one or two characters (the map hides them in a small circle), more per extra character. */
private fun labelRadius(label: String): Float = if (label.length <= 2) 0f else PinRadius + (label.length - 2) * PinRadiusPerChar

/** Pins are drawn largest first so short stops sit on top of long ones and stay tappable. */
fun sortedForDrawing(groups: List<PinGroup>): List<PinGroup> = groups.sortedByDescending { it.radius }

/** A pin the tap box found: its radius in style units and how far its centre is from the tap, in px. */
data class PinHit(val id: String, val radiusStyle: Float, val centreDistancePx: Float)

/**
 * The tapped pin among overlapping candidates: of the circles that contain the tap, the smallest (it is drawn on top);
 * with none containing it, the one whose edge is nearest.
 */
fun pickPin(hits: List<PinHit>, pxPerStyleUnit: Float): String? {
    fun edge(h: PinHit) = h.centreDistancePx - h.radiusStyle * pxPerStyleUnit
    val inside = hits.filter { edge(it) <= 0f }
    return if (inside.isNotEmpty()) inside.minByOrNull { it.radiusStyle }?.id else hits.minByOrNull { edge(it) }?.id
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
        .map { g ->
            PinGroup(g.first(), g.map { it.number }, g.sumOf { (it.end - it.start).coerceAtLeast(0L) }, g.map { it.visitId })
        }
