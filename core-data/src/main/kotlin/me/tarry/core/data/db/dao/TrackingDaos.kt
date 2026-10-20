package me.tarry.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.TrackingSession

@Dao
interface TrackingSessionDao {
    /** Returns the row id (new or existing). */
    @Upsert suspend fun upsert(session: TrackingSession): Long

    @Query("SELECT * FROM tracking_session WHERE id = :id")
    suspend fun getById(id: Long): TrackingSession?

    @Query("SELECT * FROM tracking_session WHERE start >= :from AND start < :to ORDER BY start")
    suspend fun getInRange(from: Long, to: Long): List<TrackingSession>

    @Query("SELECT * FROM tracking_session WHERE `end` IS NULL ORDER BY start DESC LIMIT 1")
    suspend fun getOpenSession(): TrackingSession?
}

@Dao
interface LocationPingDao {
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
}

@Dao
interface ManualOverrideDao {
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
