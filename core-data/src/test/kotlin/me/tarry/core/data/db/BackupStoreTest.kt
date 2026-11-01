package me.tarry.core.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupStoreTest : DbTestBase() {
    private val store get() = BackupStore(db)

    /** One row of every table, with nulls and every enum family represented. */
    private fun everything(tag: String = "a", sessionEnd: Long? = 2_000L) = BackupSnapshot(
        sessions = listOf(
            TrackingSession(1, 1_000L, sessionEnd, "Australia/Sydney", SessionTrigger.SCHEDULE, sessionEnd?.let { SessionEndReason.SCHEDULE_END }),
            TrackingSession(2, 5_000L, null, "UTC", SessionTrigger.MANUAL, null),
        ),
        pings = listOf(
            LocationPing(10, 1_100L, "Australia/Sydney", -33.8, 151.2, 5f, 1.5f, 90f, ActivityType.WALKING, PingSource.FUSED, 1),
            LocationPing(11, 1_900L, "UTC", 0.0, 0.0, 12.5f, null, null, null, PingSource.MANUAL_START, 1),
            LocationPing(12, 5_500L, "UTC", 1.0, 2.0, 3f, 0f, 0f, ActivityType.IN_VEHICLE, PingSource.GEOFENCE_EXIT, 2),
        ),
        jobs = listOf(Job("j1", "Site $tag", 0xFF112233.toInt(), archived = true, defaultBillable = false, updatedAt = 7L, deleted = false)),
        places = listOf(
            Place("p1", -33.8, 151.2, 60f, "1 Test St", "Depot", "j1", true, "p2", 1_900L, 8L, false),
            Place("p2", 1.0, 2.0, updatedAt = 9L, deleted = true),
        ),
        visits = listOf(
            Visit("v1", "p1", 1_000L, 1_800L, "UTC", "notes", true, VisitOrigin.MANUAL, 0.75f, 10L, false),
            Visit("v2", null, 1_800L, 1_900L, "UTC", origin = VisitOrigin.DETECTED, confidence = 0.5f, updatedAt = 11L, deleted = true),
        ),
        trips = listOf(Trip("t1", 1_800L, 1_900L, "UTC", "v1", "v2", 1234.5f, TripMode.VEHICLE, "n", true, 12L, false)),
        jobBlocks = listOf(JobBlock("b1", "j1", 1_000L, 1_800L, "UTC", true, "note", 13L, false)),
        excludedRanges = listOf(ExcludedRange("e1", 100L, 200L, "UTC", 14L, true)),
        gaps = listOf(Gap("g1", 1_900L, 2_000L, "UTC", GapCause.SERVICE_KILLED, 1L, 15L, false)),
        scheduleTemplates = listOf(ScheduleTemplate(0, byteArrayOf(1, 2, 3, 0, -1), 16L)),
        scheduleOverrides = listOf(ScheduleOverride("2026-10-26", byteArrayOf(9, 8), 17L)),
        manualOverrides = listOf(
            ManualOverride(1, ManualOverrideType.PAUSE, 1_000L, 2_000L, "UTC", ManualOverrideEndMode.DURATION, false),
            ManualOverride(2, ManualOverrideType.START, 3_000L, null, "UTC", ManualOverrideEndMode.UNTIL_STOPPED, true),
        ),
        checkpoints = listOf(SegmentationCheckpoint(0, 1_900L)),
    )

    @Test
    fun snapshotReturnsExactlyWhatWasStored() = runBlocking<Unit> {
        val original = everything()
        store.replaceAll(original)
        val closed = original.copy(
            sessions = original.sessions.map { if (it.end == null) it.copy(end = 5_500L, endReason = SessionEndReason.KILLED) else it },
        )
        assertThat(store.snapshot()).isEqualTo(closed)
        assertThat(store.counts()).isEqualTo(BackupCounts(visits = 1, locations = 3))
    }

    @Test
    fun replaceAllLeavesExactlyTheBackupsRows() = runBlocking<Unit> {
        store.replaceAll(everything("old"))
        val other = BackupSnapshot(
            sessions = listOf(TrackingSession(50, 10L, 20L, "UTC", SessionTrigger.MANUAL, SessionEndReason.USER_STOP)),
            pings = listOf(LocationPing(500, 15L, "UTC", 1.0, 1.0, 1f, null, null, null, PingSource.FUSED, 50)),
            jobs = listOf(Job("jx", "Other", 1, updatedAt = 1L)),
        )

        store.replaceAll(other)

        assertThat(store.snapshot()).isEqualTo(other)
    }

    @Test
    fun aFailureMidInsertRollsEverythingBack() = runBlocking<Unit> {
        val original = everything("keep")
        store.replaceAll(original)
        val before = store.snapshot()
        // The ping's session does not exist: the foreign key fails after the delete and the first inserts.
        val bad = everything("new").copy(
            pings = listOf(LocationPing(99, 1L, "UTC", 0.0, 0.0, 1f, null, null, null, PingSource.FUSED, sessionId = 404)),
        )

        assertThrows(Exception::class.java) { runBlocking { store.replaceAll(bad) } }

        assertThat(store.snapshot()).isEqualTo(before)
    }

    @Test
    fun anOpenSessionIsClosedAtItsLastPingAsKilledAndNoGapIsWritten() = runBlocking<Unit> {
        store.replaceAll(everything())

        val sessions = store.snapshot().sessions.associateBy { it.id }
        val closed = sessions.getValue(2)
        assertThat(closed.end).isEqualTo(5_500L)
        assertThat(closed.endReason).isEqualTo(SessionEndReason.KILLED)
        assertThat(db.trackingSessionDao().getOpenSession()).isNull()
        // Only the backup's own gap exists.
        assertThat(store.snapshot().gaps.map { it.id }).containsExactly("g1")
    }

    @Test
    fun anOpenSessionWithoutPingsIsClosedAtItsStart() = runBlocking<Unit> {
        val snap = BackupSnapshot(sessions = listOf(TrackingSession(7, 4_000L, null, "UTC", SessionTrigger.SCHEDULE, null)))

        store.replaceAll(snap)

        val s = store.snapshot().sessions.single()
        assertThat(s.end).isEqualTo(4_000L)
        assertThat(s.endReason).isEqualTo(SessionEndReason.KILLED)
    }
}
