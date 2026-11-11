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
 * Whether the day map's camera should fit the whole day: once per day, at the first non-empty bounds. An empty day (today
 * before anything is recorded) doesn't use up the fit, so the first visit or trip still brings the camera in. A fit made
 * before the bottom overlay was measured ([bottomPx] 0) is redone once when its height is known, so the day is never left
 * fitted to the full map with the card and strip covering part of it.
 */
class DayFit {
    private var fittedFor: LocalDate? = null
    private var fittedBottomPx = 0

    fun fit(day: LocalDate, bounds: GeoBounds?, bottomPx: Int = 0): Boolean {
        if (bounds == null) return false
        val first = fittedFor != day
        val measuredLater = !first && fittedBottomPx == 0 && bottomPx > 0
        if (!first && !measuredLater) return false
        fittedFor = day
        fittedBottomPx = bottomPx
        return true
    }
}

/** Pixels kept free on each side when fitting a day: [margin] of air, the attribution strip on top, the measured overlay below. */
data class FitPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

fun dayFitPadding(marginPx: Int, topInsetPx: Int, overlayPx: Int): FitPadding =
    FitPadding(marginPx, topInsetPx + marginPx, marginPx, overlayPx + marginPx)

/**
 * Whether the day map's camera should follow a new scrubber marker. A day's first position is skipped so it doesn't undo
 * the fit to the whole day; it is the scrubber's starting point, not a user scrub. A null marker (the day still loading,
 * or the scrubber inside a break) neither moves the camera nor uses up that skip. The same marker again (a style
 * reload after a light/dark switch re-runs the effect) never moves the camera, so the user's view is kept.
 */
class MarkerFollow {
    private var seenFor: LocalDate? = null
    private var lastMarker: GeoPoint? = null
    private var lastDay: LocalDate? = null

    fun follow(day: LocalDate, marker: GeoPoint?): Boolean {
        if (marker == null) return false
        val follow = seenFor == day && !(marker == lastMarker && lastDay == day)
        seenFor = day
        lastMarker = marker
        lastDay = day
        return follow
    }
}
