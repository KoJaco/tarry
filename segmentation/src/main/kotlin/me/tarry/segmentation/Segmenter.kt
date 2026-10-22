package me.tarry.segmentation

/**
 * Pure, deterministic segmentation (docs/specs/phase-2b.md): pings plus frozen and excluded ranges, places,
 * windows and sessions in; stays, trips, gaps and place changes out. Same input, same output.
 *
 * Steps:
 * 1. drop pings outside [SegmentationInput.range] or inside excluded or frozen ranges (T8);
 * 2. split the range at frozen ranges into free stretches. Each stretch is segmented on its own, so output
 *    never crosses or changes a frozen record (T8);
 * 3. per stretch: detect stays (T3), split them by known place, merge (T5) and build trips (T4). A trailing
 *    candidate still open but shorter than the minimum stay is not a stay yet: its pings stay part of the
 *    trailing trip;
 * 4. assign places across all stretches (T6) and build gaps (T7);
 * 5. resolve overlaps with stays > gaps > trips: gaps lose any time a stay covers, and trips are cut around gaps,
 *    time outside every session and excluded time (no overnight trip between two days' stays).
 *
 * Only a stay that is open at the very end of the range is [Stay.mayBeOngoing].
 */
object Segmenter {
    fun segment(input: SegmentationInput, params: SegmentationParams = SegmentationParams()): SegmentationResult {
        val frozen = input.frozen.normalized()
        val blocked = (input.excluded + frozen).normalized()
        val pings = input.pings
            .asSequence()
            .filter { it.timeMs in input.range && blocked.none { r -> it.timeMs in r } }
            .sortedBy { it.timeMs }
            .toList()

        // Stale repeats carry no position: they stay out of stays and trips but still count as tracking (gaps).
        val stale = staleRepeats(pings, params)
        val positional = pings.filter { it !in stale }

        val stays = ArrayList<StayCandidate>()
        val trips = ArrayList<TripCandidate>()
        for (stretch in input.range.minus(frozen)) {
            val local = positional.filter { it.timeMs in stretch }
            val reachesEnd = stretch.endMs == input.range.endMs
            var detected = detectStays(local, params)
            val last = detected.lastOrNull()
            if (last != null && last.open && last.range.durationMs < params.minStayDurationMs) {
                detected = detected.dropLast(1)
            }
            if (!reachesEnd) detected = detected.map { if (it.open) it.copy(open = false) else it }
            val split = splitStaysByPlace(detected, input.places, params)
            val merged = attributeArrivals(
                mergeStays(split, local, params, distinctKnownPlaces(input.places, params)),
                local,
                params,
            )
            val offset = stays.size
            buildTrips(merged, local, params).mapTo(trips) { t ->
                t.copy(fromStay = t.fromStay?.plus(offset), toStay = t.toStay?.plus(offset))
            }
            stays += merged
        }

        val assignment = assignPlaces(stays, input.places, params)
        val stayRanges = stays.map { it.range }
        val gaps = buildGaps(input, pings, params).flatMap { gap ->
            gap.range.minus(stayRanges).filter { it.durationMs > 0 }.map { Gap(it, gap.cause) }
        }
        // A trip is movement seen by capture. Time outside every session (overnight, between windows) and excluded
        // time are not, so trips are cut there as well as around gaps. With no sessions given (tests, traces) the
        // whole range counts as captured.
        val outsideSessions = if (input.sessions.isEmpty()) emptyList() else input.range.minus(input.sessions)
        val cuts = (gaps.map { it.range } + outsideSessions + input.excluded).normalized()

        return SegmentationResult(
            stays = stays.mapIndexed { i, s ->
                Stay(
                    range = s.range,
                    lat = s.lat,
                    lng = s.lng,
                    place = assignment.refs[i],
                    confidence = if (s.bridged) params.bridgedConfidence else params.confidence,
                    bridged = s.bridged,
                    mayBeOngoing = s.open,
                )
            },
            trips = trips.flatMap { cutAround(it, cuts, stays, positional, params) },
            gaps = gaps,
            newPlaces = assignment.newPlaces,
            placeUpdates = assignment.updates,
        )
    }

    /** Pings repeating an earlier ping's exact lat/lng at an accuracy of [SegmentationParams.maxAccuracyM] or worse. */
    private fun staleRepeats(pings: List<Ping>, params: SegmentationParams): Set<Ping> {
        val seen = HashSet<Pair<Double, Double>>()
        val stale = HashSet<Ping>()
        for (p in pings) {
            if (!seen.add(p.lat to p.lng) && p.accuracyM >= params.maxAccuracyM) stale += p
        }
        return stale
    }

    /**
     * The parts of [trip] outside [cuts] (gaps, time outside sessions, excluded time). Only the first part keeps
     * the origin stay and only the last keeps the destination; each part's distance is the ping path inside it
     * plus the legs to the stays it keeps (accuracy-aware, see [pathDistance]). Parts that cover no distance (a silence at one spot) are dropped.
     */
    private fun cutAround(
        trip: TripCandidate,
        cuts: List<TimeRange>,
        stays: List<StayCandidate>,
        pings: List<Ping>,
        params: SegmentationParams,
    ): List<Trip> {
        if (cuts.none { it.overlaps(trip.range) }) {
            return listOf(Trip(trip.range, trip.fromStay, trip.toStay, trip.distanceM, trip.mode))
        }
        val parts = trip.range.minus(cuts).filter { it.durationMs > 0 }
        return parts.mapIndexedNotNull { i, part ->
            val from = trip.fromStay.takeIf { i == 0 && part.startMs == trip.range.startMs }
            val to = trip.toStay.takeIf { i == parts.lastIndex && part.endMs == trip.range.endMs }
            val path = buildList {
                from?.let { add(stayEdge(stays[it], atEnd = true, pings = pings)) }
                pings.filter { it.timeMs > part.startMs && it.timeMs < part.endMs }.forEach { add(it.toPathPoint()) }
                to?.let { add(stayEdge(stays[it], atEnd = false, pings = pings)) }
            }
            val distance = pathDistance(path, params)
            if (distance <= 0.0) null else Trip(part, from, to, distance, trip.mode)
        }
    }
}
