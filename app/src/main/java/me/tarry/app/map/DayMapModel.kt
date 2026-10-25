package me.tarry.app.map

import java.time.LocalDate

/**
 * Everything the day map draws. [routes] are the trips (recorded stretches only; unrecorded ones are left out); [pins] are
 * numbered visits at their place centroid, each drawn with its place radius, so a stay is one circle rather than its
 * ping jitter. [points] are the accuracy-filtered pings and [visitCentres] the pinned visits' positions, for the
 * scrubber marker. [bounds] covers routes and pins. [routeMissing] is true when the day has visits or trips but no
 * usable pings at all (purged or never recorded), so the UI can say why there is no route (AGENTS.md rule 6).
 */
data class DayMap(
    val routes: List<TripRoute>,
    val pins: List<VisitPin>,
    val points: List<RoutePoint>,
    val visitCentres: Map<String, GeoPoint>,
    val bounds: GeoBounds?,
    val visitCount: Int,
    val tripCount: Int,
    val routeMissing: Boolean,
)

/**
 * Pure assembly of a [DayMap]. A trip leaves from the visit just before it and arrives at the visit just after it (in
 * time order), when those have a position.
 */
fun buildDayMap(
    visits: List<PinInput>,
    trips: List<TimeSpan>,
    gaps: List<TimeSpan>,
    points: List<RoutePoint>,
    config: MapConfig = MapConfig(),
): DayMap {
    val usable = points.filter { it.accuracyM <= config.routeMaxAccuracyM }.sortedBy { it.timestamp }
    val sequence: List<Any> = (visits.map { it.start to it } + trips.map { it.start to it }).sortedBy { it.first }.map { it.second }
    val legs = sequence.mapIndexedNotNull { i, item ->
        if (item !is TimeSpan) return@mapIndexedNotNull null
        val before = (sequence.getOrNull(i - 1) as? PinInput)?.position
        val after = (sequence.getOrNull(i + 1) as? PinInput)?.position
        TripLeg(item.start, item.end, before, after)
    }
    val routes = buildTripRoutes(usable, legs, gaps, config)
    val pins = visitPins(visits)
    return DayMap(
        routes = routes,
        pins = pins,
        points = usable,
        visitCentres = pins.associate { it.visitId to it.position },
        bounds = boundsOf(routes.flatMap { it.points } + pins.map { it.position }),
        visitCount = visits.size,
        tripCount = trips.size,
        routeMissing = usable.isEmpty() && (visits.isNotEmpty() || trips.isNotEmpty()),
    )
}

/**
 * Whether the day map's camera should follow a new scrubber marker. A day's first position is skipped so it doesn't undo
 * the fit to the whole day; it is the scrubber's starting point, not a user scrub. A null marker (the day still loading,
 * or the scrubber inside a break) neither moves the camera nor uses up that skip.
 */
class MarkerFollow {
    private var seenFor: LocalDate? = null

    fun follow(day: LocalDate, marker: GeoPoint?): Boolean {
        if (marker == null) return false
        val follow = seenFor == day
        seenFor = day
        return follow
    }
}
