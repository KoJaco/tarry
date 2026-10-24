package me.tarry.app.map

/**
 * Everything the day map draws. [route] is the broken, simplified polyline; [pins] are numbered visits at their place
 * centroid; [points] are the accuracy-filtered pings (for the scrubber marker). [bounds] covers route and pins.
 * [routeMissing] is true when the day has visits or trips but no drawable route (pings purged or never recorded), so
 * the UI can say why the line is absent (AGENTS.md rule 6).
 */
data class DayMap(
    val route: List<List<GeoPoint>>,
    val pins: List<VisitPin>,
    val points: List<RoutePoint>,
    val bounds: GeoBounds?,
    val visitCount: Int,
    val tripCount: Int,
    val routeMissing: Boolean,
)

/** Pure assembly of a [DayMap]; [gaps] break the route. */
fun buildDayMap(
    visits: List<PinInput>,
    tripCount: Int,
    gaps: List<TimeSpan>,
    points: List<RoutePoint>,
    config: MapConfig = MapConfig(),
): DayMap {
    val route = buildRoute(points, gaps, config)
    val pins = visitPins(visits)
    val usable = points.filter { it.accuracyM <= config.routeMaxAccuracyM }.sortedBy { it.timestamp }
    return DayMap(
        route = route,
        pins = pins,
        points = usable,
        bounds = boundsOf(route.flatten() + pins.map { it.position }),
        visitCount = visits.size,
        tripCount = tripCount,
        routeMissing = route.isEmpty() && (visits.isNotEmpty() || tripCount > 0),
    )
}
