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
        .map { seg -> simplifyWindowed(seg.map { GeoPoint(it.lat, it.lng) }, config.routeSimplifyToleranceM) }
}

/** Douglas-Peucker is O(n²) at worst; simplifying fixed windows (sharing their boundary points) bounds it at O(n·w). */
private const val SIMPLIFY_WINDOW = 2_000

private fun simplifyWindowed(pts: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    if (pts.size <= SIMPLIFY_WINDOW) return simplify(pts, toleranceM)
    val out = ArrayList<GeoPoint>()
    var from = 0
    while (from < pts.size - 1) {
        val to = minOf(from + SIMPLIFY_WINDOW, pts.size - 1)
        val part = simplify(pts.subList(from, to + 1), toleranceM)
        // Each window starts on the previous one's last point; don't repeat it.
        out.addAll(if (out.isEmpty()) part else part.subList(1, part.size))
        from = to
    }
    return out
}

/**
 * Iterative Douglas-Peucker in a local equirectangular plane. Endpoints are always kept. A linear radial pass runs
 * first: GPS jitter during a long visit is the zig-zag that drives Douglas-Peucker to its O(n²) worst case, and points
 * within the tolerance of the last kept one could never survive anyway.
 */
private fun simplify(input: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    if (input.size <= 2) return input
    val lat0 = input.sumOf { it.lat } / input.size
    val kx = METRES_PER_DEG_LAT * cos(Math.toRadians(lat0))
    val pts = radialThin(input, toleranceM, kx)
    val n = pts.size
    if (n <= 2) return pts
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

/** Keeps a point only when it is more than [toleranceM] from the last kept one; the last point is always kept. */
private fun radialThin(pts: List<GeoPoint>, toleranceM: Double, kx: Double): List<GeoPoint> {
    val out = ArrayList<GeoPoint>(pts.size)
    out += pts[0]
    for (i in 1 until pts.size - 1) {
        val last = out.last()
        val d = hypot((pts[i].lng - last.lng) * kx, (pts[i].lat - last.lat) * METRES_PER_DEG_LAT)
        if (d > toleranceM) out += pts[i]
    }
    out += pts.last()
    return out
}

private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
