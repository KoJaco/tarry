package me.tarry.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place

@Dao
interface JobDao {
    @Upsert suspend fun upsert(job: Job)

    /** Un-archives a live job without touching its other columns (a rename since the archive survives). */
    @Query("UPDATE job SET archived = 0, updatedAt = :updatedAt WHERE id = :id AND deleted = 0")
    suspend fun unarchive(id: String, updatedAt: Long)

    @Query("SELECT * FROM job WHERE id = :id")
    suspend fun getById(id: String): Job?

    @Query("SELECT * FROM job WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Job>>

    @Query("SELECT * FROM job WHERE deleted = 0")
    suspend fun getAllActive(): List<Job>
}

@Dao
interface PlaceDao {
    @Upsert suspend fun upsert(place: Place)

    @Query("SELECT * FROM place WHERE id = :id")
    suspend fun getById(id: String): Place?

    @Query("SELECT * FROM place WHERE deleted = 0 AND lastVisitAt >= :from AND lastVisitAt < :to ORDER BY lastVisitAt")
    suspend fun getByLastVisitInRange(from: Long, to: Long): List<Place>

    /** Every non-deleted place, merged and ignored ones included (segmentation decides what they mean). */
    @Query("SELECT * FROM place WHERE deleted = 0")
    suspend fun getAllActive(): List<Place>

    @Query("SELECT * FROM place WHERE deleted = 0 ORDER BY lastVisitAt DESC")
    fun observeAll(): Flow<List<Place>>

    /**
     * Places still waiting for a geocode lookup, most recently visited first. `address` semantics: null = not looked up
     * yet, "" (empty) = looked up and nothing found (never retried; treat as no address when displaying), else the address.
     */
    @Query("SELECT * FROM place WHERE deleted = 0 AND address IS NULL ORDER BY lastVisitAt DESC LIMIT :limit")
    suspend fun getMissingAddress(limit: Int): List<Place>

    /** Sets only the address when none is stored yet, so a concurrent edit to other columns survives. */
    @Query("UPDATE place SET address = :address, updatedAt = :updatedAt WHERE id = :id AND address IS NULL")
    suspend fun setAddressIfMissing(id: String, address: String, updatedAt: Long): Int


    /** Live places whose default job is [jobId]. */
    @Query("SELECT * FROM place WHERE deleted = 0 AND defaultJobId = :jobId")
    suspend fun getByDefaultJob(jobId: String): List<Place>

    /** Sets only the default job, so columns segmentation owns (centroid, lastVisitAt) are never overwritten. */
    @Query("UPDATE place SET defaultJobId = :jobId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setDefaultJob(id: String, jobId: String?, updatedAt: Long)

    /** Undo of a job archive: sets the default only on a live place that has none now, so a default chosen since survives. */
    @Query("UPDATE place SET defaultJobId = :jobId, updatedAt = :updatedAt WHERE id = :id AND deleted = 0 AND defaultJobId IS NULL")
    suspend fun restoreDefaultJob(id: String, jobId: String, updatedAt: Long)


    /**
     * Every place, deleted ones included, for labelling visits: frozen visits at a deleted place keep their label and
     * inherited job. Pickers and catalogs must use [observeAll] instead.
     */
    @Query("SELECT * FROM place")
    fun observeLabels(): Flow<List<Place>>

    /** Sets only the radius, so columns segmentation owns (centroid, lastVisitAt) are never overwritten. */
    @Query("UPDATE place SET radiusM = :radiusM, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setRadius(id: String, radiusM: Float, updatedAt: Long)

    /** Places (deleted tombstones included) that were merged into [id]. */
    @Query("SELECT * FROM place WHERE mergedIntoId = :id")
    suspend fun getMergedInto(id: String): List<Place>

    /** Points every place merged into [from] at [to] instead, so merge chains stay flat. */
    @Query("UPDATE place SET mergedIntoId = :to, updatedAt = :updatedAt WHERE mergedIntoId = :from")
    suspend fun repointMerged(from: String, to: String, updatedAt: Long)

    /**
     * Soft-deletes orphan places: unnamed, no defaults, not ignored, still the default radius, not a merge, last touched
     * before [cutoff] and with no live visit and no visit changed since [cutoff] (the grace period).
     */
    @Query(
        "UPDATE place SET deleted = 1, updatedAt = :now WHERE deleted = 0 AND mergedIntoId IS NULL AND name IS NULL " +
            "AND defaultJobId IS NULL AND ignored = 0 AND radiusM = :defaultRadiusM " +
            "AND updatedAt < :cutoff " +
            "AND NOT EXISTS (SELECT 1 FROM visit v WHERE v.placeId = place.id AND (v.deleted = 0 OR v.updatedAt >= :cutoff))",
    )
    suspend fun pruneOrphans(now: Long, cutoff: Long, defaultRadiusM: Float): Int
}
