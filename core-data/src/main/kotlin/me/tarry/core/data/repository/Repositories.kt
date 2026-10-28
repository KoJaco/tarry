package me.tarry.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tarry.core.data.db.dao.ExcludedRangeDao
import me.tarry.core.data.db.dao.GapDao
import me.tarry.core.data.db.dao.JobBlockDao
import me.tarry.core.data.db.dao.JobDao
import me.tarry.core.data.db.dao.LocationPingDao
import me.tarry.core.data.db.dao.ManualOverrideDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.dao.ScheduleDao
import me.tarry.core.data.db.dao.SegmentationCheckpointDao
import me.tarry.core.data.db.dao.TrackingSessionDao
import me.tarry.core.data.db.dao.TripDao
import me.tarry.core.data.db.dao.VisitDao
import me.tarry.core.data.db.entity.ExcludedRange
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.ScheduleOverride
import me.tarry.core.data.db.entity.ScheduleTemplate
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.Schedule
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingRepository @Inject constructor(
    private val sessions: TrackingSessionDao,
    private val pings: LocationPingDao,
    private val overrides: ManualOverrideDao,
    private val checkpoints: SegmentationCheckpointDao,
) {
    suspend fun saveSession(session: TrackingSession): Long =
        sessions.upsert(session).let { if (it == -1L) session.id else it }
    suspend fun session(id: Long): TrackingSession? = sessions.getById(id)
    suspend fun sessionsInRange(from: Long, to: Long) = sessions.getInRange(from, to)
    suspend fun openSession(): TrackingSession? = sessions.getOpenSession()
    fun observeSessions(): Flow<List<TrackingSession>> = sessions.observeAll()
    /** Sessions overlapping `[from, to)`, live; for the day timeline's start/stop markers. */
    fun observeSessionsOverlapping(from: Long, to: Long): Flow<List<TrackingSession>> = sessions.observeOverlapping(from, to)

    suspend fun savePing(ping: LocationPing): Long =
        pings.upsert(ping).let { if (it == -1L) ping.id else it }
    suspend fun savePings(list: List<LocationPing>) = pings.upsertAll(list)
    suspend fun pingsInRange(from: Long, to: Long) = pings.getInRange(from, to)
    /** Ping positions in `[from, to)`, live; for the map only (raw pings never leave the device). */
    fun observePingPositions(from: Long, to: Long) = pings.observePositions(from, to)
    suspend fun pingsForSession(sessionId: Long) = pings.getBySession(sessionId)
    suspend fun purgePingsBefore(before: Long): Int = pings.deleteOlderThan(before)
    /** Ping count per session id; sessions with no pings are absent. */
    fun observePingCounts(): Flow<Map<Long, Int>> =
        pings.observePingCounts().map { rows -> rows.associate { it.sessionId to it.count } }

    suspend fun saveOverride(override: ManualOverride): Long =
        overrides.upsert(override).let { if (it == -1L) override.id else it }
    suspend fun overridesInRange(from: Long, to: Long) = overrides.getInRange(from, to)
    fun observeActiveOverrides(): Flow<List<ManualOverride>> = overrides.observeActive()

    suspend fun checkpoint(): SegmentationCheckpoint? = checkpoints.getById(0)
    suspend fun saveCheckpoint(checkpoint: SegmentationCheckpoint) = checkpoints.upsert(checkpoint)
}

@Singleton
class TimelineRepository @Inject constructor(
    private val visits: VisitDao,
    private val trips: TripDao,
    private val gaps: GapDao,
    private val excluded: ExcludedRangeDao,
) {
    suspend fun saveVisit(visit: Visit) = visits.upsert(visit)
    suspend fun visit(id: String): Visit? = visits.getById(id)
    suspend fun visitsInRange(from: Long, to: Long) = visits.getInRange(from, to)
    fun observeVisits(from: Long, to: Long): Flow<List<Visit>> = visits.observeInRange(from, to)
    /** Live visits per place id; places with none are absent. */
    fun observePlaceVisitCounts(): Flow<Map<String, Int>> =
        visits.observePlaceVisitCounts().map { rows -> rows.associate { it.placeId to it.count } }
    suspend fun liveVisitsAt(placeId: String) = visits.getLiveByPlace(placeId)

    suspend fun saveTrip(trip: Trip) = trips.upsert(trip)
    suspend fun trip(id: String): Trip? = trips.getById(id)
    suspend fun tripsInRange(from: Long, to: Long) = trips.getInRange(from, to)
    fun observeTrips(from: Long, to: Long): Flow<List<Trip>> = trips.observeInRange(from, to)

    suspend fun saveGap(gap: Gap) = gaps.upsert(gap)
    suspend fun gap(id: String): Gap? = gaps.getById(id)
    suspend fun gapsInRange(from: Long, to: Long) = gaps.getInRange(from, to)
    fun observeGaps(from: Long, to: Long): Flow<List<Gap>> = gaps.observeInRange(from, to)

    suspend fun saveExcludedRange(range: ExcludedRange) = excluded.upsert(range)
    suspend fun excludedRangesInRange(from: Long, to: Long) = excluded.getInRange(from, to)
    fun observeExcludedRanges(from: Long, to: Long): Flow<List<ExcludedRange>> =
        excluded.observeInRange(from, to)
}

@Singleton
class PlaceRepository @Inject constructor(private val places: PlaceDao) {
    suspend fun save(place: Place) = places.upsert(place)
    suspend fun place(id: String): Place? = places.getById(id)
    suspend fun byLastVisitInRange(from: Long, to: Long) = places.getByLastVisitInRange(from, to)
    fun observeAll(): Flow<List<Place>> = places.observeAll()

    /** Every place including deleted ones, only for labelling existing visits. Pickers use [observeAll]. */
    fun observeLabels(): Flow<List<Place>> = places.observeLabels()
}

@Singleton
class JobRepository @Inject constructor(private val jobs: JobDao) {
    suspend fun save(job: Job) = jobs.upsert(job)
    suspend fun job(id: String): Job? = jobs.getById(id)
    fun observeAll(): Flow<List<Job>> = jobs.observeAll()
}

@Singleton
class JobBlockRepository @Inject constructor(private val blocks: JobBlockDao) {
    /** Live blocks overlapping `[from, to)` ordered by start, re-emitting when a block changes. */
    fun observeOverlapping(from: Long, to: Long): Flow<List<JobBlock>> = blocks.observeOverlapping(from, to)

    /** Insert or update; always stamps [JobBlock.updatedAt]. */
    suspend fun save(block: JobBlock, now: Long = System.currentTimeMillis()) = blocks.upsert(block.copy(updatedAt = now))

    /** One-shot live blocks overlapping `[from, to)` ordered by start. */
    suspend fun overlapping(from: Long, to: Long): List<JobBlock> = blocks.getOverlapping(from, to)

    /** Saves [save] and soft-deletes [deleteIds] atomically (a same-job merge); stamps [JobBlock.updatedAt] on all of them. */
    suspend fun replace(save: JobBlock, deleteIds: List<String>, now: Long = System.currentTimeMillis()) =
        blocks.replace(save, deleteIds, now)

    /** Soft delete. No-op when the block does not exist. */
    suspend fun delete(id: String, now: Long = System.currentTimeMillis()) {
        blocks.getById(id)?.let { blocks.upsert(it.copy(deleted = true, updatedAt = now)) }
    }

    /** Undo of [delete]. */
    suspend fun restore(id: String, now: Long = System.currentTimeMillis()) {
        blocks.getById(id)?.let { blocks.upsert(it.copy(deleted = false, updatedAt = now)) }
    }

    /** The block by id, deleted ones included. */
    suspend fun byId(id: String): JobBlock? = blocks.getById(id)
}

@Singleton
class ScheduleRepository @Inject constructor(private val schedules: ScheduleDao) {
    suspend fun template(): Schedule? = schedules.getTemplate()?.let { Schedule.fromBytes(it.slots) }

    fun observeTemplate(): Flow<Schedule?> =
        schedules.observeTemplate().map { row -> row?.let { Schedule.fromBytes(it.slots) } }

    suspend fun saveTemplate(schedule: Schedule, updatedAt: Long) =
        schedules.upsertTemplate(ScheduleTemplate(id = 0, slots = schedule.toBytes(), updatedAt = updatedAt))

    /** [weekStart] is the ISO date of the Monday. */
    suspend fun override(weekStart: String): Schedule? =
        schedules.getOverride(weekStart)?.let { Schedule.fromBytes(it.slots) }

    fun observeOverride(weekStart: String): Flow<Schedule?> =
        schedules.observeOverride(weekStart).map { row -> row?.let { Schedule.fromBytes(it.slots) } }

    suspend fun saveOverride(weekStart: String, schedule: Schedule, updatedAt: Long) =
        schedules.upsertOverride(ScheduleOverride(weekStart, schedule.toBytes(), updatedAt))

    suspend fun clearOverride(weekStart: String) = schedules.deleteOverride(weekStart)
}
