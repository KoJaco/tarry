package me.tarry.app.export.backup

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
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Synthetic data only. Two rows of every table, and every field takes a different value in the two rows (nulls, both
 * booleans, different enums, edge numbers such as `Float.MAX_VALUE`, the accuracy sentinel in `PlayLocationClient`), so
 * a mapping that drops a field and falls back to a DTO default is caught by the round trip.
 * `BackupCodecTest.everyEntityPropertyTakesAtLeastTwoDifferentValuesInTheFixture` enforces the variety.
 */
fun everythingSnapshot(tag: String = "a", openSession: Boolean = false) = BackupSnapshot(
    sessions = listOf(
        TrackingSession(1, 1_000L, 2_000L, "Australia/Sydney", SessionTrigger.SCHEDULE, SessionEndReason.SCHEDULE_END),
        TrackingSession(2, 5_000L, if (openSession) null else 6_000L, "UTC", SessionTrigger.MANUAL, if (openSession) null else SessionEndReason.USER_STOP),
    ),
    pings = listOf(
        LocationPing(10, 1_100L, "Australia/Sydney", -33.8688, 151.2093, 5.25f, 1.5f, 90f, ActivityType.WALKING, PingSource.FUSED, 1),
        LocationPing(11, 1_900L, "UTC", 0.0, 0.0, 12.5f, null, null, null, PingSource.MANUAL_START, 1),
        LocationPing(12, 5_500L, "Europe/Paris", 1.0, -2.0, Float.MAX_VALUE, 0f, 0f, ActivityType.IN_VEHICLE, PingSource.GEOFENCE_EXIT, 2),
    ),
    jobs = listOf(
        Job("j1", "Site $tag é“", 0xFF112233.toInt(), archived = true, defaultBillable = false, updatedAt = 7L, deleted = false),
        Job("j2", "Other", 5, archived = false, defaultBillable = true, updatedAt = 70L, deleted = true),
    ),
    places = listOf(
        Place("p1", -33.8, 151.2, 60f, "1 Test St", "Depot", "j1", true, "p2", 1_900L, 8L, false),
        Place("p2", 1.0, 2.0, updatedAt = 9L, deleted = true),
    ),
    visits = listOf(
        Visit("v1", "p1", 1_000L, 1_800L, "UTC", "notes\nline", true, VisitOrigin.MANUAL, 0.75f, 10L, false),
        Visit("v2", null, 1_800L, 1_900L, "Australia/Sydney", origin = VisitOrigin.DETECTED, confidence = 0.5f, updatedAt = 11L, deleted = true),
    ),
    trips = listOf(
        Trip("t1", 1_800L, 1_900L, "UTC", "v1", "v2", 1234.5f, TripMode.VEHICLE, "n", true, 12L, false),
        Trip("t2", 2_000L, 2_100L, "Australia/Sydney", null, null, 0f, TripMode.WALK, "", false, 120L, true),
    ),
    jobBlocks = listOf(
        JobBlock("b1", "j1", 1_000L, 1_800L, "UTC", true, "note", 13L, false),
        JobBlock("b2", "j2", 2_000L, 2_500L, "Australia/Sydney", false, null, 130L, true),
    ),
    excludedRanges = listOf(
        ExcludedRange("e1", 100L, 200L, "UTC", 14L, true),
        ExcludedRange("e2", 300L, 400L, "Australia/Sydney", 140L, false),
    ),
    gaps = listOf(
        Gap("g1", 1_900L, 2_000L, "UTC", GapCause.SERVICE_KILLED, 1L, 15L, false),
        Gap("g2", 2_100L, 2_200L, "Australia/Sydney", GapCause.NO_FIX, null, 150L, true),
    ),
    scheduleTemplates = listOf(
        ScheduleTemplate(0, byteArrayOf(1, 2, 3, 0, -1), 16L),
        ScheduleTemplate(1, byteArrayOf(7), 160L),
    ),
    scheduleOverrides = listOf(
        ScheduleOverride("2026-10-26", byteArrayOf(9, 8), 17L),
        ScheduleOverride("2026-11-02", byteArrayOf(), 170L),
    ),
    manualOverrides = listOf(
        ManualOverride(1, ManualOverrideType.PAUSE, 1_000L, 2_000L, "UTC", ManualOverrideEndMode.DURATION, false),
        ManualOverride(2, ManualOverrideType.START, 3_000L, null, "Australia/Sydney", ManualOverrideEndMode.UNTIL_STOPPED, true),
    ),
    checkpoints = listOf(SegmentationCheckpoint(0, 1_900L), SegmentationCheckpoint(1, 2_500L)),
)

fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

fun gzip(text: String): ByteArray = gzip(text.toByteArray(Charsets.UTF_8))

fun encodeFile(file: BackupFile): ByteArray = ByteArrayOutputStream().also { BackupCodec.encode(file, it) }.toByteArray()

fun fileOf(snapshot: BackupSnapshot, version: Int = BackupCodec.CURRENT_VERSION) = BackupFile(
    format = BackupCodec.FORMAT,
    formatVersion = version,
    createdAt = 1_700_000_000_000L,
    zoneId = "UTC",
    appVersionName = "test",
    tables = snapshot.toTables(),
)
