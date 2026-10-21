package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

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
 * 5. resolve overlaps with stays > gaps > trips: gaps lose any time a stay covers, and trips are cut around gaps.
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

        val stays = ArrayList<StayCandidate>()
        val trips = ArrayList<TripCandidate>()
        for (stretch in input.range.minus(frozen)) {
            val local = pings.filter { it.timeMs in stretch }
            val reachesEnd = stretch.endMs == input.range.endMs
            var detected = detectStays(local, params)
            val last = detected.lastOrNull()
            if (last != null && last.open && last.range.durationMs < params.minStayDurationMs) {
                detected = detected.dropLast(1)
            }
            if (!reachesEnd) detected = detected.map { if (it.open) it.copy(open = false) else it }
            val split = splitStaysByPlace(detected, input.places, params)
            val merged = mergeStays(split, local, params, distinctKnownPlaces(input.places))
            val offset = stays.size
            buildTrips(merged, local).mapTo(trips) { t ->
                t.copy(fromStay = t.fromStay?.plus(offset), toStay = t.toStay?.plus(offset))
            }
            stays += merged
        }

        val assignment = assignPlaces(stays, input.places, params)
        val stayRanges = stays.map { it.range }
        val gaps = buildGaps(input, pings, params).flatMap { gap ->
            gap.range.minus(stayRanges).filter { it.durationMs > 0 }.map { Gap(it, gap.cause) }
        }
        val gapRanges = gaps.map { it.range }

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
            trips = trips.flatMap { cutAroundGaps(it, gapRanges, stays, pings) },
            gaps = gaps,
            newPlaces = assignment.newPlaces,
            placeUpdates = assignment.updates,
        )
    }

    /**
     * The parts of [trip] outside [gaps]. Only the first part keeps the origin stay and only the last keeps the
     * destination; each part's distance is the ping path inside it plus the legs to the stays it keeps. Parts
     * that cover no distance (a silence at one spot) are dropped.
     */
    private fun cutAroundGaps(
        trip: TripCandidate,
        gaps: List<TimeRange>,
        stays: List<StayCandidate>,
        pings: List<Ping>,
    ): List<Trip> {
        if (gaps.none { it.overlaps(trip.range) }) {
            return listOf(Trip(trip.range, trip.fromStay, trip.toStay, trip.distanceM, trip.mode))
        }
        val parts = trip.range.minus(gaps).filter { it.durationMs > 0 }
        return parts.mapIndexedNotNull { i, part ->
            val from = trip.fromStay.takeIf { i == 0 && part.startMs == trip.range.startMs }
            val to = trip.toStay.takeIf { i == parts.lastIndex && part.endMs == trip.range.endMs }
            val path = buildList {
                from?.let { add(stays[it].lat to stays[it].lng) }
                pings.filter { it.timeMs > part.startMs && it.timeMs < part.endMs }.forEach { add(it.lat to it.lng) }
                to?.let { add(stays[it].lat to stays[it].lng) }
            }
            val distance = path.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }
            if (distance <= 0.0) null else Trip(part, from, to, distance, trip.mode)
        }
    }
}
