package me.tarry.core.data.repository

import androidx.room.withTransaction
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.dao.JobDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.dao.TagDao
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Management of the job, tag and tag-group catalog (phase 4 T3). Same contract as [TimelineEditor]: one Room
 * transaction per op that re-reads its rows by id, and failures come back as [EditResult], never exceptions.
 * Archiving a job or tag also clears it from place defaults; the returned [Snapshot] puts everything back through
 * [TimelineEditor.restore]. Archived rows stay on past visits. Catalog edits never touch segmentation state.
 */
@Singleton
class CatalogEditor @Inject constructor(
    private val db: TarryDatabase,
    private val jobs: JobDao,
    private val tags: TagDao,
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

    /** Hides the job from pickers and clears it from every place default; the undo [Snapshot] restores both. */
    suspend fun archiveJob(jobId: String): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        val affected = places.getByDefaultJob(jobId)
        val now = clock.nowMs()
        jobs.upsert(job.copy(archived = true, updatedAt = now))
        affected.forEach { places.setDefaultJob(it.id, null, now) }
        EditResult.Ok(emptySnapshot().copy(jobs = listOf(job), placeDefaultJobs = affected.associate { it.id to jobId }))
    }

    suspend fun unarchiveJob(jobId: String): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        jobs.upsert(job.copy(archived = false, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** Soft delete. [EditResult.InUse] when a live visit, trip attribution or place default still uses the job. */
    suspend fun deleteJob(jobId: String): EditResult = db.withTransaction {
        val job = liveJob(jobId) ?: return@withTransaction EditResult.NotFound
        val uses = jobs.countVisits(jobId) + jobs.countTrips(jobId) + places.getByDefaultJob(jobId).size
        if (uses > 0) return@withTransaction EditResult.InUse(uses)
        jobs.upsert(job.copy(deleted = true, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    // endregion

    // region tags

    suspend fun renameTag(tagId: String, name: String): EditResult = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction EditResult.BlankName
        val tag = liveTag(tagId) ?: return@withTransaction EditResult.NotFound
        tags.upsert(tag.copy(name = trimmed, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** Hides the tag from pickers and removes it from place defaults; the undo [Snapshot] keeps the removed links. */
    suspend fun archiveTag(tagId: String): EditResult = db.withTransaction {
        val tag = liveTag(tagId) ?: return@withTransaction EditResult.NotFound
        EditResult.Ok(archive(listOf(tag)))
    }

    suspend fun unarchiveTag(tagId: String): EditResult = db.withTransaction {
        val tag = liveTag(tagId) ?: return@withTransaction EditResult.NotFound
        tags.upsert(tag.copy(archived = false, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** Soft delete, only when no live visit, trip or place default carries the tag ([EditResult.InUse] otherwise). */
    suspend fun deleteTag(tagId: String): EditResult = db.withTransaction {
        val tag = liveTag(tagId) ?: return@withTransaction EditResult.NotFound
        val uses = tags.countVisitUses(tagId) + tags.countTripUses(tagId) + places.getDefaultTagLinksByTag(tagId).size
        if (uses > 0) return@withTransaction EditResult.InUse(uses)
        tags.upsert(tag.copy(deleted = true, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    // endregion

    // region tag groups

    /** Creates a group at the end. [colorArgb] is a `JobPalette` key, or 0 for none. */
    suspend fun createTagGroup(name: String, colorArgb: Int = 0): EditResult = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction EditResult.BlankName
        val group = TagGroup(
            name = trimmed, colorArgb = colorArgb, sortOrder = (tags.getGroups().maxOfOrNull { it.sortOrder } ?: -1) + 1,
            updatedAt = clock.nowMs(),
        )
        tags.upsertGroup(group)
        EditResult.Ok(id = group.id)
    }

    suspend fun renameTagGroup(groupId: String, name: String): EditResult = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction EditResult.BlankName
        val group = liveGroup(groupId) ?: return@withTransaction EditResult.NotFound
        tags.upsertGroup(group.copy(name = trimmed, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** [colorArgb] is a `JobPalette` key, or 0 for none. */
    suspend fun setTagGroupColour(groupId: String, colorArgb: Int): EditResult = db.withTransaction {
        val group = liveGroup(groupId) ?: return@withTransaction EditResult.NotFound
        tags.upsertGroup(group.copy(colorArgb = colorArgb, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    /** Archives every tag in the group that is not archived yet, as one undoable edit. */
    suspend fun archiveAllTags(groupId: String): EditResult = db.withTransaction {
        liveGroup(groupId) ?: return@withTransaction EditResult.NotFound
        EditResult.Ok(archive(tags.getByGroup(groupId).filterNot { it.archived }))
    }

    /** Soft delete, only when the group has no non-deleted tags ([EditResult.InUse] with their count otherwise). */
    suspend fun deleteTagGroup(groupId: String): EditResult = db.withTransaction {
        val group = liveGroup(groupId) ?: return@withTransaction EditResult.NotFound
        val remaining = tags.getByGroup(groupId).size
        if (remaining > 0) return@withTransaction EditResult.InUse(remaining)
        tags.upsertGroup(group.copy(deleted = true, updatedAt = clock.nowMs()))
        EditResult.Ok()
    }

    // endregion

    // region reorder

    /**
     * Gives the group's non-deleted tags (archived included) the order of [orderedIds]. The ids must be exactly the
     * live set, else [EditResult.NotFound] and nothing is written (a stale list from the UI). No undo.
     */
    suspend fun reorderTags(groupId: String, orderedIds: List<String>): EditResult = db.withTransaction {
        liveGroup(groupId) ?: return@withTransaction EditResult.NotFound
        val live = tags.getByGroup(groupId).associateBy { it.id }
        if (!matches(live.keys, orderedIds)) return@withTransaction EditResult.NotFound
        val now = clock.nowMs()
        orderedIds.forEachIndexed { index, id ->
            val tag = live.getValue(id)
            if (tag.sortOrder != index) tags.upsert(tag.copy(sortOrder = index, updatedAt = now))
        }
        EditResult.Ok()
    }

    /** Same contract as [reorderTags], over the live tag groups. */
    suspend fun reorderTagGroups(orderedIds: List<String>): EditResult = db.withTransaction {
        val live = tags.getGroups().associateBy { it.id }
        if (!matches(live.keys, orderedIds)) return@withTransaction EditResult.NotFound
        val now = clock.nowMs()
        orderedIds.forEachIndexed { index, id ->
            val group = live.getValue(id)
            if (group.sortOrder != index) tags.upsertGroup(group.copy(sortOrder = index, updatedAt = now))
        }
        EditResult.Ok()
    }

    // endregion

    /** Archives [toArchive] and deletes their place-default links; the snapshot holds the tags and all default tags of the affected places. */
    private suspend fun archive(toArchive: List<Tag>): Snapshot {
        val now = clock.nowMs()
        val links = toArchive.flatMap { places.getDefaultTagLinksByTag(it.id) }
        val affected = links.map { it.placeId }.distinct()
        val before = if (affected.isEmpty()) emptyList() else places.getDefaultTagsFor(affected)
        links.forEach { places.deleteDefaultTag(it.placeId, it.tagId) }
        toArchive.forEach { tags.upsert(it.copy(archived = true, updatedAt = now)) }
        return emptySnapshot().copy(tags = toArchive, placeDefaultTags = before)
    }

    private fun matches(live: Set<String>, ordered: List<String>) = ordered.size == live.size && ordered.toSet() == live

    private suspend fun liveJob(id: String): Job? = jobs.getById(id)?.takeUnless { it.deleted }
    private suspend fun liveTag(id: String): Tag? = tags.getById(id)?.takeUnless { it.deleted }
    private suspend fun liveGroup(id: String): TagGroup? = tags.getGroupById(id)?.takeUnless { it.deleted }

    private fun emptySnapshot() = Snapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
}
