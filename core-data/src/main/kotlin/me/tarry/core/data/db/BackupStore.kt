package me.tarry.core.data.db

import androidx.room.withTransaction
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
import me.tarry.core.data.model.SessionEndReason

/** Every row of every table: a full copy of the database. */
data class BackupSnapshot(
    val sessions: List<TrackingSession> = emptyList(),
    val pings: List<LocationPing> = emptyList(),
    val jobs: List<Job> = emptyList(),
    val places: List<Place> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val jobBlocks: List<JobBlock> = emptyList(),
    val excludedRanges: List<ExcludedRange> = emptyList(),
    val gaps: List<Gap> = emptyList(),
    val scheduleTemplates: List<ScheduleTemplate> = emptyList(),
    val scheduleOverrides: List<ScheduleOverride> = emptyList(),
    val manualOverrides: List<ManualOverride> = emptyList(),
    val checkpoints: List<SegmentationCheckpoint> = emptyList(),
) {
    /** What the user sees: live visits and all locations. */
    fun counts() = BackupCounts(visits = visits.count { !it.deleted }, locations = pings.size)
}

data class BackupCounts(val visits: Int, val locations: Int)

/**
 * Whole-database snapshot and replace for backup and restore. [snapshot] reads every table in one read transaction;
 * [replaceAll] swaps the contents in one write transaction, so a failure leaves the old data untouched.
 */
class BackupStore(private val db: TarryDatabase) {
    private val dao = db.backupDao()

    suspend fun snapshot(): BackupSnapshot = db.withTransaction {
        BackupSnapshot(
            sessions = dao.sessions(),
            pings = dao.pings(),
            jobs = dao.jobs(),
            places = dao.places(),
            visits = dao.visits(),
            trips = dao.trips(),
            jobBlocks = dao.jobBlocks(),
            excludedRanges = dao.excludedRanges(),
            gaps = dao.gaps(),
            scheduleTemplates = dao.scheduleTemplates(),
            scheduleOverrides = dao.scheduleOverrides(),
            manualOverrides = dao.manualOverrides(),
            checkpoints = dao.checkpoints(),
        )
    }

    /** Live visits and all locations currently stored, without loading them. */
    suspend fun counts(): BackupCounts = db.withTransaction { BackupCounts(dao.liveVisitCount(), dao.pingCount()) }

    /**
     * Deletes every table and inserts [snapshot], all in one transaction (sessions before pings, jobs before blocks,
     * chunked). A session still open in [snapshot] (a backup taken mid-session) is closed at its last ping, or at its
     * start without pings, with [SessionEndReason.KILLED] and no Gap: the time between backup and restore was not lost
     * on this device, and crash recovery would otherwise find the open row and write one.
     * When [backupCreatedAt] is given, manual overrides still active at that time are ended at it (see above).
     */
    suspend fun replaceAll(snapshot: BackupSnapshot, backupCreatedAt: Long? = null) = db.withTransaction {
        // Children first, so no cascade or FK check fires on the way out.
        dao.deletePings()
        dao.deleteJobBlocks()
        dao.deleteSessions()
        dao.deleteJobs()
        dao.deletePlaces()
        dao.deleteVisits()
        dao.deleteTrips()
        dao.deleteExcludedRanges()
        dao.deleteGaps()
        dao.deleteScheduleTemplates()
        dao.deleteScheduleOverrides()
        dao.deleteManualOverrides()
        dao.deleteCheckpoints()

        val lastPing = snapshot.pings.groupBy { it.sessionId }.mapValues { (_, p) -> p.maxOf { it.timestamp } }
        // Manual overrides still active when the backup was made end then: the restored phone follows only the schedule
        // until the user acts. Ones that had already ended are untouched.
        val overrides = snapshot.manualOverrides.map {
            if (backupCreatedAt != null && (it.end == null || it.end > backupCreatedAt)) {
                it.copy(end = maxOf(backupCreatedAt, it.start), active = false)
            } else {
                it
            }
        }
        val sessions = snapshot.sessions.map {
            if (it.end == null) it.copy(end = lastPing[it.id] ?: it.start, endReason = SessionEndReason.KILLED) else it
        }
        sessions.chunked(CHUNK).forEach { dao.insertSessions(it) }
        snapshot.pings.chunked(CHUNK).forEach { dao.insertPings(it) }
        snapshot.jobs.chunked(CHUNK).forEach { dao.insertJobs(it) }
        snapshot.jobBlocks.chunked(CHUNK).forEach { dao.insertJobBlocks(it) }
        snapshot.places.chunked(CHUNK).forEach { dao.insertPlaces(it) }
        snapshot.visits.chunked(CHUNK).forEach { dao.insertVisits(it) }
        snapshot.trips.chunked(CHUNK).forEach { dao.insertTrips(it) }
        snapshot.excludedRanges.chunked(CHUNK).forEach { dao.insertExcludedRanges(it) }
        snapshot.gaps.chunked(CHUNK).forEach { dao.insertGaps(it) }
        snapshot.scheduleTemplates.chunked(CHUNK).forEach { dao.insertScheduleTemplates(it) }
        snapshot.scheduleOverrides.chunked(CHUNK).forEach { dao.insertScheduleOverrides(it) }
        overrides.chunked(CHUNK).forEach { dao.insertManualOverrides(it) }
        snapshot.checkpoints.chunked(CHUNK).forEach { dao.insertCheckpoints(it) }
    }

    private companion object {
        const val CHUNK = 500
    }
}
