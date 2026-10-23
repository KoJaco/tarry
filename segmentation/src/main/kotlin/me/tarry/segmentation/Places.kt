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

/**
 * A place smaller than the stay radius. Only the user makes one (by shrinking a place, docs/specs/tarry-mvp.md
 * §Same-site disambiguation), so being just outside it means being somewhere else. Being just outside a default
 * place means nothing: a stay's pings reach [SegmentationParams.stayRadiusM] from its centroid, and places shared
 * by nearby stays reach further, so treating that as another site would split stays on every re-run.
 */
internal fun KnownPlace.isTight(params: SegmentationParams): Boolean = radiusM < params.stayRadiusM

/**
 * True when GPS cannot tell [a] and [b] apart: one lies within the other's radius, or neither is tight (see [isTight];
 * the user shrank a tight place on purpose to separate neighbours) and their centroids are closer than
 * [SegmentationParams.indistinguishablePlaceFactor] times [SegmentationParams.stayRadiusM].
 */
internal fun indistinguishablePlaces(a: KnownPlace, b: KnownPlace, params: SegmentationParams, noiseM: Double = 0.0): Boolean {
    val d = haversineMeters(a.lat, a.lng, b.lat, b.lng)
    if (d <= maxOf(a.radiusM, b.radiusM)) return true
    return !a.isTight(params) && !b.isTight(params) &&
        d < params.indistinguishablePlaceFactor * minOf(params.placeMaxRadiusM, maxOf(params.stayRadiusM, noiseM))
}

/** Place id to cluster label (the smallest id of its connected [indistinguishablePlaces] component). */
private fun clusterLabels(placesInStay: List<KnownPlace>, params: SegmentationParams, noiseM: Double): Map<String, String> {
    val label = placesInStay.associate { it.id to it.id }.toMutableMap()
    var changed = true
    while (changed) {
        changed = false
        for (a in placesInStay) for (b in placesInStay) {
            val la = label.getValue(a.id)
            val lb = label.getValue(b.id)
            if (la < lb && indistinguishablePlaces(a, b, params, noiseM)) {
                for (k in label.keys) if (label.getValue(k) == lb) label[k] = la
                changed = true
            }
        }
    }
    return label
}

/**
 * True when [a] and [b] are at different places: two different known places, or a tight known place and no place.
 * No place next to a default-sized place is not a difference (see [isTight]).
 */
internal fun distinctKnownPlaces(
    places: List<KnownPlace>,
    params: SegmentationParams,
): (StayCandidate, StayCandidate) -> Boolean = { a, b ->
    val pa = matchPlace(a.lat, a.lng, places)
    val pb = matchPlace(b.lat, b.lng, places)
    when {
        pa == null && pb == null -> false
        pa != null && pb != null ->
            pa.id != pb.id && !indistinguishablePlaces(pa, pb, params, maxOf(stayNoiseM(a, params), stayNoiseM(b, params)))
        else -> (pa ?: pb)!!.isTight(params)
    }
}

/**
 * Splits a stay whose pings dwell in different known places, so two boats 40 m apart become two stays once each
 * has its own small place (a 60 m stay radius alone would keep them together).
 *
 * Places GPS cannot tell apart (see [indistinguishablePlaces]) share one label, so a stay is never split between
 * them; [assignPlaces] then gives the whole stay the known place nearest its centroid.
 *
 * Each ping is labelled with its matching place (or none; unplaced pings take a neighbouring label unless the stay
 * touches a tight place, see [isTight]). Consecutive pings with the same label form runs; a run's extent is its
 * first to its last ping, which is also the range of the stay it becomes. Runs shorter than
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
    val matched = stay.pings.map { matchPlace(it.lat, it.lng, places) }
    val cluster = clusterLabels(matched.filterNotNull().distinctBy { it.id }, params, stayNoiseM(stay, params))
    val ids = matched.map { it?.let { p -> cluster.getValue(p.id) } }
    // Without a tight place in this stay, pings in no place belong with the place next to them (see [isTight]).
    val labels = if (matched.any { it != null && it.isTight(params) }) ids else absorbUnplaced(ids)
    if (labels.distinct().size < 2) return listOf(stay)

    val runs = ArrayList<Run>()
    stay.pings.forEachIndexed { i, ping ->
        val last = runs.lastOrNull()
        if (last != null && last.label == labels[i]) last.pings += ping else runs += Run(labels[i], arrayListOf(ping))
    }
    // The run's own extent, not the time to the next run: a one-ping run is a zero-length stay however far off the next is.
    fun span(i: Int): Long = runs[i].pings.last().timeMs - runs[i].pings.first().timeMs

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
 * the earlier new place's radius (at least [SegmentationParams.placeMatchRadiusM], scaled up to [SegmentationParams.placeMaxRadiusM]
 * with the stay's noise, see [noiseScaledRadiusM]) of its running centroid share it (a morning and an
 * afternoon at the same new site make one place).
 * Centroids are duration-weighted means; an existing place's current centroid carries its [KnownPlace.weightMs].
 */
internal fun assignPlaces(
    stays: List<StayCandidate>,
    places: List<KnownPlace>,
    params: SegmentationParams,
): PlaceAssignment {
    val refs = ArrayList<PlaceRef>(stays.size)
    val created = ArrayList<WeightedPoint>()
    val radii = ArrayList<Double>()
    val moved = LinkedHashMap<String, WeightedPoint>()
    for (stay in stays) {
        val weight = maxOf(stay.range.durationMs, 1L).toDouble()
        val scale = noiseScaledRadiusM(stay, params)
        val known = matchPlace(stay.lat, stay.lng, places)
        if (known != null) {
            refs += PlaceRef.Existing(known.id)
            moved.getOrPut(known.id) { WeightedPoint(known.lat, known.lng, known.weightMs.toDouble()) }
                .add(stay.lat, stay.lng, weight)
            continue
        }
        val index = created.indices
            .map { it to haversineMeters(stay.lat, stay.lng, created[it].lat, created[it].lng) }
            .filter { (k, d) -> d <= maxOf(radii[k], scale) }
            .minByOrNull { (_, d) -> d }
            ?.first
        if (index != null) {
            created[index].add(stay.lat, stay.lng, weight)
            radii[index] = maxOf(radii[index], scale)
            refs += PlaceRef.New(index)
        } else {
            created += WeightedPoint(stay.lat, stay.lng, weight)
            radii += scale
            refs += PlaceRef.New(created.lastIndex)
        }
    }
    return PlaceAssignment(
        refs = refs,
        newPlaces = created.mapIndexed { k, p -> NewPlace(p.lat, p.lng, radii[k]) },
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

/** Replaces each null label with the nearest earlier non-null label, else the nearest later one. */
private fun absorbUnplaced(labels: List<String?>): List<String?> {
    val forward = ArrayList<String?>(labels.size)
    var last: String? = null
    for (l in labels) {
        if (l != null) last = l
        forward += last
    }
    var next: String? = null
    val out = arrayOfNulls<String>(labels.size)
    for (i in labels.indices.reversed()) {
        if (labels[i] != null) next = labels[i]
        out[i] = forward[i] ?: next
    }
    return out.toList()
}
