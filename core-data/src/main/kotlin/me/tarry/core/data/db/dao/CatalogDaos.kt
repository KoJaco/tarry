package me.tarry.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup

@Dao
interface JobDao {
    @Upsert suspend fun upsert(job: Job)

    @Query("SELECT * FROM job WHERE id = :id")
    suspend fun getById(id: String): Job?

    @Query("SELECT * FROM job WHERE deleted = 0 ORDER BY name")
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

    @Upsert suspend fun upsertDefaultTag(link: PlaceDefaultTag)

    @Query("DELETE FROM place_default_tag WHERE placeId = :placeId AND tagId = :tagId")
    suspend fun deleteDefaultTag(placeId: String, tagId: String)

    @Query("SELECT * FROM place_default_tag WHERE placeId = :placeId")
    suspend fun getDefaultTags(placeId: String): List<PlaceDefaultTag>
}

@Dao
interface TagDao {
    @Upsert suspend fun upsertGroup(group: TagGroup)

    @Upsert suspend fun upsert(tag: Tag)

    @Query("SELECT * FROM tag_group WHERE id = :id")
    suspend fun getGroupById(id: String): TagGroup?

    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun getById(id: String): Tag?

    @Query("SELECT * FROM tag_group WHERE deleted = 0 ORDER BY sortOrder")
    fun observeGroups(): Flow<List<TagGroup>>

    @Query("SELECT * FROM tag WHERE deleted = 0 ORDER BY groupId, sortOrder")
    fun observeTags(): Flow<List<Tag>>

    @Query("SELECT * FROM tag WHERE groupId = :groupId AND deleted = 0 ORDER BY sortOrder")
    suspend fun getByGroup(groupId: String): List<Tag>

    @Query("SELECT * FROM tag_group WHERE deleted = 0 ORDER BY sortOrder")
    suspend fun getGroups(): List<TagGroup>
}
