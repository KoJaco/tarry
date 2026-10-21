package me.tarry.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.TrackingSession

/** Row of [LocationPingDao.observePingCounts]. */
data class SessionPingCount(val sessionId: Long, val count: Int)

@Dao
interface TrackingSessionDao {
    /** Returns the new row id on insert, or -1 when the upsert fell back to an update. */
    @Upsert suspend fun upsert(session: TrackingSession): Long

    @Query("SELECT * FROM tracking_session WHERE id = :id")
    suspend fun getById(id: Long): TrackingSession?

    @Query("SELECT * FROM tracking_session WHERE start >= :from AND start < :to ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<TrackingSession>

    /** Sessions overlapping [from, to): started before `to` and still open or ended after `from`. */
    @Query("SELECT * FROM tracking_session WHERE start < :to AND (`end` IS NULL OR `end` > :from) ORDER BY start")
    suspend fun getOverlapping(from: Long, to: Long): List<TrackingSession>

    @Query("SELECT * FROM tracking_session WHERE `end` IS NULL ORDER BY start DESC LIMIT 1")
    suspend fun getOpenSession(): TrackingSession?

    /** All sessions, newest first (debug sessions screen). */
    @Query("SELECT * FROM tracking_session ORDER BY start DESC")
    fun observeAll(): Flow<List<TrackingSession>>
}

@Dao
interface LocationPingDao {
    /** Returns the new row id on insert, or -1 when the upsert fell back to an update. */
    @Upsert suspend fun upsert(ping: LocationPing): Long

    @Upsert suspend fun upsertAll(pings: List<LocationPing>)

    @Query("SELECT * FROM location_ping WHERE id = :id")
    suspend fun getById(id: Long): LocationPing?

    @Query("SELECT * FROM location_ping WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    suspend fun getInRange(from: Long, to: Long): List<LocationPing>

    @Query("SELECT * FROM location_ping WHERE sessionId = :sessionId ORDER BY timestamp")
    suspend fun getBySession(sessionId: Long): List<LocationPing>

    @Query("DELETE FROM location_ping WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** Accepted pings per session; sessions without pings are absent. */
    @Query("SELECT sessionId, COUNT(*) AS count FROM location_ping GROUP BY sessionId")
    fun observePingCounts(): Flow<List<SessionPingCount>>
}

@Dao
interface ManualOverrideDao {
    /** Returns the new row id on insert, or -1 when the upsert fell back to an update. */
    @Upsert suspend fun upsert(override: ManualOverride): Long

    @Query("SELECT * FROM manual_override WHERE id = :id")
    suspend fun getById(id: Long): ManualOverride?

    @Query("SELECT * FROM manual_override WHERE start < :to AND (`end` IS NULL OR `end` > :from) ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<ManualOverride>

    @Query("SELECT * FROM manual_override WHERE active = 1 ORDER BY start DESC")
    fun observeActive(): Flow<List<ManualOverride>>
}

@Dao
interface SegmentationCheckpointDao {
    @Upsert suspend fun upsert(checkpoint: SegmentationCheckpoint)

    @Query("SELECT * FROM segmentation_checkpoint WHERE id = :id")
    suspend fun getById(id: Int = 0): SegmentationCheckpoint?
}
