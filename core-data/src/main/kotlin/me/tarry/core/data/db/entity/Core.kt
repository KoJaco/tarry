package me.tarry.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "job", indices = [Index("name")])
data class Job(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorArgb: Int,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** Initial billable flag for new [JobBlock]s of this job. */
    @ColumnInfo(defaultValue = "1") val defaultBillable: Boolean = true,
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
    @ColumnInfo(defaultValue = "0") val ignored: Boolean = false,
    val mergedIntoId: String? = null,
    val lastVisitAt: Long? = null,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
