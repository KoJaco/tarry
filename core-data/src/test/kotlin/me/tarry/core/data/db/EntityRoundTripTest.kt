package me.tarry.core.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
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
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One insert/read round-trip per entity (17), covering enums, nulls and ByteArrays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EntityRoundTripTest : DbTestBase() {

    @Test
    fun trackingSession_fullAndOpen() = runTest {
        val dao = db.trackingSessionDao()
        val full = TrackingSession(
            start = 1_000, end = 2_000, zoneId = "Australia/Sydney",
            trigger = SessionTrigger.MANUAL, endReason = SessionEndReason.PERMISSION_LOST,
        )
        val open = TrackingSession(start = 3_000, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE)
        val fullId = dao.upsert(full)
        val openId = dao.upsert(open)

        assertThat(dao.getById(fullId)).isEqualTo(full.copy(id = fullId))
        val readOpen = dao.getById(openId)!!
        assertThat(readOpen).isEqualTo(open.copy(id = openId))
        assertThat(readOpen.end).isNull()
        assertThat(readOpen.endReason).isNull()
        assertThat(dao.getOpenSession()).isEqualTo(readOpen)
    }

    @Test
    fun locationPing_fullAndNullable() = runTest {
        val sessionId = db.trackingSessionDao()
            .upsert(TrackingSession(start = 0, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE))
        val dao = db.locationPingDao()
        val full = LocationPing(
            timestamp = 10, zoneId = "UTC", lat = -33.8688, lng = 151.2093, accuracyM = 12.5f,
            speedMps = 3.25f, bearing = 90f, activity = ActivityType.ON_BICYCLE,
            source = PingSource.GEOFENCE_EXIT, sessionId = sessionId,
        )
        val sparse = LocationPing(
            timestamp = 20, zoneId = "UTC", lat = 1.0, lng = -2.0, accuracyM = 5f,
            source = PingSource.FUSED, sessionId = sessionId,
        )
        val fullId = dao.upsert(full)
        val sparseId = dao.upsert(sparse)

        assertThat(dao.getById(fullId)).isEqualTo(full.copy(id = fullId))
        val readSparse = dao.getById(sparseId)!!
        assertThat(readSparse).isEqualTo(sparse.copy(id = sparseId))
        assertThat(readSparse.speedMps).isNull()
        assertThat(readSparse.bearing).isNull()
        assertThat(readSparse.activity).isNull()
    }

    @Test
    fun job() = runTest {
        val job = Job(id = "j1", name = "Acme", colorArgb = 0xFF112233.toInt(), archived = true, defaultBillable = false, updatedAt = 5, deleted = true)
        db.jobDao().upsert(job)
        assertThat(db.jobDao().getById("j1")).isEqualTo(job)
    }

    @Test
    fun jobBlock_fullAndDefaults() = runTest {
        val dao = db.jobBlockDao()
        db.jobDao().upsert(Job(id = "j1", name = "Acme", colorArgb = 1, updatedAt = 1))
        val full = JobBlock(
            id = "b1", jobId = "j1", start = 10, end = 20, zoneId = "Europe/London", billable = true,
            notes = "site visit", updatedAt = 5, deleted = true,
        )
        val minimal = JobBlock(id = "b2", jobId = "j1", start = 1, end = 2, zoneId = "UTC", billable = false, updatedAt = 3)
        dao.upsert(full)
        dao.upsert(minimal)

        assertThat(dao.getById("b1")).isEqualTo(full)
        val read = dao.getById("b2")!!
        assertThat(read).isEqualTo(minimal)
        assertThat(read.notes).isNull()
        assertThat(read.deleted).isFalse()
    }

    @Test
    fun place_fullAndDefaults() = runTest {
        val dao = db.placeDao()
        val full = Place(
            id = "p1", centroidLat = -33.0, centroidLng = 151.0, radiusM = 120f, address = "1 Main St",
            name = "Office", defaultJobId = "j1", ignored = true,
            mergedIntoId = "p2", lastVisitAt = 99, updatedAt = 100, deleted = true,
        )
        val minimal = Place(id = "p2", centroidLat = 1.0, centroidLng = 2.0, updatedAt = 1)
        dao.upsert(full)
        dao.upsert(minimal)

        assertThat(dao.getById("p1")).isEqualTo(full)
        val read = dao.getById("p2")!!
        assertThat(read).isEqualTo(minimal)
        assertThat(read.radiusM).isEqualTo(75f)
        assertThat(read.address).isNull()
        assertThat(read.ignored).isFalse()
    }

    @Test
    fun visit_fullAndDefaults() = runTest {
        val dao = db.visitDao()
        val full = Visit(
            id = "v1", placeId = "p1", start = 100, end = 200, zoneId = "Europe/London",
            freeNotes = "notes", frozen = true, origin = VisitOrigin.MERGED,
            confidence = 0.75f, updatedAt = 300, deleted = true,
        )
        val minimal = Visit(id = "v2", start = 1, end = 2, zoneId = "UTC", origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 3)
        dao.upsert(full)
        dao.upsert(minimal)

        assertThat(dao.getById("v1")).isEqualTo(full)
        val read = dao.getById("v2")!!
        assertThat(read).isEqualTo(minimal)
        assertThat(read.placeId).isNull()
        assertThat(read.freeNotes).isEmpty()
        assertThat(read.frozen).isFalse()
    }

    @Test
    fun trip_fullAndDefaults() = runTest {
        val dao = db.tripDao()
        val full = Trip(
            id = "tr1", start = 1, end = 2, zoneId = "UTC", fromVisitId = "v1", toVisitId = "v2",
            distanceM = 1234.5f, mode = TripMode.WALK,
            freeNotes = "n", frozen = true, updatedAt = 9, deleted = true,
        )
        val minimal = Trip(id = "tr2", start = 1, end = 2, zoneId = "UTC", distanceM = 0f, mode = TripMode.UNKNOWN, updatedAt = 1)
        dao.upsert(full)
        dao.upsert(minimal)

        assertThat(dao.getById("tr1")).isEqualTo(full)
        val read = dao.getById("tr2")!!
        assertThat(read).isEqualTo(minimal)
        assertThat(read.fromVisitId).isNull()
    }

    @Test
    fun excludedRange() = runTest {
        val r = ExcludedRange(id = "e1", start = 10, end = 20, zoneId = "Australia/Sydney", updatedAt = 5, deleted = true)
        db.excludedRangeDao().upsert(r)
        assertThat(db.excludedRangeDao().getById("e1")).isEqualTo(r)
        assertThat(db.excludedRangeDao().getById("e1")!!.zoneId).isEqualTo("Australia/Sydney")
    }

    @Test
    fun gap_everyCauseRoundTrips() = runTest {
        val dao = db.gapDao()
        val gaps = GapCause.entries.mapIndexed { i, cause ->
            Gap(
                id = "g$i", start = 1, end = 2, zoneId = "Australia/Sydney", cause = cause,
                sessionId = if (i % 2 == 0) 42L else null, updatedAt = 1, deleted = i == 0,
            )
        }
        dao.upsertAll(gaps)
        gaps.forEach {
            assertThat(dao.getById(it.id)).isEqualTo(it)
            assertThat(dao.getById(it.id)!!.zoneId).isEqualTo("Australia/Sydney")
        }
        assertThat(gaps).hasSize(6)
    }

    @Test
    fun scheduleTemplate_byteArray() = runTest {
        val t = ScheduleTemplate(slots = Schedule.MON_FRI_7_TO_17.toBytes(), updatedAt = 11)
        db.scheduleDao().upsertTemplate(t)
        val read = db.scheduleDao().getTemplate()!!
        assertThat(read).isEqualTo(t)
        assertThat(read.id).isEqualTo(0)
        assertThat(Schedule.fromBytes(read.slots)).isEqualTo(Schedule.MON_FRI_7_TO_17)
    }

    @Test
    fun scheduleOverride_byteArray() = runTest {
        val o = ScheduleOverride(weekStart = "2026-10-19", slots = Schedule.EMPTY.toBytes(), updatedAt = 12)
        db.scheduleDao().upsertOverride(o)
        assertThat(db.scheduleDao().getOverride("2026-10-19")).isEqualTo(o)
        assertThat(db.scheduleDao().getOverride("2026-10-26")).isNull()
    }

    @Test
    fun manualOverride_openAndClosed() = runTest {
        val dao = db.manualOverrideDao()
        val closed = ManualOverride(type = ManualOverrideType.PAUSE, start = 1, end = 5, zoneId = "Australia/Sydney", endMode = ManualOverrideEndMode.DURATION, active = false)
        val open = ManualOverride(type = ManualOverrideType.START, start = 10, zoneId = "Australia/Perth", endMode = ManualOverrideEndMode.UNTIL_STOPPED, active = true)
        val closedId = dao.upsert(closed)
        val openId = dao.upsert(open)

        assertThat(dao.getById(closedId)).isEqualTo(closed.copy(id = closedId))
        val readOpen = dao.getById(openId)!!
        assertThat(readOpen).isEqualTo(open.copy(id = openId))
        assertThat(readOpen.end).isNull()
        assertThat(dao.getById(closedId)!!.zoneId).isEqualTo("Australia/Sydney")
        assertThat(readOpen.zoneId).isEqualTo("Australia/Perth")
    }

    @Test
    fun segmentationCheckpoint() = runTest {
        val dao = db.segmentationCheckpointDao()
        assertThat(dao.getById()).isNull()
        dao.upsert(SegmentationCheckpoint(processedUntil = 555))
        assertThat(dao.getById()).isEqualTo(SegmentationCheckpoint(id = 0, processedUntil = 555))
        dao.upsert(SegmentationCheckpoint(processedUntil = 777))
        assertThat(dao.getById()!!.processedUntil).isEqualTo(777)
    }
}
