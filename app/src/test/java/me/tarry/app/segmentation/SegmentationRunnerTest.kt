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
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.EditClock
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.TimelineEditor
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
    fun tripDoesNotKeepAnOriginVisitThatThisRunSoftDeleted() = runBlocking<Unit> {
        val siteC = -34.2000 to 151.6000
        sessionId = db.trackingSessionDao().upsert(
            TrackingSession(start = t0 - minute, zoneId = "Australia/Sydney", trigger = SessionTrigger.SCHEDULE),
        )
        val pings = mutableListOf<LocationPing>()
        for (k in 0..10) pings += ping(t0 + k * 3 * minute, siteA)
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
        val origin = trips().single().fromVisitId!!

        // The origin stay's pings go away, so the next run no longer detects that visit.
        db.locationPingDao().deleteOlderThan(t0 + 30 * minute)
        db.locationPingDao().upsertAll((103..115 step 3).map { ping(t0 + it * minute, siteC) })
        runner.run(t0 + 117 * minute)

        assertThat(db.visitDao().getById(origin)!!.deleted).isTrue()
        assertThat(trips().single().fromVisitId).isNotEqualTo(origin)
    }

    @Test
    fun frozenVisitIsUntouchedAndNothingOverlapsIt() = runBlocking<Unit> {
        insertTwoSiteDay()
        val frozen = Visit(
            id = "frozen", placeId = null, start = t0, end = t0 + 30 * minute, zoneId = "Australia/Sydney",
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

    private fun editor() = TimelineEditor(
        db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(),
        db.segmentationCheckpointDao(), EditClock { now1 + 5 * minute },
    )

    @Test
    fun editsBeforeTheCheckpointAreHonouredAfterTheRewindAndRerun() = runBlocking<Unit> {
        val siteC = -33.9300 to 151.3000
        insertTwoSiteDay()
        // B was 40..70; the user left B at 70 and drove on to C, so B is a closed stay and C the ongoing one.
        val extra = mutableListOf<LocationPing>()
        for (j in 1..9) {
            val f = j / 10.0
            extra += ping(
                t0 + (70 + j) * minute,
                (siteB.first + (siteC.first - siteB.first) * f) to (siteB.second + (siteC.second - siteB.second) * f),
            )
        }
        extra += (80..110 step 3).map { ping(t0 + it * minute, siteC) }
        db.locationPingDao().upsertAll(extra)
        val end = t0 + 112 * minute
        runner.run(end)
        val (a, b, c) = visits()
        val checkpointBefore = db.segmentationCheckpointDao().getById()!!.processedUntil
        assertThat(checkpointBefore).isEqualTo(c.start)

        // Trim A (freezes it) and delete B: both lie before the checkpoint.
        val trimmedEnd = a.end - 5 * minute
        assertThat(editor().trim(a.id, a.start, trimmedEnd)).isInstanceOf(EditResult.Ok::class.java)
        assertThat(editor().delete(b.id)).isInstanceOf(EditResult.Ok::class.java)
        val frozenA = db.visitDao().getById(a.id)!!
        assertThat(db.segmentationCheckpointDao().getById()!!.processedUntil).isEqualTo(a.start)

        runner.run(end + 2 * minute)

        assertThat(db.visitDao().getById(a.id)).isEqualTo(frozenA)
        val live = visits()
        assertThat(live.map { it.id }).contains(a.id)
        assertThat(live.none { it.id != a.id && it.start < b.end && it.end > b.start }).isTrue()
        assertThat(db.visitDao().getById(b.id)!!.deleted).isTrue()
        assertThat(live.none { it.id != a.id && it.start < frozenA.end && it.end > frozenA.start }).isTrue()
        assertThat(trips().none { it.start < b.end && it.end > b.start }).isTrue()
        assertThat(live.any { it.id == c.id }).isTrue()
    }

    @Test
    fun aManualVisitInsideATripSplitsTheTripOnTheNextRun() = runBlocking<Unit> {
        val siteC = -34.2000 to 151.6000
        sessionId = db.trackingSessionDao().upsert(
            TrackingSession(start = t0 - minute, zoneId = "Australia/Sydney", trigger = SessionTrigger.SCHEDULE),
        )
        val pings = mutableListOf<LocationPing>()
        for (k in 0..10) pings += ping(t0 + k * 3 * minute, siteA)
        for (j in 1..44) {
            val f = j / 45.0
            pings += ping(
                t0 + (30 + j) * minute,
                (siteA.first + (siteC.first - siteA.first) * f) to (siteA.second + (siteC.second - siteA.second) * f),
            )
        }
        pings += (75..100 step 3).map { ping(t0 + it * minute, siteC) }
        db.locationPingDao().upsertAll(pings)
        val end = t0 + 101 * minute
        runner.run(end)
        val before = trips().single()
        db.placeDao().upsert(Place(id = "stop", centroidLat = -34.0, centroidLng = 151.4, updatedAt = 0))

        val from = t0 + 50 * minute
        val to = t0 + 60 * minute
        val created = editor().createManual("stop", from, to, "Australia/Sydney") as EditResult.Ok
        runner.run(end + 2 * minute)

        val manual = db.visitDao().getById(created.id!!)!!
        assertThat(manual.deleted).isFalse()
        assertThat(manual.frozen).isTrue()
        assertThat(manual.start).isEqualTo(from)
        assertThat(manual.end).isEqualTo(to)
        val after = trips()
        assertThat(after).hasSize(2)
        assertThat(after.none { it.start < to && it.end > from }).isTrue()
        assertThat(after.first().end).isAtMost(from)
        assertThat(after.last().start).isAtLeast(to)
        assertThat(after.map { it.id }).contains(before.id)
    }
}
