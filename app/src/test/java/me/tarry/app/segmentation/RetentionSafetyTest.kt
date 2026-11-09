package me.tarry.app.segmentation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeWindowChecker
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.repository.EditClock
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.ItemRef
import me.tarry.core.data.repository.RewindFloor
import me.tarry.core.data.repository.TimelineEditor
import me.tarry.core.data.settings.SettingsRepository
import me.tarry.segmentation.SegmentationParams
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Pruning must not change what segmentation produces (phase 8a.1 B1, S1): real editor, runner and retention over an
 * in-memory database holding about 120 days of detected visits and trips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RetentionSafetyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val minute = 60_000L
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_700_000_000_000L
    private val floor get() = now - 90 * day
    private val siteA = -33.8700 to 151.2000
    private val siteB = -33.9000 to 151.2500
    private val params = SegmentationParams()
    private lateinit var db: TarryDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var runner: SegmentationRunner
    private lateinit var retention: PingRetention
    private lateinit var editor: TimelineEditor

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries().build()
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") },
        )
        runner = SegmentationRunner(
            SegmentationStore(db), db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(),
            db.placeDao(), db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao(), FakeWindowChecker(inside = true),
            params,
        )
        retention = PingRetention(
            settings, db.locationPingDao(), db.trackingSessionDao(), db.segmentationCheckpointDao(), params, FakeClock(now = now),
        )
        editor = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(),
            db.segmentationCheckpointDao(), EditClock { now }, RewindFloor { floor },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun ping(sessionId: Long, timeMs: Long, site: Pair<Double, Double>) = LocationPing(
        timestamp = timeMs, zoneId = "UTC", lat = site.first, lng = site.second, accuracyM = 10f,
        source = PingSource.FUSED, sessionId = sessionId,
    )

    /** One session: 30 min at A, a 10 min drive, 30 min at B. Closed unless [open]. */
    private suspend fun addDay(base: Long, open: Boolean = false) {
        val id = db.trackingSessionDao().upsert(
            TrackingSession(start = base - minute, end = if (open) null else base + 75 * minute, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE),
        )
        val pings = mutableListOf<LocationPing>()
        for (k in 0..10) pings += ping(id, base + k * 3 * minute, siteA)
        for (j in 1..9) {
            val f = j / 10.0
            pings += ping(
                id, base + (30 + j) * minute,
                (siteA.first + (siteB.first - siteA.first) * f) to (siteA.second + (siteB.second - siteA.second) * f),
            )
        }
        for (m in 40..70 step 3) pings += ping(id, base + m * minute, siteB)
        db.locationPingDao().upsertAll(pings)
    }

    private suspend fun liveVisits() = db.visitDao().getInRange(0, Long.MAX_VALUE).filter { !it.deleted }
    private suspend fun liveTrips() = db.tripDao().getInRange(0, Long.MAX_VALUE).filter { !it.deleted }
    private suspend fun noFixGaps() = db.gapDao().getInRange(0, Long.MAX_VALUE)
        .filter { !it.deleted && it.cause == GapCause.NO_FIX }.map { it.start to it.end }.sortedBy { it.first }

    @Test
    fun editingAVisitOlderThanThePrunedPingsLeavesItsUnfrozenNeighboursAndGapsAlone() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(90)
        for (offset in listOf(120, 100, 60, 30)) addDay(now - offset * day)
        addDay(now - 72 * minute, open = true)
        runner.run(now)
        val oldDay = now - 100 * day
        val oldVisits = liveVisits().filter { it.start in oldDay - day..oldDay + day }.sortedBy { it.start }
        assertThat(oldVisits).hasSize(2)
        val oldTrip = liveTrips().single { it.start in oldDay - day..oldDay + day }
        val gapsBefore = noFixGaps()

        assertThat(retention.prune()).isGreaterThan(0)
        assertThat(db.locationPingDao().getInRange(0, floor)).isEmpty()

        val other = liveVisits().first { it.start > now - 40 * day }.placeId!!
        assertThat(editor.reassignPlace(oldVisits[0].id, other)).isInstanceOf(EditResult.Ok::class.java)
        runner.run(now + minute)

        val after = liveVisits().filter { it.start in oldDay - day..oldDay + day }.sortedBy { it.start }
        assertThat(after).hasSize(2)
        assertThat(after[0].placeId).isEqualTo(other)
        assertThat(after[0].frozen).isTrue()
        assertThat(after[1]).isEqualTo(oldVisits[1])
        assertThat(liveTrips().single { it.start in oldDay - day..oldDay + day }).isEqualTo(oldTrip)
        assertThat(noFixGaps()).isEqualTo(gapsBefore)
    }

    @Test
    fun resetToDetectedOnAVisitOlderThanTheFloorIsRefusedAndKeepsItFrozen() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(90)
        addDay(now - 100 * day)
        addDay(now - 72 * minute, open = true)
        runner.run(now)
        val old = liveVisits().minBy { it.start }
        editor.setNotes(ItemRef.visit(old.id), "keep me")
        val checkpoint = db.segmentationCheckpointDao().getById()

        assertThat(editor.resetToDetected(old.id)).isEqualTo(EditResult.NotFound)

        assertThat(db.visitDao().getById(old.id)!!.frozen).isTrue()
        assertThat(db.visitDao().getById(old.id)!!.freeNotes).isEqualTo("keep me")
        assertThat(db.segmentationCheckpointDao().getById()).isEqualTo(checkpoint)
    }

    @Test
    fun aVisitStraddlingTheFloorSurvivesAnUpgradeRewindAndRunAfterAPrune() = runBlocking<Unit> {
        settings.setRawPingRetentionDays(90)
        // The stay at A runs from 15 min before the floor to 15 min after it; the trip to B follows.
        addDay(floor - 15 * minute)
        addDay(now - 72 * minute, open = true)
        runner.run(now)
        val before = liveVisits().sortedBy { it.start }
        val tripBefore = liveTrips().first()
        assertThat(before.first().start).isLessThan(floor)

        retention.prune()
        runner.rewindCheckpoint(floor)
        runner.run(now + minute)

        val after = liveVisits().sortedBy { it.start }
        assertThat(after.first().id).isEqualTo(before.first().id)
        assertThat(after.first().start).isEqualTo(before.first().start)
        val tripAfter = liveTrips().first()
        assertThat(tripAfter.id).isEqualTo(tripBefore.id)
        assertThat(tripAfter.fromVisitId).isEqualTo(tripBefore.fromVisitId)
        assertThat(tripAfter.distanceM).isEqualTo(tripBefore.distanceM)
    }
}
