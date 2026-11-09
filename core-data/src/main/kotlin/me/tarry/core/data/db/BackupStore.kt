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

/** The small tables ([snapshot], no pings) and the highest ping id at the moment they were read. */
data class BackupHead(val snapshot: BackupSnapshot, val maxPingId: Long)

/**
 * Whole-database read and replace for backup and restore. Pings (about 99% of the rows) are streamed: [readHead] plus
 * [pingsPage] on the way out, [replaceAllStreaming] on the way in. [snapshot] reads every table at once and is for
 * tests and small data. A replace is one write transaction, so a failure leaves the old data untouched.
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

    /**
     * The small tables and the highest ping id, in one short read transaction (no pings). Pings are then read by
     * [pingsPage] up to [BackupHead.maxPingId], so rows written later are not in the backup.
     */
    suspend fun readHead(): BackupHead = db.withTransaction {
        BackupHead(
            snapshot = BackupSnapshot(
                sessions = dao.sessions(),
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
            ),
            maxPingId = dao.maxPingId() ?: 0L,
        )
    }

    /** One keyset page: pings with `after < id <= maxId`, ascending, at most [limit]. Not in a transaction. */
    suspend fun pingsPage(after: Long, maxId: Long, limit: Int): List<LocationPing> = dao.pingsPage(after, maxId, limit)

    /** Live visits and all locations currently stored, without loading them. */
    suspend fun counts(): BackupCounts = db.withTransaction { BackupCounts(dao.liveVisitCount(), dao.pingCount()) }

    /** [replaceAllStreaming] with the pings already in memory (tests and small data). */
    suspend fun replaceAll(snapshot: BackupSnapshot, backupCreatedAt: Long? = null) =
        replaceAllStreaming(snapshot, backupCreatedAt) { insert -> snapshot.pings.chunked(CHUNK).forEach { insert(it) } }

    /**
     * Deletes every table and inserts [snapshot]'s small tables, all in one transaction (sessions before pings, jobs
     * before blocks, chunked). [snapshot]'s own pings are ignored: [pings] is handed an `insert` function and calls it
     * with chunks, inside the same transaction and after the sessions are in, so the pings never all sit in memory.
     * If [pings] throws, the transaction rolls back and the old data stays.
     * Sessions still open afterwards (a backup taken mid-session) are closed at their last ping, or at their start
     * without pings, with [SessionEndReason.KILLED] and no Gap: the time between backup and restore was not lost on
     * this device, and crash recovery would otherwise find the open row and write one.
     * When [backupCreatedAt] is given, manual overrides still active at that time are ended at it.
     */
    suspend fun replaceAllStreaming(
        snapshot: BackupSnapshot,
        backupCreatedAt: Long? = null,
        pings: suspend (insert: suspend (List<LocationPing>) -> Unit) -> Unit,
    ) = db.withTransaction {
        deleteRecorded()
        dao.deleteScheduleTemplates()
        dao.deleteScheduleOverrides()

        // Manual overrides still active when the backup was made end then: the restored phone follows only the schedule
        // until the user acts. Ones that had already ended are untouched.
        val overrides = snapshot.manualOverrides.map {
            if (backupCreatedAt != null && (it.end == null || it.end > backupCreatedAt)) {
                it.copy(end = maxOf(backupCreatedAt, it.start), active = false)
            } else {
                it
            }
        }
        snapshot.sessions.chunked(CHUNK).forEach { dao.insertSessions(it) }
        pings { chunk -> chunk.chunked(CHUNK).forEach { dao.insertPings(it) } }
        dao.closeOpenSessions()
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

    /**
     * Removes everything the user recorded or labelled, in one transaction: pings, sessions, visits, trips, places,
     * jobs, job blocks, excluded ranges, gaps, manual overrides and the segmentation checkpoint. The schedule
     * (templates and overrides) stays, like the settings; so does the schema.
     */
    suspend fun deleteAll() = db.withTransaction { deleteRecorded() }

    /** Children first, so no cascade or FK check fires on the way out. Call inside a transaction. */
    private suspend fun deleteRecorded() {
        dao.deletePings()
        dao.deleteJobBlocks()
        dao.deleteSessions()
        dao.deleteJobs()
        dao.deletePlaces()
        dao.deleteVisits()
        dao.deleteTrips()
        dao.deleteExcludedRanges()
        dao.deleteGaps()
        dao.deleteManualOverrides()
        dao.deleteCheckpoints()
    }

    private companion object {
        const val CHUNK = 500
    }
}
