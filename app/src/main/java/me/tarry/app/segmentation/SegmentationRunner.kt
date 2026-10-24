package me.tarry.app.segmentation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tarry.app.tracking.ScheduleWindowChecker
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.dao.ExcludedRangeDao
import me.tarry.core.data.db.dao.GapDao
import me.tarry.core.data.db.dao.LocationPingDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.dao.SegmentationCheckpointDao
import me.tarry.core.data.db.dao.TrackingSessionDao
import me.tarry.core.data.db.dao.TripDao
import me.tarry.core.data.db.dao.VisitDao
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag
import me.tarry.core.data.model.VisitOrigin
import me.tarry.segmentation.KnownPlace
import me.tarry.segmentation.Ping
import me.tarry.segmentation.PlaceRef
import me.tarry.segmentation.SegmentationInput
import me.tarry.segmentation.SegmentationParams
import me.tarry.segmentation.SegmentationResult
import me.tarry.segmentation.Segmenter
import me.tarry.segmentation.TimeRange
import me.tarry.segmentation.geo.haversineMeters
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [Segmenter] over the unprocessed pings and reconciles the result with the stored rows (docs/specs/phase-2b.md
 * T10). Rows keep their ids across runs: a new stay or trip reuses the id of the existing unfrozen row it overlaps
 * most, and a row is only written when something changed, so a re-run over the same data is a no-op. Rows that
 * disappear are soft-deleted, never removed. Frozen rows are never touched.
 *
 * One run at a time per process: the mutex is shared by every instance, so a periodic, a one-time and a daily worker
 * never overlap.
 */
@Singleton
class SegmentationRunner @Inject constructor(
    private val store: SegmentationStore,
    private val pingDao: LocationPingDao,
    private val sessionDao: TrackingSessionDao,
    private val checkpointDao: SegmentationCheckpointDao,
    private val placeDao: PlaceDao,
    private val visitDao: VisitDao,
    private val tripDao: TripDao,
    private val gapDao: GapDao,
    private val excludedDao: ExcludedRangeDao,
    private val windowChecker: ScheduleWindowChecker,
    private val params: SegmentationParams,
) {
    suspend fun run(nowMs: Long) {
        mutex.withLock { store.transaction { runLocked(nowMs) } }
    }

    private suspend fun runLocked(now: Long) {
        val checkpoint = checkpointDao.getById()?.processedUntil
            ?: sessionDao.getInRange(0, Long.MAX_VALUE).firstOrNull()?.start
        if (checkpoint == null) {
            saveCheckpoint(now)
            return
        }
        val to = now
        var from = checkpoint - params.overlapMs
        if (from >= to) return

        // Widen once so a detected row is never half inside the range. Segmentation-owned gaps are widened too:
        // they are rebuilt from the range, and reconciling a partly covered gap would otherwise shrink it.
        val unfrozenTrips = tripDao.getUnfrozenInRange(from, to)
        val widenedFrom = buildList {
            visitDao.getUnfrozenInRange(from, to).forEach { add(it.start) }
            unfrozenTrips.forEach { add(it.start) }
            gapDao.getInRange(from, to).filter { it.cause.toSegmentationOrNull() != null }.forEach { add(it.start) }
        }.minOrNull()
        if (widenedFrom != null && widenedFrom < from) {
            from = widenedFrom
            // A trip reached by the widening needs its origin stay in the input, or it is re-emitted without one.
            // One extra step, not a loop: the origin visit's start becomes the bound.
            unfrozenTrips.mapNotNull { it.fromVisitId }.mapNotNull { visitDao.getById(it) }
                .filter { !it.deleted && !it.frozen }.minOfOrNull { it.start }
                ?.let { if (it < from) from = it }
        }

        val pings = pingDao.getInRange(from, to)
        val sessions = sessionDao.getOverlapping(from, to)
        val allGaps = gapDao.getInRange(from, to)
        val knownPlaces = placeDao.getAllActive()
        val weights = visitDao.durationByPlaceOutside(from, to).associate { it.placeId to it.durationMs }

        val input = SegmentationInput(
            range = TimeRange(from, to),
            pings = pings.map { it.toSegmentation() },
            frozen = visitDao.getFrozenInRange(from, to).map { TimeRange(it.start, it.end) } +
                tripDao.getFrozenInRange(from, to).map { TimeRange(it.start, it.end) },
            excluded = excludedDao.getInRange(from, to).map { TimeRange(it.start, it.end) },
            places = knownPlaces.map {
                KnownPlace(
                    id = it.id, lat = it.centroidLat, lng = it.centroidLng, radiusM = it.radiusM,
                    weightMs = weights[it.id] ?: 0L, merged = it.mergedIntoId != null, ignored = it.ignored,
                )
            },
            windows = windowChecker.activeIntervals(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
                .map { TimeRange(it.start.toEpochMilli(), it.end.toEpochMilli()) },
            sessions = sessions.map { TimeRange(it.start, maxOf(it.end ?: now, it.start)) },
            healthGaps = allGaps.filter { it.cause.toSegmentationOrNull() == null }.map { TimeRange(it.start, it.end) },
        )
        val result = Segmenter.segment(input, params)

        val zones = ZoneLookup(pings, sessions)
        val newPlaceIds = result.newPlaces.map { UUID.randomUUID().toString() }
        val placeIds = result.stays.map { stay ->
            when (val ref = stay.place) {
                is PlaceRef.Existing -> ref.id
                is PlaceRef.New -> newPlaceIds[ref.index]
            }
        }
        val places = reconcilePlaces(result, knownPlaces, newPlaceIds, placeIds, now)
        val visitIds = reconcileVisits(result, placeIds, places, from, to, zones, now)
        reconcileTrips(result, visitIds, from, to, zones, now)
        reconcileGaps(result, allGaps, zones, now)
        saveCheckpoint(minOf(nextCheckpoint(result, pings), now))
    }

    /** Writes new, moved and recently visited places. Returns every active place, current values, by id. */
    private suspend fun reconcilePlaces(
        result: SegmentationResult,
        known: List<Place>,
        newPlaceIds: List<String>,
        stayPlaceIds: List<String>,
        now: Long,
    ): Map<String, Place> {
        val byId = known.associateBy { it.id }.toMutableMap()
        val dirty = LinkedHashSet<String>()
        result.newPlaces.forEachIndexed { i, p ->
            val id = newPlaceIds[i]
            byId[id] = Place(
                id = id, centroidLat = p.lat, centroidLng = p.lng, radiusM = p.radiusM.toFloat(),
                updatedAt = now,
            )
            dirty += id
        }
        for (u in result.placeUpdates) {
            val old = byId[u.placeId] ?: continue
            if (haversineMeters(old.centroidLat, old.centroidLng, u.lat, u.lng) > params.placeMoveThresholdM) {
                byId[old.id] = old.copy(centroidLat = u.lat, centroidLng = u.lng, updatedAt = now)
                dirty += old.id
            }
        }
        val lastEnd = HashMap<String, Long>()
        result.stays.forEachIndexed { i, stay -> lastEnd.merge(stayPlaceIds[i], stay.range.endMs, ::maxOf) }
        for ((id, end) in lastEnd) {
            val place = byId[id] ?: continue
            val last = maxOf(place.lastVisitAt ?: end, end)
            if (last != place.lastVisitAt) {
                byId[id] = place.copy(lastVisitAt = last, updatedAt = now)
                dirty += id
            }
        }
        dirty.forEach { placeDao.upsert(byId.getValue(it)) }
        return byId
    }

    /** Returns the visit id of every stay, in order. */
    private suspend fun reconcileVisits(
        result: SegmentationResult,
        placeIds: List<String>,
        places: Map<String, Place>,
        from: Long,
        to: Long,
        zones: ZoneLookup,
        now: Long,
    ): List<String> {
        val candidates = visitDao.getUnfrozenInRange(from, to)
        val match = matchByOverlap(
            result.stays.map { it.range },
            candidates.map { TimeRange(it.start, it.end) },
        ) { stay, candidate -> candidates[candidate].placeId == placeIds[stay] }
        val ids = result.stays.mapIndexed { i, stay ->
            val existing = match[i]?.let { candidates[it] }
            if (existing != null) {
                val updated = existing.copy(
                    start = stay.range.startMs, end = stay.range.endMs, placeId = placeIds[i], confidence = stay.confidence,
                )
                if (updated != existing) visitDao.upsert(updated.copy(updatedAt = now))
                existing.id
            } else {
                // Place defaults apply to new visits only: a reused visit may have been edited since.
                val place = places[placeIds[i]]
                val visit = Visit(
                    placeId = placeIds[i], start = stay.range.startMs, end = stay.range.endMs,
                    zoneId = zones.at(stay.range.startMs), jobId = place?.defaultJobId, billable = place?.defaultBillable,
                    origin = VisitOrigin.DETECTED, confidence = stay.confidence, updatedAt = now,
                )
                visitDao.upsert(visit)
                placeDao.getDefaultTags(placeIds[i]).forEach { visitDao.upsertTag(VisitTag(visit.id, it.tagId)) }
                visit.id
            }
        }
        val used = match.values.toSet()
        candidates.forEachIndexed { i, v -> if (i !in used) visitDao.upsert(v.copy(deleted = true, updatedAt = now)) }
        return ids
    }

    private suspend fun reconcileTrips(
        result: SegmentationResult,
        visitIds: List<String>,
        from: Long,
        to: Long,
        zones: ZoneLookup,
        now: Long,
    ) {
        val candidates = tripDao.getUnfrozenInRange(from, to)
        val match = matchByOverlap(result.trips.map { it.range }, candidates.map { TimeRange(it.start, it.end) }) { _, _ -> true }
        result.trips.forEachIndexed { i, trip ->
            val fromVisit = trip.fromStay?.let { visitIds[it] }
            val toVisit = trip.toStay?.let { visitIds[it] }
            val existing = match[i]?.let { candidates[it] }
            if (existing != null) {
                // An origin outside the input comes back null: keep the stored one while the trip still starts at the same time.
                val keptFrom = fromVisit ?: existing.fromVisitId?.takeIf { existing.start == trip.range.startMs && visitDao.getById(it)?.deleted == false }
                val updated = existing.copy(
                    start = trip.range.startMs, end = trip.range.endMs, fromVisitId = keptFrom, toVisitId = toVisit,
                    distanceM = trip.distanceM.toFloat(), mode = trip.mode.toEntity(),
                )
                if (updated != existing) tripDao.upsert(updated.copy(updatedAt = now))
            } else {
                tripDao.upsert(
                    Trip(
                        start = trip.range.startMs, end = trip.range.endMs, zoneId = zones.at(trip.range.startMs),
                        fromVisitId = fromVisit, toVisitId = toVisit, distanceM = trip.distanceM.toFloat(),
                        mode = trip.mode.toEntity(), updatedAt = now,
                    ),
                )
            }
        }
        val used = match.values.toSet()
        candidates.forEachIndexed { i, t -> if (i !in used) tripDao.upsert(t.copy(deleted = true, updatedAt = now)) }
    }

    private suspend fun reconcileGaps(result: SegmentationResult, inRange: List<Gap>, zones: ZoneLookup, now: Long) {
        val candidates = inRange.filter { it.cause.toSegmentationOrNull() != null }
        val match = matchByOverlap(result.gaps.map { it.range }, candidates.map { TimeRange(it.start, it.end) }) { g, c ->
            candidates[c].cause == result.gaps[g].cause.toEntity()
        }
        result.gaps.forEachIndexed { i, gap ->
            val existing = match[i]?.let { candidates[it] }
            if (existing != null) {
                val updated = existing.copy(start = gap.range.startMs, end = gap.range.endMs)
                if (updated != existing) gapDao.upsert(updated.copy(updatedAt = now))
            } else {
                gapDao.upsert(
                    Gap(
                        start = gap.range.startMs, end = gap.range.endMs, zoneId = zones.at(gap.range.startMs),
                        cause = gap.cause.toEntity(), sessionId = null, updatedAt = now,
                    ),
                )
            }
        }
        val used = match.values.toSet()
        candidates.forEachIndexed { i, g -> if (i !in used) gapDao.upsert(g.copy(deleted = true, updatedAt = now)) }
    }

    /**
     * Where the next run must restart: the start of a stay or trip that may continue past the last ping, so it is
     * re-evaluated with the new pings; otherwise everything up to now is final ([Long.MAX_VALUE], capped by the caller).
     */
    private fun nextCheckpoint(result: SegmentationResult, pings: List<LocationPing>): Long {
        result.stays.lastOrNull { it.mayBeOngoing }?.let { return it.range.startMs }
        val lastTrip = result.trips.lastOrNull()
        val lastPing = pings.lastOrNull()
        if (lastTrip != null && lastPing != null && lastTrip.toStay == null && lastTrip.range.endMs >= lastPing.timestamp) {
            return lastTrip.range.startMs
        }
        return Long.MAX_VALUE
    }

    /**
     * Lowers the checkpoint to [processedUntilMs] if it is later, under the run mutex so an in-flight run cannot write
     * a newer value over it. Creates nothing when no run has happened yet (the first run starts from the first session).
     */
    suspend fun rewindCheckpoint(processedUntilMs: Long) {
        mutex.withLock {
            val current = checkpointDao.getById()?.processedUntil ?: return
            if (processedUntilMs < current) saveCheckpoint(processedUntilMs)
        }
    }

    private suspend fun saveCheckpoint(processedUntil: Long) {
        checkpointDao.upsert(SegmentationCheckpoint(processedUntil = processedUntil))
    }

    /** Capture-time zone for new rows: the latest ping at or before the time, else the first ping, else a session's. */
    private class ZoneLookup(private val pings: List<LocationPing>, private val sessions: List<TrackingSession>) {
        fun at(timeMs: Long): String =
            pings.lastOrNull { it.timestamp <= timeMs }?.zoneId
                ?: pings.firstOrNull()?.zoneId
                ?: sessions.firstOrNull()?.zoneId
                ?: ZoneId.systemDefault().id
    }

    private companion object {
        val mutex = Mutex()
    }
}

private fun LocationPing.toSegmentation() = Ping(
    timeMs = timestamp, lat = lat, lng = lng, accuracyM = accuracyM, activity = activity?.toSegmentation(), sessionId = sessionId,
)

/**
 * For each target in order, the index of the unused candidate that overlaps it most (and passes [accept]), if any
 * overlaps at all. Returns target index to candidate index.
 */
private fun matchByOverlap(
    targets: List<TimeRange>,
    candidates: List<TimeRange>,
    accept: (target: Int, candidate: Int) -> Boolean,
): Map<Int, Int> {
    val used = HashSet<Int>()
    val out = LinkedHashMap<Int, Int>()
    targets.forEachIndexed { t, target ->
        var best = -1
        var bestOverlap = 0L
        candidates.forEachIndexed { c, candidate ->
            if (c in used || !accept(t, c)) return@forEachIndexed
            val overlap = minOf(target.endMs, candidate.endMs) - maxOf(target.startMs, candidate.startMs)
            if (overlap > bestOverlap) {
                best = c
                bestOverlap = overlap
            }
        }
        if (best >= 0) {
            used += best
            out[t] = best
        }
    }
    return out
}
