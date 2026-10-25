package me.tarry.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.tarry.core.data.db.entity.ExcludedRange
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.ScheduleOverride
import me.tarry.core.data.db.entity.ScheduleTemplate
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.TripTag
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag

/** Row of [VisitDao.durationByPlaceOutside]. */
data class PlaceDuration(val placeId: String, val durationMs: Long)

/** Row of [VisitDao.observePlaceVisitCounts]. */
data class PlaceVisitCount(val placeId: String, val count: Int)

@Dao
interface VisitDao {
    /** Live visit count per place; places with none are absent. */
    @Query("SELECT placeId, COUNT(*) AS count FROM visit WHERE deleted = 0 AND placeId IS NOT NULL GROUP BY placeId")
    fun observePlaceVisitCounts(): Flow<List<PlaceVisitCount>>

    @Upsert suspend fun upsert(visit: Visit)

    @Upsert suspend fun upsertAll(visits: List<Visit>)

    @Query("SELECT * FROM visit WHERE id = :id")
    suspend fun getById(id: String): Visit?

    /** Non-deleted rows overlapping [from, to). */
    @Query("SELECT * FROM visit WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<Visit>

    @Query("SELECT * FROM visit WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    fun observeInRange(from: Long, to: Long): Flow<List<Visit>>

    /** Non-deleted, non-frozen rows overlapping [from, to). */
    @Query("SELECT * FROM visit WHERE deleted = 0 AND frozen = 0 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getUnfrozenInRange(from: Long, to: Long): List<Visit>

    /** Non-deleted, frozen rows overlapping [from, to). */
    @Query("SELECT * FROM visit WHERE deleted = 0 AND frozen = 1 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getFrozenInRange(from: Long, to: Long): List<Visit>

    /** Total visit time per place, counting only non-deleted visits that do not overlap [from, to). */
    @Query(
        "SELECT placeId AS placeId, SUM(`end` - start) AS durationMs FROM visit " +
            "WHERE deleted = 0 AND placeId IS NOT NULL AND NOT (start < :to AND `end` > :from) GROUP BY placeId",
    )
    suspend fun durationByPlaceOutside(from: Long, to: Long): List<PlaceDuration>

    /** Every non-deleted visit at the place, frozen or not. */
    @Query("SELECT * FROM visit WHERE deleted = 0 AND placeId = :placeId ORDER BY start")
    suspend fun getLiveByPlace(placeId: String): List<Visit>

    /** Start of the earliest non-deleted, non-frozen visit at any of the places, or null when there is none. */
    @Query("SELECT MIN(start) FROM visit WHERE deleted = 0 AND frozen = 0 AND placeId IN (:placeIds)")
    suspend fun earliestUnfrozenStart(placeIds: List<String>): Long?

    /** Total time of the place's non-deleted visits. */
    @Query("SELECT COALESCE(SUM(`end` - start), 0) FROM visit WHERE deleted = 0 AND placeId = :placeId")
    suspend fun durationAt(placeId: String): Long

    /** Moves every non-deleted visit (frozen included) from one place to another; only placeId and updatedAt change. */
    @Query("UPDATE visit SET placeId = :to, updatedAt = :updatedAt WHERE deleted = 0 AND placeId = :from")
    suspend fun repointPlace(from: String, to: String, updatedAt: Long)

    /** Soft-deletes the place's non-frozen visits (no exclusion is recorded, so segmentation detects them again). */
    @Query("UPDATE visit SET deleted = 1, updatedAt = :updatedAt WHERE deleted = 0 AND frozen = 0 AND placeId = :placeId")
    suspend fun softDeleteUnfrozenAt(placeId: String, updatedAt: Long)

    @Upsert suspend fun upsertTag(link: VisitTag)

    @Query("DELETE FROM visit_tag WHERE visitId = :visitId AND tagId = :tagId")
    suspend fun deleteTag(visitId: String, tagId: String)

    @Query("SELECT * FROM visit_tag WHERE visitId = :visitId")
    suspend fun getTags(visitId: String): List<VisitTag>

    @Query("DELETE FROM visit_tag WHERE visitId = :visitId")
    suspend fun deleteAllTags(visitId: String)

    /** The non-deleted visit that ends last at or before [time] (the previous neighbour of a visit starting at [time]). */
    @Query("SELECT * FROM visit WHERE deleted = 0 AND `end` <= :time ORDER BY `end` DESC LIMIT 1")
    suspend fun getLastEndingAtOrBefore(time: Long): Visit?

    /** The non-deleted visit that starts first at or after [time] (the next neighbour of a visit ending at [time]). */
    @Query("SELECT * FROM visit WHERE deleted = 0 AND start >= :time ORDER BY start LIMIT 1")
    suspend fun getFirstStartingAtOrAfter(time: Long): Visit?
}

@Dao
interface TripDao {
    @Upsert suspend fun upsert(trip: Trip)

    @Upsert suspend fun upsertAll(trips: List<Trip>)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun getById(id: String): Trip?

    @Query("SELECT * FROM trip WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<Trip>

    @Query("SELECT * FROM trip WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    fun observeInRange(from: Long, to: Long): Flow<List<Trip>>

    /** Non-deleted, non-frozen rows overlapping [from, to). */
    @Query("SELECT * FROM trip WHERE deleted = 0 AND frozen = 0 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getUnfrozenInRange(from: Long, to: Long): List<Trip>

    /** Non-deleted, frozen rows overlapping [from, to). */
    @Query("SELECT * FROM trip WHERE deleted = 0 AND frozen = 1 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getFrozenInRange(from: Long, to: Long): List<Trip>

    @Upsert suspend fun upsertTag(link: TripTag)

    @Query("DELETE FROM trip_tag WHERE tripId = :tripId AND tagId = :tagId")
    suspend fun deleteTag(tripId: String, tagId: String)

    @Query("SELECT * FROM trip_tag WHERE tripId = :tripId")
    suspend fun getTags(tripId: String): List<TripTag>

    @Query("DELETE FROM trip_tag WHERE tripId = :tripId")
    suspend fun deleteAllTags(tripId: String)
}

@Dao
interface GapDao {
    @Upsert suspend fun upsert(gap: Gap)

    @Upsert suspend fun upsertAll(gaps: List<Gap>)

    @Query("SELECT * FROM gap WHERE id = :id")
    suspend fun getById(id: String): Gap?

    @Query("SELECT * FROM gap WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<Gap>

    @Query("SELECT * FROM gap WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    fun observeInRange(from: Long, to: Long): Flow<List<Gap>>
}

@Dao
interface ExcludedRangeDao {
    @Upsert suspend fun upsert(range: ExcludedRange)

    @Query("SELECT * FROM excluded_range WHERE id = :id")
    suspend fun getById(id: String): ExcludedRange?

    @Query("SELECT * FROM excluded_range WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<ExcludedRange>

    @Query("SELECT * FROM excluded_range WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    fun observeInRange(from: Long, to: Long): Flow<List<ExcludedRange>>
}

@Dao
interface ScheduleDao {
    @Upsert suspend fun upsertTemplate(template: ScheduleTemplate)

    @Query("SELECT * FROM schedule_template WHERE id = 0")
    suspend fun getTemplate(): ScheduleTemplate?

    @Query("SELECT * FROM schedule_template WHERE id = 0")
    fun observeTemplate(): Flow<ScheduleTemplate?>

    @Upsert suspend fun upsertOverride(override: ScheduleOverride)

    @Query("SELECT * FROM schedule_override WHERE weekStart = :weekStart")
    suspend fun getOverride(weekStart: String): ScheduleOverride?

    @Query("SELECT * FROM schedule_override WHERE weekStart = :weekStart")
    fun observeOverride(weekStart: String): Flow<ScheduleOverride?>

    @Query("SELECT * FROM schedule_override WHERE weekStart >= :fromWeek AND weekStart < :toWeek ORDER BY weekStart")
    suspend fun getOverridesInRange(fromWeek: String, toWeek: String): List<ScheduleOverride>

    @Query("DELETE FROM schedule_override WHERE weekStart = :weekStart")
    suspend fun deleteOverride(weekStart: String)
}
