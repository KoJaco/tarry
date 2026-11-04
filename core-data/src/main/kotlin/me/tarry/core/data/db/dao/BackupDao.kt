package me.tarry.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
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

/**
 * Whole-table reads, bulk inserts and whole-table deletes for backup and restore. Used only through
 * [me.tarry.core.data.db.BackupStore], which wraps them in one transaction. Inserts keep the given ids (a restore
 * must reproduce the rows exactly) and abort on a conflict, which rolls the restore back.
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM tracking_session ORDER BY id") suspend fun sessions(): List<TrackingSession>
    @Query("SELECT * FROM location_ping ORDER BY id") suspend fun pings(): List<LocationPing>
    @Query("SELECT * FROM job ORDER BY id") suspend fun jobs(): List<Job>
    @Query("SELECT * FROM place ORDER BY id") suspend fun places(): List<Place>
    @Query("SELECT * FROM visit ORDER BY id") suspend fun visits(): List<Visit>
    @Query("SELECT * FROM trip ORDER BY id") suspend fun trips(): List<Trip>
    @Query("SELECT * FROM job_block ORDER BY id") suspend fun jobBlocks(): List<JobBlock>
    @Query("SELECT * FROM excluded_range ORDER BY id") suspend fun excludedRanges(): List<ExcludedRange>
    @Query("SELECT * FROM gap ORDER BY id") suspend fun gaps(): List<Gap>
    @Query("SELECT * FROM schedule_template ORDER BY id") suspend fun scheduleTemplates(): List<ScheduleTemplate>
    @Query("SELECT * FROM schedule_override ORDER BY weekStart") suspend fun scheduleOverrides(): List<ScheduleOverride>
    @Query("SELECT * FROM manual_override ORDER BY id") suspend fun manualOverrides(): List<ManualOverride>
    @Query("SELECT * FROM segmentation_checkpoint ORDER BY id") suspend fun checkpoints(): List<SegmentationCheckpoint>

    @Insert suspend fun insertSessions(rows: List<TrackingSession>)
    @Insert suspend fun insertPings(rows: List<LocationPing>)
    @Insert suspend fun insertJobs(rows: List<Job>)
    @Insert suspend fun insertPlaces(rows: List<Place>)
    @Insert suspend fun insertVisits(rows: List<Visit>)
    @Insert suspend fun insertTrips(rows: List<Trip>)
    @Insert suspend fun insertJobBlocks(rows: List<JobBlock>)
    @Insert suspend fun insertExcludedRanges(rows: List<ExcludedRange>)
    @Insert suspend fun insertGaps(rows: List<Gap>)
    @Insert suspend fun insertScheduleTemplates(rows: List<ScheduleTemplate>)
    @Insert suspend fun insertScheduleOverrides(rows: List<ScheduleOverride>)
    @Insert suspend fun insertManualOverrides(rows: List<ManualOverride>)
    @Insert suspend fun insertCheckpoints(rows: List<SegmentationCheckpoint>)

    @Query("DELETE FROM location_ping") suspend fun deletePings()
    @Query("DELETE FROM job_block") suspend fun deleteJobBlocks()
    @Query("DELETE FROM tracking_session") suspend fun deleteSessions()
    @Query("DELETE FROM job") suspend fun deleteJobs()
    @Query("DELETE FROM place") suspend fun deletePlaces()
    @Query("DELETE FROM visit") suspend fun deleteVisits()
    @Query("DELETE FROM trip") suspend fun deleteTrips()
    @Query("DELETE FROM excluded_range") suspend fun deleteExcludedRanges()
    @Query("DELETE FROM gap") suspend fun deleteGaps()
    @Query("DELETE FROM schedule_template") suspend fun deleteScheduleTemplates()
    @Query("DELETE FROM schedule_override") suspend fun deleteScheduleOverrides()
    @Query("DELETE FROM manual_override") suspend fun deleteManualOverrides()
    @Query("DELETE FROM segmentation_checkpoint") suspend fun deleteCheckpoints()

    @Query("SELECT MAX(id) FROM location_ping") suspend fun maxPingId(): Long?
    @Query("SELECT * FROM location_ping WHERE id > :after AND id <= :maxId ORDER BY id LIMIT :limit")
    suspend fun pingsPage(after: Long, maxId: Long, limit: Int): List<LocationPing>

    /** Closes sessions still open: at their last ping (else their start), KILLED (stored as the enum name). */
    @Query(
        "UPDATE tracking_session SET `end` = COALESCE((SELECT MAX(timestamp) FROM location_ping " +
            "WHERE location_ping.sessionId = tracking_session.id), start), endReason = 'KILLED' WHERE `end` IS NULL",
    )
    suspend fun closeOpenSessions()

    @Query("SELECT COUNT(*) FROM visit WHERE deleted = 0") suspend fun liveVisitCount(): Int
    @Query("SELECT COUNT(*) FROM location_ping") suspend fun pingCount(): Int
}
