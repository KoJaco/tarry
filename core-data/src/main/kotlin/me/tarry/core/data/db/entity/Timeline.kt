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
    indices = [Index("start"), Index("placeId"), Index("jobId")],
)
data class Visit(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val placeId: String? = null,
    val start: Long,
    val end: Long,
    val zoneId: String,
    val jobId: String? = null,
    @ColumnInfo(defaultValue = "") val freeNotes: String = "",
    val billable: Boolean? = null,
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
    val billable: Boolean? = null,
    val attributedJobId: String? = null,
    @ColumnInfo(defaultValue = "") val freeNotes: String = "",
    @ColumnInfo(defaultValue = "0") val frozen: Boolean = false,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Entity(
    tableName = "visit_tag",
    primaryKeys = ["visitId", "tagId"],
    foreignKeys = [
        ForeignKey(Visit::class, ["id"], ["visitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Tag::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class VisitTag(val visitId: String, val tagId: String)

@Entity(
    tableName = "trip_tag",
    primaryKeys = ["tripId", "tagId"],
    foreignKeys = [
        ForeignKey(Trip::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Tag::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class TripTag(val tripId: String, val tagId: String)

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
