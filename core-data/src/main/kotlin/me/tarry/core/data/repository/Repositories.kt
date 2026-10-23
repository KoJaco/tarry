package me.tarry.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tarry.core.data.db.dao.ExcludedRangeDao
import me.tarry.core.data.db.dao.GapDao
import me.tarry.core.data.db.dao.JobDao
import me.tarry.core.data.db.dao.LocationPingDao
import me.tarry.core.data.db.dao.ManualOverrideDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.dao.ScheduleDao
import me.tarry.core.data.db.dao.SegmentationCheckpointDao
import me.tarry.core.data.db.dao.TagDao
import me.tarry.core.data.db.dao.TrackingSessionDao
import me.tarry.core.data.db.dao.TripDao
import me.tarry.core.data.db.dao.VisitDao
import me.tarry.core.data.db.entity.ExcludedRange
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.ScheduleOverride
import me.tarry.core.data.db.entity.ScheduleTemplate
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.TripTag
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag
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

    suspend fun savePing(ping: LocationPing): Long =
        pings.upsert(ping).let { if (it == -1L) ping.id else it }
    suspend fun savePings(list: List<LocationPing>) = pings.upsertAll(list)
    suspend fun pingsInRange(from: Long, to: Long) = pings.getInRange(from, to)
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
    suspend fun tagVisit(link: VisitTag) = visits.upsertTag(link)
    suspend fun untagVisit(visitId: String, tagId: String) = visits.deleteTag(visitId, tagId)
    suspend fun visitTags(visitId: String) = visits.getTags(visitId)

    suspend fun saveTrip(trip: Trip) = trips.upsert(trip)
    suspend fun trip(id: String): Trip? = trips.getById(id)
    suspend fun tripsInRange(from: Long, to: Long) = trips.getInRange(from, to)
    fun observeTrips(from: Long, to: Long): Flow<List<Trip>> = trips.observeInRange(from, to)
    suspend fun tagTrip(link: TripTag) = trips.upsertTag(link)
    suspend fun untagTrip(tripId: String, tagId: String) = trips.deleteTag(tripId, tagId)
    suspend fun tripTags(tripId: String) = trips.getTags(tripId)

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
    suspend fun addDefaultTag(link: PlaceDefaultTag) = places.upsertDefaultTag(link)
    suspend fun removeDefaultTag(placeId: String, tagId: String) = places.deleteDefaultTag(placeId, tagId)
    suspend fun defaultTags(placeId: String) = places.getDefaultTags(placeId)
}

@Singleton
class JobRepository @Inject constructor(private val jobs: JobDao) {
    suspend fun save(job: Job) = jobs.upsert(job)
    suspend fun job(id: String): Job? = jobs.getById(id)
    fun observeAll(): Flow<List<Job>> = jobs.observeAll()
}

@Singleton
class TagRepository @Inject constructor(private val tags: TagDao) {
    suspend fun saveGroup(group: TagGroup) = tags.upsertGroup(group)
    suspend fun save(tag: Tag) = tags.upsert(tag)
    suspend fun group(id: String): TagGroup? = tags.getGroupById(id)
    suspend fun tag(id: String): Tag? = tags.getById(id)
    suspend fun tagsInGroup(groupId: String) = tags.getByGroup(groupId)
    fun observeGroups(): Flow<List<TagGroup>> = tags.observeGroups()
    fun observeTags(): Flow<List<Tag>> = tags.observeTags()
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

    suspend fun saveOverride(weekStart: String, schedule: Schedule, updatedAt: Long) =
        schedules.upsertOverride(ScheduleOverride(weekStart, schedule.toBytes(), updatedAt))

    suspend fun clearOverride(weekStart: String) = schedules.deleteOverride(weekStart)
}
