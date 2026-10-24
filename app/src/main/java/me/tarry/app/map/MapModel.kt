package me.tarry.app.map

/**
 * Plain types shared by the pure map logic (route, pins, circles, scrubber). No Android or MapLibre imports, so all of
 * it is JVM-testable; `ui/map` converts these to MapLibre geometry.
 */

/** A WGS84 position in degrees. */
data class GeoPoint(val lat: Double, val lng: Double)

/** One location ping reduced to what the map needs. [timestamp] is UTC epoch ms. */
data class RoutePoint(val timestamp: Long, val lat: Double, val lng: Double, val accuracyM: Float)

/** A half-open UTC range `[start, end)` in epoch ms. */
data class TimeSpan(val start: Long, val end: Long)

/** An axis-aligned box in degrees, for fitting the camera. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** Map tunables (AGENTS.md rule 5: no scattered literals). */
data class MapConfig(
    /** Consecutive pings further apart in time than this are not joined by the route line. */
    val routeBreakMs: Long = 10 * 60_000L,
    /** Pings less accurate than this are left out of the route line (network fixes at noisy sites). */
    val routeMaxAccuracyM: Float = 100f,
    /** Douglas-Peucker tolerance for the drawn route. */
    val routeSimplifyToleranceM: Double = 5.0,
    /** While tracking, the open day map rebuilds from new pings at most this often. */
    val liveRefreshMs: Long = 5_000L,
    /** Vertices in a radius circle polygon. */
    val circleSegments: Int = 64,
    /** A dropped map pin offers known places whose centre is within this distance. */
    val pinSnapM: Double = 150.0,
    /** Minimum touch box for a pin tap (WCAG 2.5.8 / AGENTS.md rule 11). */
    val pinTouchDp: Float = 48f,
)
