package me.tarry.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger

@Entity(tableName = "tracking_session", indices = [Index("start")])
data class TrackingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Long,
    val end: Long? = null,
    val zoneId: String,
    val trigger: SessionTrigger,
    val endReason: SessionEndReason? = null,
)

@Entity(
    tableName = "location_ping",
    foreignKeys = [
        ForeignKey(
            entity = TrackingSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("timestamp"), Index("sessionId")],
)
data class LocationPing(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val zoneId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val speedMps: Float? = null,
    val bearing: Float? = null,
    val activity: ActivityType? = null,
    val source: PingSource,
    val sessionId: Long,
)

@Entity(tableName = "manual_override", indices = [Index("active")])
data class ManualOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ManualOverrideType,
    val start: Long,
    val end: Long? = null,
    val zoneId: String,
    val endMode: ManualOverrideEndMode,
    val active: Boolean,
)

@Entity(tableName = "segmentation_checkpoint")
data class SegmentationCheckpoint(
    @PrimaryKey val id: Int = 0,
    val processedUntil: Long,
)
