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

@Dao
interface VisitDao {
    @Upsert suspend fun upsert(visit: Visit)

    @Upsert suspend fun upsertAll(visits: List<Visit>)

    @Query("SELECT * FROM visit WHERE id = :id")
    suspend fun getById(id: String): Visit?

    /** Rows overlapping [from, to), including deleted ones (for sync/segmentation bookkeeping). */
    @Query("SELECT * FROM visit WHERE start < :to AND `end` > :from ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<Visit>

    @Query("SELECT * FROM visit WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    fun observeInRange(from: Long, to: Long): Flow<List<Visit>>

    @Upsert suspend fun upsertTag(link: VisitTag)

    @Query("DELETE FROM visit_tag WHERE visitId = :visitId AND tagId = :tagId")
    suspend fun deleteTag(visitId: String, tagId: String)

    @Query("SELECT * FROM visit_tag WHERE visitId = :visitId")
    suspend fun getTags(visitId: String): List<VisitTag>
}

@Dao
interface TripDao {
    @Upsert suspend fun upsert(trip: Trip)

    @Upsert suspend fun upsertAll(trips: List<Trip>)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun getById(id: String): Trip?

    @Query("SELECT * FROM trip WHERE start < :to AND `end` > :from ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<Trip>

    @Query("SELECT * FROM trip WHERE deleted = 0 AND start < :to AND `end` > :from ORDER BY start")
    fun observeInRange(from: Long, to: Long): Flow<List<Trip>>

    @Upsert suspend fun upsertTag(link: TripTag)

    @Query("DELETE FROM trip_tag WHERE tripId = :tripId AND tagId = :tagId")
    suspend fun deleteTag(tripId: String, tagId: String)

    @Query("SELECT * FROM trip_tag WHERE tripId = :tripId")
    suspend fun getTags(tripId: String): List<TripTag>
}

@Dao
interface GapDao {
    @Upsert suspend fun upsert(gap: Gap)

    @Upsert suspend fun upsertAll(gaps: List<Gap>)

    @Query("SELECT * FROM gap WHERE id = :id")
    suspend fun getById(id: String): Gap?

    @Query("SELECT * FROM gap WHERE start < :to AND `end` > :from ORDER BY start")
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

    @Query("SELECT * FROM schedule_override WHERE weekStart >= :fromWeek AND weekStart < :toWeek ORDER BY weekStart")
    suspend fun getOverridesInRange(fromWeek: String, toWeek: String): List<ScheduleOverride>

    @Query("DELETE FROM schedule_override WHERE weekStart = :weekStart")
    suspend fun deleteOverride(weekStart: String)
}
