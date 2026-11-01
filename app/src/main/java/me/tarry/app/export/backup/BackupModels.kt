package me.tarry.app.export.backup

import kotlinx.serialization.Serializable
import me.tarry.core.data.db.BackupSnapshot
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
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import java.util.Base64

/**
 * The backup file's JSON shape. DTOs mirror the entities field by field, so `:core-data` stays free of serialization.
 *
 * Compatibility rule: a field added in a later version MUST have a default, so older files still decode. A breaking
 * change bumps [BackupCodec.CURRENT_VERSION] and adds an explicit upgrade step (see [BackupCodec.upgrade]).
 */
@Serializable
data class BackupFile(
    val format: String = "",
    val formatVersion: Int = 0,
    /** UTC epoch ms. */
    val createdAt: Long = 0,
    val zoneId: String = "",
    val appVersionName: String = "",
    /** The `settings` DataStore, typed key/value pairs. */
    val settings: List<PrefEntry> = emptyList(),
    val declinedSuggestions: List<PrefEntry> = emptyList(),
    val tables: BackupTables = BackupTables(),
)

/** One DataStore preference. [type] is one of `int`, `long`, `float`, `double`, `boolean`, `string`, `stringSet`. */
@Serializable
data class PrefEntry(val key: String, val type: String, val value: String = "", val values: List<String> = emptyList())

@Serializable
data class BackupTables(
    val sessions: List<SessionDto> = emptyList(),
    val pings: List<PingDto> = emptyList(),
    val jobs: List<JobDto> = emptyList(),
    val places: List<PlaceDto> = emptyList(),
    val visits: List<VisitDto> = emptyList(),
    val trips: List<TripDto> = emptyList(),
    val jobBlocks: List<JobBlockDto> = emptyList(),
    val excludedRanges: List<ExcludedRangeDto> = emptyList(),
    val gaps: List<GapDto> = emptyList(),
    val scheduleTemplates: List<ScheduleTemplateDto> = emptyList(),
    val scheduleOverrides: List<ScheduleOverrideDto> = emptyList(),
    val manualOverrides: List<ManualOverrideDto> = emptyList(),
    val checkpoints: List<CheckpointDto> = emptyList(),
)

@Serializable
data class SessionDto(
    val id: Long,
    val start: Long,
    val end: Long? = null,
    val zoneId: String,
    val trigger: SessionTrigger,
    val endReason: SessionEndReason? = null,
)

@Serializable
data class PingDto(
    val id: Long,
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

@Serializable
data class JobDto(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val archived: Boolean = false,
    val defaultBillable: Boolean = true,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class PlaceDto(
    val id: String,
    val centroidLat: Double,
    val centroidLng: Double,
    val radiusM: Float = 75f,
    val address: String? = null,
    val name: String? = null,
    val defaultJobId: String? = null,
    val ignored: Boolean = false,
    val mergedIntoId: String? = null,
    val lastVisitAt: Long? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class VisitDto(
    val id: String,
    val placeId: String? = null,
    val start: Long,
    val end: Long,
    val zoneId: String,
    val freeNotes: String = "",
    val frozen: Boolean = false,
    val origin: VisitOrigin,
    val confidence: Float,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class TripDto(
    val id: String,
    val start: Long,
    val end: Long,
    val zoneId: String,
    val fromVisitId: String? = null,
    val toVisitId: String? = null,
    val distanceM: Float,
    val mode: TripMode,
    val freeNotes: String = "",
    val frozen: Boolean = false,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class JobBlockDto(
    val id: String,
    val jobId: String,
    val start: Long,
    val end: Long,
    val zoneId: String,
    val billable: Boolean,
    val notes: String? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class ExcludedRangeDto(
    val id: String,
    val start: Long,
    val end: Long,
    val zoneId: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class GapDto(
    val id: String,
    val start: Long,
    val end: Long,
    val zoneId: String,
    val cause: GapCause,
    val sessionId: Long? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** [slots] is Base64 of the entity's byte array. */
@Serializable
data class ScheduleTemplateDto(val id: Int = 0, val slots: String, val updatedAt: Long)

@Serializable
data class ScheduleOverrideDto(val weekStart: String, val slots: String, val updatedAt: Long)

@Serializable
data class ManualOverrideDto(
    val id: Long,
    val type: ManualOverrideType,
    val start: Long,
    val end: Long? = null,
    val zoneId: String,
    val endMode: ManualOverrideEndMode,
    val active: Boolean,
)

@Serializable
data class CheckpointDto(val id: Int = 0, val processedUntil: Long)

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
private fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

/** Entities to DTOs. */
fun BackupSnapshot.toTables() = BackupTables(
    sessions = sessions.map { SessionDto(it.id, it.start, it.end, it.zoneId, it.trigger, it.endReason) },
    pings = pings.map {
        PingDto(it.id, it.timestamp, it.zoneId, it.lat, it.lng, it.accuracyM, it.speedMps, it.bearing, it.activity, it.source, it.sessionId)
    },
    jobs = jobs.map { JobDto(it.id, it.name, it.colorArgb, it.archived, it.defaultBillable, it.updatedAt, it.deleted) },
    places = places.map {
        PlaceDto(
            it.id, it.centroidLat, it.centroidLng, it.radiusM, it.address, it.name, it.defaultJobId, it.ignored,
            it.mergedIntoId, it.lastVisitAt, it.updatedAt, it.deleted,
        )
    },
    visits = visits.map {
        VisitDto(it.id, it.placeId, it.start, it.end, it.zoneId, it.freeNotes, it.frozen, it.origin, it.confidence, it.updatedAt, it.deleted)
    },
    trips = trips.map {
        TripDto(
            it.id, it.start, it.end, it.zoneId, it.fromVisitId, it.toVisitId, it.distanceM, it.mode, it.freeNotes,
            it.frozen, it.updatedAt, it.deleted,
        )
    },
    jobBlocks = jobBlocks.map {
        JobBlockDto(it.id, it.jobId, it.start, it.end, it.zoneId, it.billable, it.notes, it.updatedAt, it.deleted)
    },
    excludedRanges = excludedRanges.map { ExcludedRangeDto(it.id, it.start, it.end, it.zoneId, it.updatedAt, it.deleted) },
    gaps = gaps.map { GapDto(it.id, it.start, it.end, it.zoneId, it.cause, it.sessionId, it.updatedAt, it.deleted) },
    scheduleTemplates = scheduleTemplates.map { ScheduleTemplateDto(it.id, it.slots.b64(), it.updatedAt) },
    scheduleOverrides = scheduleOverrides.map { ScheduleOverrideDto(it.weekStart, it.slots.b64(), it.updatedAt) },
    manualOverrides = manualOverrides.map {
        ManualOverrideDto(it.id, it.type, it.start, it.end, it.zoneId, it.endMode, it.active)
    },
    checkpoints = checkpoints.map { CheckpointDto(it.id, it.processedUntil) },
)

/** DTOs to entities. Throws [IllegalArgumentException] for a malformed Base64 schedule. */
fun BackupTables.toSnapshot() = BackupSnapshot(
    sessions = sessions.map { TrackingSession(it.id, it.start, it.end, it.zoneId, it.trigger, it.endReason) },
    pings = pings.map {
        LocationPing(it.id, it.timestamp, it.zoneId, it.lat, it.lng, it.accuracyM, it.speedMps, it.bearing, it.activity, it.source, it.sessionId)
    },
    jobs = jobs.map { Job(it.id, it.name, it.colorArgb, it.archived, it.defaultBillable, it.updatedAt, it.deleted) },
    places = places.map {
        Place(
            it.id, it.centroidLat, it.centroidLng, it.radiusM, it.address, it.name, it.defaultJobId, it.ignored,
            it.mergedIntoId, it.lastVisitAt, it.updatedAt, it.deleted,
        )
    },
    visits = visits.map {
        Visit(it.id, it.placeId, it.start, it.end, it.zoneId, it.freeNotes, it.frozen, it.origin, it.confidence, it.updatedAt, it.deleted)
    },
    trips = trips.map {
        Trip(
            it.id, it.start, it.end, it.zoneId, it.fromVisitId, it.toVisitId, it.distanceM, it.mode, it.freeNotes,
            it.frozen, it.updatedAt, it.deleted,
        )
    },
    jobBlocks = jobBlocks.map {
        JobBlock(it.id, it.jobId, it.start, it.end, it.zoneId, it.billable, it.notes, it.updatedAt, it.deleted)
    },
    excludedRanges = excludedRanges.map { ExcludedRange(it.id, it.start, it.end, it.zoneId, it.updatedAt, it.deleted) },
    gaps = gaps.map { Gap(it.id, it.start, it.end, it.zoneId, it.cause, it.sessionId, it.updatedAt, it.deleted) },
    scheduleTemplates = scheduleTemplates.map { ScheduleTemplate(it.id, it.slots.unb64(), it.updatedAt) },
    scheduleOverrides = scheduleOverrides.map { ScheduleOverride(it.weekStart, it.slots.unb64(), it.updatedAt) },
    manualOverrides = manualOverrides.map {
        ManualOverride(it.id, it.type, it.start, it.end, it.zoneId, it.endMode, it.active)
    },
    checkpoints = checkpoints.map { SegmentationCheckpoint(it.id, it.processedUntil) },
)
