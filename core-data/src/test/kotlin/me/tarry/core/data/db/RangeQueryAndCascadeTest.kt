package me.tarry.core.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.dao.PingPosition
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Query window used throughout: [1000, 2000). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RangeQueryAndCascadeTest : DbTestBase() {

    private fun visit(id: String, start: Long, end: Long, deleted: Boolean = false) = Visit(
        id = id, start = start, end = end, zoneId = "UTC", origin = VisitOrigin.DETECTED,
        confidence = 1f, updatedAt = 0, deleted = deleted,
    )

    private fun trip(id: String, start: Long, end: Long, deleted: Boolean = false) = Trip(
        id = id, start = start, end = end, zoneId = "UTC", distanceM = 1f, mode = TripMode.VEHICLE,
        updatedAt = 0, deleted = deleted,
    )

    private fun gap(id: String, start: Long, end: Long, deleted: Boolean = false) = Gap(
        id = id, start = start, end = end, zoneId = "UTC", cause = GapCause.NO_FIX, updatedAt = 0, deleted = deleted,
    )

    // Inserted out of order on purpose. "early" and "deleted" share a start; only "early" may appear.
    private val visits = listOf(
        visit("late", 1500, 1600),
        visit("deleted", 1100, 1200, deleted = true),
        visit("early", 1100, 1200),
        visit("straddleStart", 900, 1100),
        visit("straddleEnd", 1900, 2100),
        visit("before", 100, 200),
        visit("after", 3000, 3100),
        visit("endsAtFrom", 500, 1000),
        visit("startsAtTo", 2000, 2500),
    )
    private val expectedIds = listOf("straddleStart", "early", "late", "straddleEnd")

    @Test
    fun visitObserveInRange_filtersAndOrders() = runTest {
        db.visitDao().upsertAll(visits)
        val ids = db.visitDao().observeInRange(1000, 2000).first().map { it.id }
        assertThat(ids).containsExactlyElementsIn(expectedIds).inOrder()
    }

    @Test
    fun visitGetInRange_excludesDeleted() = runTest {
        db.visitDao().upsertAll(visits)
        val ids = db.visitDao().getInRange(1000, 2000).map { it.id }
        assertThat(ids).containsExactlyElementsIn(expectedIds).inOrder()
        assertThat(ids).doesNotContain("deleted")
    }

    @Test
    fun visitObserveInRange_reflectsSoftDelete() = runTest {
        db.visitDao().upsertAll(visits)
        db.visitDao().upsert(visit("early", 1100, 1200, deleted = true))
        val ids = db.visitDao().observeInRange(1000, 2000).first().map { it.id }
        assertThat(ids).doesNotContain("early")
    }

    @Test
    fun tripObserveAndGetInRange_filterAndOrder() = runTest {
        db.tripDao().upsertAll(
            listOf(
                trip("late", 1500, 1600),
                trip("deleted", 1100, 1200, deleted = true),
                trip("early", 1100, 1200),
                trip("straddleStart", 900, 1100),
                trip("straddleEnd", 1900, 2100),
                trip("before", 100, 200),
                trip("after", 3000, 3100),
            ),
        )
        assertThat(db.tripDao().observeInRange(1000, 2000).first().map { it.id })
            .containsExactlyElementsIn(expectedIds).inOrder()
        assertThat(db.tripDao().getInRange(1000, 2000).map { it.id })
            .containsExactlyElementsIn(expectedIds).inOrder()
    }

    @Test
    fun gapObserveAndGetInRange_filterAndOrder() = runTest {
        db.gapDao().upsertAll(
            listOf(
                gap("late", 1500, 1600),
                gap("deleted", 1100, 1200, deleted = true),
                gap("early", 1100, 1200),
                gap("straddleStart", 900, 1100),
                gap("straddleEnd", 1900, 2100),
                gap("before", 100, 200),
                gap("after", 3000, 3100),
            ),
        )
        assertThat(db.gapDao().observeInRange(1000, 2000).first().map { it.id })
            .containsExactlyElementsIn(expectedIds).inOrder()
        assertThat(db.gapDao().getInRange(1000, 2000).map { it.id })
            .containsExactlyElementsIn(expectedIds).inOrder()
    }

    @Test
    fun deletingSession_cascadesToPings() = runTest {
        val s1 = db.trackingSessionDao().upsert(TrackingSession(start = 0, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE))
        val s2 = db.trackingSessionDao().upsert(TrackingSession(start = 0, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        fun ping(ts: Long, session: Long) = LocationPing(
            timestamp = ts, zoneId = "UTC", lat = 0.0, lng = 0.0, accuracyM = 1f,
            source = PingSource.FUSED, sessionId = session,
        )
        db.locationPingDao().upsertAll(listOf(ping(1, s1), ping(2, s1), ping(3, s2)))

        db.openHelper.writableDatabase.execSQL("DELETE FROM tracking_session WHERE id = $s1")

        assertThat(db.locationPingDao().getBySession(s1)).isEmpty()
        assertThat(db.locationPingDao().getBySession(s2)).hasSize(1)
    }

    @Test
    fun pingObservePositions_isHalfOpenAndOrdered() = runTest {
        val s = db.trackingSessionDao().upsert(TrackingSession(start = 0, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE))
        fun ping(ts: Long, lat: Double) = LocationPing(
            timestamp = ts, zoneId = "UTC", lat = lat, lng = 151.0, accuracyM = 5f,
            source = PingSource.FUSED, sessionId = s,
        )
        db.locationPingDao().upsertAll(listOf(ping(2000, 3.0), ping(1500, 2.0), ping(1000, 1.0), ping(999, 0.0)))

        val positions = db.locationPingDao().observePositions(1000, 2000).first()

        assertThat(positions).containsExactly(
            PingPosition(1000, 1.0, 151.0, 5f),
            PingPosition(1500, 2.0, 151.0, 5f),
        ).inOrder()
    }


    @Test
    fun hardDeletingJob_cascadesToItsBlocks_butKeepsOthers() = runTest {
        db.jobDao().upsert(Job(id = "j1", name = "A", colorArgb = 1, updatedAt = 0))
        db.jobDao().upsert(Job(id = "j2", name = "B", colorArgb = 2, updatedAt = 0))
        db.jobBlockDao().upsert(JobBlock(id = "b1", jobId = "j1", start = 1, end = 2, zoneId = "UTC", billable = true, updatedAt = 0))
        db.jobBlockDao().upsert(JobBlock(id = "b2", jobId = "j2", start = 1, end = 2, zoneId = "UTC", billable = true, updatedAt = 0))

        db.openHelper.writableDatabase.execSQL("DELETE FROM job WHERE id = 'j1'")

        assertThat(db.jobBlockDao().getById("b1")).isNull()
        assertThat(db.jobBlockDao().getById("b2")).isNotNull()
    }
}
