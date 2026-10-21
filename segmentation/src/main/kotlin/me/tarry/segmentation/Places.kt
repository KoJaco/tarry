package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

/**
 * Place steps (docs/specs/phase-2b.md T6, docs/specs/tarry-mvp.md §Same-site disambiguation).
 *
 * A place matches a point when the point is within the place's own radius; the nearest such place wins.
 * Merged places never match. Ignored places match like any other (they are hidden from totals later).
 */
internal fun matchPlace(lat: Double, lng: Double, places: List<KnownPlace>): KnownPlace? =
    places.asSequence()
        .filter { !it.merged }
        .map { it to haversineMeters(lat, lng, it.lat, it.lng) }
        .filter { (place, d) -> d <= place.radiusM }
        .minByOrNull { (_, d) -> d }
        ?.first

/** True when [a] and [b] fall in different known places (or one in a place and the other in none). */
internal fun distinctKnownPlaces(places: List<KnownPlace>): (StayCandidate, StayCandidate) -> Boolean = { a, b ->
    matchPlace(a.lat, a.lng, places)?.id != matchPlace(b.lat, b.lng, places)?.id
}

/**
 * Splits a stay whose pings dwell in different known places, so two boats 40 m apart become two stays once each
 * has its own small place (a 60 m stay radius alone would keep them together).
 *
 * Each ping is labelled with its matching place (or none). Consecutive pings with the same label form runs;
 * a run spans from its first ping to the next run's first ping. Runs shorter than
 * [SegmentationParams.minStayDurationMs] are absorbed into their longer neighbour, shortest first, so
 * accuracy noise at a place's edge does not split anything. What remains, if more than one run, becomes
 * separate stays. Stays with no ping in any known place are returned unchanged.
 */
internal fun splitStaysByPlace(
    stays: List<StayCandidate>,
    places: List<KnownPlace>,
    params: SegmentationParams,
): List<StayCandidate> {
    if (places.none { !it.merged }) return stays
    return stays.flatMap { split(it, places, params) }
}

private class Run(val label: String?, val pings: MutableList<Ping>)

private fun split(stay: StayCandidate, places: List<KnownPlace>, params: SegmentationParams): List<StayCandidate> {
    val labels = stay.pings.map { matchPlace(it.lat, it.lng, places)?.id }
    if (labels.distinct().size < 2) return listOf(stay)

    val runs = ArrayList<Run>()
    stay.pings.forEachIndexed { i, ping ->
        val last = runs.lastOrNull()
        if (last != null && last.label == labels[i]) last.pings += ping else runs += Run(labels[i], arrayListOf(ping))
    }
    fun span(i: Int): Long =
        (runs.getOrNull(i + 1)?.pings?.first()?.timeMs ?: runs[i].pings.last().timeMs) - runs[i].pings.first().timeMs

    while (runs.size > 1) {
        val shortest = runs.indices.minBy(::span)
        if (span(shortest) >= params.minStayDurationMs) break
        val target = when {
            shortest == 0 -> 1
            shortest == runs.lastIndex -> shortest - 1
            span(shortest + 1) > span(shortest - 1) -> shortest + 1
            else -> shortest - 1
        }
        val absorbed = runs.removeAt(shortest)
        val into = runs[if (target > shortest) target - 1 else target]
        into.pings += absorbed.pings
        into.pings.sortBy { it.timeMs }
        // Absorbing a run can make its two neighbours adjacent with the same label.
        var i = 0
        while (i < runs.lastIndex) {
            if (runs[i].label == runs[i + 1].label) {
                runs[i].pings += runs.removeAt(i + 1).pings
            } else {
                i++
            }
        }
    }
    if (runs.size == 1) return listOf(stay)

    return runs.mapIndexed { i, run ->
        val (lat, lng) = weightedCentroid(run.pings, params)
        StayCandidate(
            range = TimeRange(run.pings.first().timeMs, run.pings.last().timeMs),
            lat = lat,
            lng = lng,
            pings = run.pings.toList(),
            bridged = run.pings.zipWithNext().any { (a, b) -> b.timeMs - a.timeMs > params.signalGapMs },
            open = stay.open && i == runs.lastIndex,
        )
    }
}

/** Place refs for each stay (same order), the places to create, and moved centroids of matched places. */
internal data class PlaceAssignment(
    val refs: List<PlaceRef>,
    val newPlaces: List<NewPlace>,
    val updates: List<PlaceCentroidUpdate>,
)

/**
 * Assigns each stay to the nearest matching known place, otherwise to a new place. Unmatched stays within
 * [SegmentationParams.placeMatchRadiusM] of an earlier new place's running centroid share it (a morning and an
 * afternoon at the same new site make one place). A new place's radius covers its stays' pings (see [NewPlace]).
 * Centroids are duration-weighted means; an existing place's
 * current centroid carries its [KnownPlace.weightMs].
 */
internal fun assignPlaces(
    stays: List<StayCandidate>,
    places: List<KnownPlace>,
    params: SegmentationParams,
): PlaceAssignment {
    val refs = ArrayList<PlaceRef>(stays.size)
    val created = ArrayList<WeightedPoint>()
    val members = ArrayList<MutableList<Ping>>()
    val moved = LinkedHashMap<String, WeightedPoint>()
    for (stay in stays) {
        val weight = maxOf(stay.range.durationMs, 1L).toDouble()
        val known = matchPlace(stay.lat, stay.lng, places)
        if (known != null) {
            refs += PlaceRef.Existing(known.id)
            moved.getOrPut(known.id) { WeightedPoint(known.lat, known.lng, known.weightMs.toDouble()) }
                .add(stay.lat, stay.lng, weight)
            continue
        }
        val index = created.indices
            .map { it to haversineMeters(stay.lat, stay.lng, created[it].lat, created[it].lng) }
            .filter { (_, d) -> d <= params.placeMatchRadiusM }
            .minByOrNull { (_, d) -> d }
            ?.first
        if (index != null) {
            created[index].add(stay.lat, stay.lng, weight)
            members[index] += stay.pings
            refs += PlaceRef.New(index)
        } else {
            created += WeightedPoint(stay.lat, stay.lng, weight)
            members += stay.pings.toMutableList()
            refs += PlaceRef.New(created.lastIndex)
        }
    }
    return PlaceAssignment(
        refs = refs,
        newPlaces = created.mapIndexed { i, p ->
            val farthest = members[i].maxOfOrNull { haversineMeters(it.lat, it.lng, p.lat, p.lng) } ?: 0.0
            val radius = (farthest + params.newPlaceRadiusMarginM)
                .coerceIn(params.placeMatchRadiusM, params.maxNewPlaceRadiusM)
            NewPlace(p.lat, p.lng, radius)
        },
        updates = moved.map { (id, p) -> PlaceCentroidUpdate(id, p.lat, p.lng) },
    )
}

private class WeightedPoint(lat: Double, lng: Double, private var weight: Double) {
    private var latSum = lat * weight
    private var lngSum = lng * weight
    // With no prior weight the first add sets the point outright.
    private val start = lat to lng

    val lat: Double get() = if (weight == 0.0) start.first else latSum / weight
    val lng: Double get() = if (weight == 0.0) start.second else lngSum / weight

    fun add(lat: Double, lng: Double, w: Double) {
        latSum += lat * w
        lngSum += lng * w
        weight += w
    }
}
