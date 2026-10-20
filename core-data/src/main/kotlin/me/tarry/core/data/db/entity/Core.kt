package me.tarry.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "job", indices = [Index("name")])
data class Job(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorArgb: Int,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "place", indices = [Index("lastVisitAt")])
data class Place(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val centroidLat: Double,
    val centroidLng: Double,
    @ColumnInfo(defaultValue = "75") val radiusM: Float = 75f,
    val address: String? = null,
    val name: String? = null,
    val defaultJobId: String? = null,
    val defaultBillable: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val ignored: Boolean = false,
    val mergedIntoId: String? = null,
    val lastVisitAt: Long? = null,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "tag_group")
data class TagGroup(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "tag", indices = [Index("groupId")])
data class Tag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val groupId: String,
    val name: String,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(
    tableName = "place_default_tag",
    primaryKeys = ["placeId", "tagId"],
    foreignKeys = [
        ForeignKey(Place::class, ["id"], ["placeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Tag::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class PlaceDefaultTag(val placeId: String, val tagId: String)
