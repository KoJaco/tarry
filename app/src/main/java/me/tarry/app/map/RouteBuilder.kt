package me.tarry.app.map

import kotlin.math.cos
import kotlin.math.hypot

private const val METRES_PER_DEG_LAT = 111_194.9266 // EARTH_RADIUS_M * pi / 180

/**
 * Turns pings into drawable polylines: accuracy-filtered, broken at time breaks and recorded gaps, then simplified.
 * Segments with fewer than two points are dropped.
 */
fun buildRoute(
    points: List<RoutePoint>,
    gaps: List<TimeSpan>,
    config: MapConfig = MapConfig(),
): List<List<GeoPoint>> {
    val sorted = points.filter { it.accuracyM <= config.routeMaxAccuracyM }.sortedBy { it.timestamp }
    if (sorted.size < 2) return emptyList()

    val segments = mutableListOf<List<RoutePoint>>()
    var current = mutableListOf(sorted[0])
    for (i in 1 until sorted.size) {
        val prev = sorted[i - 1]
        val next = sorted[i]
        val breaks = next.timestamp - prev.timestamp > config.routeBreakMs ||
            gaps.any { it.start < next.timestamp && it.end > prev.timestamp }
        if (breaks) {
            segments += current
            current = mutableListOf()
        }
        current += next
    }
    segments += current

    return segments
        .filter { it.size >= 2 }
        .map { seg -> simplify(seg.map { GeoPoint(it.lat, it.lng) }, config.routeSimplifyToleranceM) }
}

/** Iterative Douglas-Peucker in a local equirectangular plane. Endpoints are always kept. */
private fun simplify(pts: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    val n = pts.size
    if (n <= 2) return pts
    val lat0 = pts.sumOf { it.lat } / n
    val kx = METRES_PER_DEG_LAT * cos(Math.toRadians(lat0))
    val x = DoubleArray(n) { pts[it].lng * kx }
    val y = DoubleArray(n) { pts[it].lat * METRES_PER_DEG_LAT }

    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, n - 1))
    while (stack.isNotEmpty()) {
        val (a, b) = stack.removeLast()
        if (b <= a + 1) continue
        var maxD = -1.0
        var maxI = -1
        for (i in a + 1 until b) {
            val d = distToSegment(x[i], y[i], x[a], y[a], x[b], y[b])
            if (d > maxD) {
                maxD = d
                maxI = i
            }
        }
        if (maxD > toleranceM) {
            keep[maxI] = true
            stack.addLast(intArrayOf(a, maxI))
            stack.addLast(intArrayOf(maxI, b))
        }
    }
    return pts.filterIndexed { i, _ -> keep[i] }
}

private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
