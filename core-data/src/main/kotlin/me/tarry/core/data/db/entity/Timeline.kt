package me.tarry.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import java.util.UUID

@Entity(
    tableName = "visit",
    indices = [Index("start"), Index("placeId")],
)
data class Visit(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val placeId: String? = null,
    val start: Long,
    val end: Long,
    val zoneId: String,
    @ColumnInfo(defaultValue = "") val freeNotes: String = "",
    @ColumnInfo(defaultValue = "0") val frozen: Boolean = false,
    val origin: VisitOrigin,
    val confidence: Float,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "trip", indices = [Index("start")])
data class Trip(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val start: Long,
    val end: Long,
    val zoneId: String,
    val fromVisitId: String? = null,
    val toVisitId: String? = null,
    val distanceM: Float,
    val mode: TripMode,
    @ColumnInfo(defaultValue = "") val freeNotes: String = "",
    @ColumnInfo(defaultValue = "0") val frozen: Boolean = false,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/**
 * A span of time worked on a job, independent of the visits and trips it covers. [start]/[end] are UTC epoch ms,
 * [zoneId] the capture time zone. Soft-deleted via [deleted]; the cascade only matters if a job row is ever hard-deleted.
 */
@Entity(
    tableName = "job_block",
    foreignKeys = [ForeignKey(Job::class, ["id"], ["jobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("start"), Index("jobId")],
)
data class JobBlock(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val jobId: String,
    val start: Long,
    val end: Long,
    val zoneId: String,
    val billable: Boolean,
    val notes: String? = null,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "excluded_range", indices = [Index("start")])
data class ExcludedRange(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val start: Long,
    val end: Long,
    val zoneId: String,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(tableName = "gap", indices = [Index("start")])
data class Gap(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val start: Long,
    val end: Long,
    val zoneId: String,
    val cause: GapCause,
    val sessionId: Long? = null,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
