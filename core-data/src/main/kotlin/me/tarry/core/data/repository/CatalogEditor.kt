package me.tarry.core.data.repository

import androidx.room.withTransaction
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.dao.JobBlockDao
import me.tarry.core.data.db.dao.JobDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.entity.Job
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Management of the job catalog (phase 4 T3). Same contract as [TimelineEditor]: one Room
 * transaction per op that re-reads its rows by id, and failures come back as [EditResult], never exceptions.
 * Archiving a job also clears it from place defaults; the returned [Snapshot] reverses only those changes, targeted, through
 * [TimelineEditor.restore]. Archived jobs stay on past job blocks. Catalog edits never touch segmentation state.
 */
@Singleton
class CatalogEditor @Inject constructor(
    private val db: TarryDatabase,
    private val jobs: JobDao,
    private val blocks: JobBlockDao,
    private val places: PlaceDao,
    private val clock: EditClock,
) {
    // region jobs

    suspend fun renameJob(jobId: String, name: String): EditResult = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction EditResult.BlankName
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        jobs.upsert(job.copy(name = trimmed, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** [colorArgb] is a `JobPalette` key (passed in as a plain int; core-data cannot see the UI theme). */
    suspend fun setJobColour(jobId: String, colorArgb: Int): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        jobs.upsert(job.copy(colorArgb = colorArgb, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** Sets [Job.defaultBillable], the initial billable flag for new blocks; existing blocks keep theirs. */
    suspend fun setJobDefaultBillable(jobId: String, billable: Boolean): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        jobs.upsert(job.copy(defaultBillable = billable, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** Hides the job from pickers and clears it from every place default; the undo [Snapshot] reverses just those two changes. */
    suspend fun archiveJob(jobId: String): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        val affected = places.getByDefaultJob(jobId)
        val now = clock.nowMs()
        jobs.upsert(job.copy(archived = true, updatedAt = now))
        affected.forEach { places.setDefaultJob(it.id, null, now) }
        EditResult.Ok(emptySnapshot().copy(archivedJobIds = listOf(jobId), placeDefaultJobs = affected.associate { it.id to jobId }))
    }

    suspend fun unarchiveJob(jobId: String): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        jobs.upsert(job.copy(archived = false, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** Soft delete. [EditResult.InUse] when a live job block or place default still uses the job. */
    suspend fun deleteJob(jobId: String): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        val uses = jobUses(jobId)
        if (uses > 0) return@withTransaction EditResult.InUse(uses)
        jobs.upsert(job.copy(deleted = true, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** How many live job blocks and place defaults use the job (what blocks [deleteJob]). Read-only, for the UI to explain a disabled Delete. */
    suspend fun jobUses(jobId: String): Int =
        blocks.countLive(jobId) + places.getByDefaultJob(jobId).size

    // endregion

    private suspend fun liveJob(id: String): Job? = jobs.getById(id)?.takeUnless { it.deleted }

    private fun emptySnapshot() = Snapshot(null, emptyList(), emptyList(), emptyList())
}
