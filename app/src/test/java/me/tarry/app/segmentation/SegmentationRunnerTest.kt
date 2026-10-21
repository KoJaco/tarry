package me.tarry.app.segmentation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeWindowChecker
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.ExcludedRange
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.VisitOrigin
import me.tarry.segmentation.SegmentationParams
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The runner against a real in-memory database: ids stay stable across runs and user data is never clobbered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SegmentationRunnerTest {
    private lateinit var db: TarryDatabase
    private lateinit var runner: SegmentationRunner
    private var sessionId = 0L

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val siteA = -33.8700 to 151.2000
    private val siteB = -33.9000 to 151.2500

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runner = SegmentationRunner(
            SegmentationStore(db), db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(),
            db.placeDao(), db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao(), FakeWindowChecker(inside = true),
            SegmentationParams(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ping(timeMs: Long, site: Pair<Double, Double>) = LocationPing(
        timestamp = timeMs, zoneId = "Australia/Sydney", lat = site.first, lng = site.second, accuracyM = 10f,
        source = PingSource.FUSED, sessionId = sessionId,
    )

    /** 30 min at A, a 10 min drive, then 30 min at B (still there at the end). */
    private suspend fun insertTwoSiteDay() {
        sessionId = db.trackingSessionDao().upsert(
            TrackingSession(start = t0 - minute, zoneId = "Australia/Sydney", trigger = SessionTrigger.SCHEDULE),
        )
        val pings = mutableListOf<LocationPing>()
        for (k in 0..10) pings += ping(t0 + k * 3 * minute, siteA)
        for (j in 1..9) {
            val f = j / 10.0
            pings += ping(
                t0 + (30 + j) * minute,
                (siteA.first + (siteB.first - siteA.first) * f) to (siteA.second + (siteB.second - siteA.second) * f),
            )
        }
        pings += stayAtB(40, 70)
        db.locationPingDao().upsertAll(pings)
    }

    private fun stayAtB(fromMin: Int, toMin: Int) = (fromMin..toMin step 3).map { ping(t0 + it * minute, siteB) }

    private val now1 get() = t0 + 72 * minute

    private suspend fun visits() = db.visitDao().getInRange(0, Long.MAX_VALUE)

    private suspend fun trips() = db.tripDao().getInRange(0, Long.MAX_VALUE)

    @Test
    fun twoSiteDayCreatesVisitsTripPlacesAndAdvancesCheckpoint() = runBlocking<Unit> {
        insertTwoSiteDay()

        runner.run(now1)

        val visits = visits()
        assertThat(visits).hasSize(2)
        assertThat(visits.map { it.origin }).containsExactly(VisitOrigin.DETECTED, VisitOrigin.DETECTED)
        assertThat(visits.all { it.placeId != null && !it.frozen && it.zoneId == "Australia/Sydney" }).isTrue()
        val trip = trips().single()
        assertThat(trip.fromVisitId).isEqualTo(visits[0].id)
        assertThat(trip.toVisitId).isEqualTo(visits[1].id)
        assertThat(db.placeDao().getAllActive()).hasSize(2)
        // The stay at B is still open, so the next run restarts where it began.
        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(visits[1].start)
    }

    @Test
    fun rerunWithTheSameDataKeepsIdsAndWritesNothing() = runBlocking<Unit> {
        insertTwoSiteDay()
        runner.run(now1)
        val visitsBefore = visits()
        val tripsBefore = trips()
        val placesBefore = db.placeDao().getAllActive().sortedBy { it.id }

        runner.run(now1 + 2 * minute)

        assertThat(visits()).isEqualTo(visitsBefore)
        assertThat(trips()).isEqualTo(tripsBefore)
        assertThat(db.placeDao().getAllActive().sortedBy { it.id }).isEqualTo(placesBefore)
    }

    @Test
    fun laterPingsExtendTheOngoingVisitUnderTheSameId() = runBlocking<Unit> {
        insertTwoSiteDay()
        runner.run(now1)
        val ongoing = visits().last()

        db.locationPingDao().upsertAll(stayAtB(73, 85))
        val later = t0 + 87 * minute
        runner.run(later)

        val after = visits()
        assertThat(after).hasSize(2)
        assertThat(after.last().id).isEqualTo(ongoing.id)
        assertThat(after.last().end).isGreaterThan(ongoing.end)
        assertThat(after.last().updatedAt).isEqualTo(later)
        assertThat(db.placeDao().getAllActive()).hasSize(2)
    }

    @Test
    fun longTripKeepsItsOriginWhenALaterRunWidensIntoIt() = runBlocking<Unit> {
        val siteC = -34.2000 to 151.6000
        sessionId = db.trackingSessionDao().upsert(
            TrackingSession(start = t0 - minute, zoneId = "Australia/Sydney", trigger = SessionTrigger.SCHEDULE),
        )
        val pings = mutableListOf<LocationPing>()
        for (k in 0..10) pings += ping(t0 + k * 3 * minute, siteA)
        // A 45 minute drive: longer than the 30 minute overlap, so the second run starts inside the trip.
        for (j in 1..44) {
            val f = j / 45.0
            pings += ping(
                t0 + (30 + j) * minute,
                (siteA.first + (siteC.first - siteA.first) * f) to (siteA.second + (siteC.second - siteA.second) * f),
            )
        }
        pings += (75..100 step 3).map { ping(t0 + it * minute, siteC) }
        db.locationPingDao().upsertAll(pings)
        runner.run(t0 + 101 * minute)
        val tripBefore = trips().single()
        assertThat(tripBefore.fromVisitId).isNotNull()

        db.locationPingDao().upsertAll((103..115 step 3).map { ping(t0 + it * minute, siteC) })
        runner.run(t0 + 117 * minute)

        val tripAfter = trips().single()
        assertThat(tripAfter.id).isEqualTo(tripBefore.id)
        assertThat(tripAfter.fromVisitId).isEqualTo(tripBefore.fromVisitId)
        assertThat(tripAfter.toVisitId).isNotNull()
        assertThat(tripAfter.distanceM).isWithin(1f).of(tripBefore.distanceM)
        assertThat(visits()).hasSize(2)
    }

    @Test
    fun frozenVisitIsUntouchedAndNothingOverlapsIt() = runBlocking<Unit> {
        insertTwoSiteDay()
        val frozen = Visit(
            id = "frozen", placeId = null, start = t0, end = t0 + 30 * minute, zoneId = "Australia/Sydney", jobId = "job",
            freeNotes = "mine", frozen = true, origin = VisitOrigin.MANUAL, confidence = 1f, updatedAt = 5,
        )
        db.visitDao().upsert(frozen)

        runner.run(now1)

        assertThat(db.visitDao().getById("frozen")).isEqualTo(frozen)
        val others = visits().filter { it.id != "frozen" }
        assertThat(others).isNotEmpty()
        assertThat(others.none { it.start < frozen.end && it.end > frozen.start }).isTrue()
        assertThat(trips().none { it.start < frozen.end && it.end > frozen.start }).isTrue()
    }

    @Test
    fun aVisitThatDisappearsIsSoftDeletedNotRemoved() = runBlocking<Unit> {
        insertTwoSiteDay()
        runner.run(now1)
        val (a, b) = visits()

        // The user deletes B: its time becomes an excluded range, so segmentation no longer finds the stay.
        db.excludedRangeDao().upsert(
            ExcludedRange(start = b.start - minute, end = b.end + minute, zoneId = "Australia/Sydney", updatedAt = 1),
        )
        runner.run(now1 + 2 * minute)

        val gone = db.visitDao().getById(b.id)!!
        assertThat(gone.deleted).isTrue()
        assertThat(gone.updatedAt).isEqualTo(now1 + 2 * minute)
        assertThat(visits().map { it.id }).containsExactly(a.id)
    }

    @Test
    fun placeDefaultsApplyToNewVisitsOnly() = runBlocking<Unit> {
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "Group", colorArgb = 0, sortOrder = 0, updatedAt = 0))
        db.tagDao().upsert(Tag(id = "tag", groupId = "g", name = "Tag", sortOrder = 0, updatedAt = 0))
        db.placeDao().upsert(
            Place(
                id = "placeA", centroidLat = siteA.first, centroidLng = siteA.second, defaultJobId = "job1",
                defaultBillable = true, updatedAt = 0,
            ),
        )
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("placeA", "tag"))
        insertTwoSiteDay()

        runner.run(now1)

        val visitA = visits().first()
        assertThat(visitA.placeId).isEqualTo("placeA")
        assertThat(visitA.jobId).isEqualTo("job1")
        assertThat(visitA.billable).isTrue()
        assertThat(db.visitDao().getTags(visitA.id).map { it.tagId }).containsExactly("tag")
        assertThat(visits().last().jobId).isNull()

        // The user clears the job and the tag; a later run reuses the visit and must not re-apply the defaults.
        db.visitDao().upsert(visitA.copy(jobId = null, billable = null))
        db.visitDao().deleteTag(visitA.id, "tag")
        runner.run(now1 + 2 * minute)

        val reused = db.visitDao().getById(visitA.id)!!
        assertThat(reused.deleted).isFalse()
        assertThat(reused.jobId).isNull()
        assertThat(reused.billable).isNull()
        assertThat(db.visitDao().getTags(visitA.id)).isEmpty()
    }

    @Test
    fun healthGapsAreNeverTouched() = runBlocking<Unit> {
        insertTwoSiteDay()
        val health = Gap(
            id = "health", start = t0 + 5 * minute, end = t0 + 8 * minute, zoneId = "Australia/Sydney",
            cause = GapCause.SERVICE_KILLED, updatedAt = 3,
        )
        db.gapDao().upsert(health)

        runner.run(now1)
        runner.run(now1 + 2 * minute)

        assertThat(db.gapDao().getById("health")).isEqualTo(health)
    }

    @Test
    fun noSessionsSetsTheCheckpointAndReturns() = runBlocking<Unit> {
        runner.run(now1)

        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(now1)
        assertThat(visits()).isEmpty()
        assertThat(trips()).isEmpty()
        assertThat(db.placeDao().getAllActive()).isEmpty()
    }
}
