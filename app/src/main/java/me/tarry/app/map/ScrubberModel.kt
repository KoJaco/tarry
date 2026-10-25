package me.tarry.app.map

import kotlin.math.abs
import kotlin.math.roundToLong

/** What a scrubber block represents. */
enum class ScrubKind { VISIT, TRIP, GAP }

/** One visit, trip or gap on the day strip. [start]/[end] are UTC epoch ms, half-open. */
data class ScrubItem(val id: String, val kind: ScrubKind, val start: Long, val end: Long)

/** An item placed on the strip; fractions are 0..1 of [ScrubberModel.range]. */
data class ScrubBlock(val item: ScrubItem, val startFraction: Float, val endFraction: Float)

/**
 * Pure model behind the map scrubber: time-proportional blocks, time/fraction mapping, the marker position along the
 * route, and snapping/stepping to visit starts. The strip covers the worked span, not the whole day.
 */
class ScrubberModel(
    items: List<ScrubItem>,
    dayStart: Long,
    dayEnd: Long,
    points: List<RoutePoint>,
    private val config: MapConfig = MapConfig(),
    /** Pinned visits' place centres: during a visit the marker sits there instead of following ping jitter. */
    private val visitCentres: Map<String, GeoPoint> = emptyMap(),
) {
    private val clamped: List<ScrubItem> = items
        .map { it.copy(start = maxOf(it.start, dayStart), end = minOf(it.end, dayEnd)) }
        .filter { it.end > it.start }
        .sortedBy { it.start }

    /** Earliest item start to latest item end within the day; null when there is nothing to show. */
    val range: TimeSpan? =
        if (clamped.isEmpty()) null else TimeSpan(clamped.first().start, clamped.maxOf { it.end })

    private val length: Long = range?.let { it.end - it.start } ?: 0L

    /** Items clamped to [range], sorted by start. */
    val blocks: List<ScrubBlock> = clamped.map { ScrubBlock(it, fractionAt(it.start), fractionAt(it.end)) }

    private val pings: List<RoutePoint> =
        points.filter { it.accuracyM <= config.routeMaxAccuracyM }.sortedBy { it.timestamp }

    private val gaps: List<ScrubItem> = clamped.filter { it.kind == ScrubKind.GAP }

    private val starts: List<Long> =
        clamped.filter { it.kind == ScrubKind.VISIT }.map { it.start }.distinct().sorted()

    /** Position of [t] along the strip, 0..1 (clamped). */
    fun fractionAt(t: Long): Float {
        val r = range ?: return 0f
        if (length <= 0L) return 0f
        return ((t.coerceIn(r.start, r.end) - r.start).toDouble() / length).toFloat()
    }

    /** Time at strip [fraction] (clamped to 0..1); inverse of [fractionAt]. */
    fun timeAt(fraction: Float): Long {
        val r = range ?: return 0L
        return r.start + (fraction.coerceIn(0f, 1f).toDouble() * length).roundToLong()
    }

    /**
     * The visit's place centre while [t] is inside a pinned visit; otherwise the interpolated route position, or null with no pings, outside the ping span, or inside a break longer than
     * `routeBreakMs` (`tripBreakMs` inside a trip, matching the drawn line; no fake position).
     */
    fun positionAt(t: Long): GeoPoint? {
        itemAt(t)?.takeIf { it.kind == ScrubKind.VISIT }?.let { visit -> visitCentres[visit.id]?.let { return it } }
        if (pings.isEmpty() || t < pings.first().timestamp || t > pings.last().timestamp) return null
        // Binary search: this runs on every drag frame over a whole day of pings.
        val found = pings.binarySearchBy(t) { it.timestamp }
        if (found >= 0) return pings[found].let { GeoPoint(it.lat, it.lng) }
        val hi = -found - 1
        val b = pings[hi]
        val a = pings[hi - 1]
        // Inside a trip the line breaks after tripBreakMs (buildTripRoutes), so the marker does too.
        val breakMs = if (itemAt(t)?.kind == ScrubKind.TRIP) config.tripBreakMs else config.routeBreakMs
        if (b.timestamp - a.timestamp > breakMs) return null
        // A recorded gap between the two pings breaks the route line too (buildRoute), so no position there either.
        if (gaps.any { it.start < b.timestamp && it.end > a.timestamp }) return null
        val f = (t - a.timestamp).toDouble() / (b.timestamp - a.timestamp)
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }

    /** Visit start times, sorted and clamped to [range]. */
    fun visitStarts(): List<Long> = starts

    /** Nearest visit start to [t] (ties go earlier), or [t] when there are no visits. */
    fun snap(t: Long): Long = starts.minWithOrNull(compareBy<Long> { abs(it - t) }.thenBy { it }) ?: t

    /** First visit start strictly after [t], for the "next visit" button. */
    fun next(t: Long): Long? = starts.firstOrNull { it > t }

    /** Last visit start strictly before [t], for the "previous visit" button. */
    fun previous(t: Long): Long? = starts.lastOrNull { it < t }

    /** The item covering [t] (half-open); a visit wins over a trip, which wins over a gap. */
    fun itemAt(t: Long): ScrubItem? =
        clamped.filter { t >= it.start && t < it.end }.minByOrNull { it.kind.ordinal }
}
