package me.tarry.app.segmentation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeWindowChecker
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Visit
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

/** Phase 4: orphan-place pruning (T8) and place edits (radius, merge, delete) against the real runner (T9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaceOpsRunnerTest {
    private lateinit var db: TarryDatabase
    private lateinit var runner: SegmentationRunner
    private var sessionId = 0L

    private val minute = 60_000L
    private val hour = 60 * minute
    private val t0 = 1_700_000_000_000L
    private val siteA = -33.8700 to 151.2000
    private val siteA2 = (-33.8700 + 50.0 / 111_195.0) to 151.2000 // 50 m north of A
    private val siteB = -33.9000 to 151.2500
    private val siteFar = -30.0 to 150.0

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

    private suspend fun startSession() {
        sessionId = db.trackingSessionDao().upsert(
            TrackingSession(start = t0 - minute, zoneId = "Australia/Sydney", trigger = SessionTrigger.SCHEDULE),
        )
    }

    private fun dwell(site: Pair<Double, Double>, fromMin: Int, toMin: Int) = (fromMin..toMin step 3).map { ping(t0 + it * minute, site) }

    private fun drive(from: Pair<Double, Double>, to: Pair<Double, Double>, startMin: Int) = (1..9).map { j ->
        val f = j / 10.0
        ping(t0 + (startMin + j) * minute, (from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f))
    }

    /** 30 min at A, a drive, then 30 min at B (still there at the end). */
    private suspend fun insertTwoSiteDay() {
        startSession()
        db.locationPingDao().upsertAll(dwell(siteA, 0, 30) + drive(siteA, siteB, 30) + dwell(siteB, 40, 70))
    }

    private val now1 get() = t0 + 72 * minute

    private suspend fun visits() = db.visitDao().getInRange(0, Long.MAX_VALUE)

    private fun editor(nowMs: Long) = TimelineEditor(
        db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
        db.segmentationCheckpointDao(), EditClock { nowMs },
    )

    // region prune (T8)

    private fun orphan(id: String, updatedAt: Long = 0L) =
        Place(id = id, centroidLat = siteFar.first, centroidLng = siteFar.second, updatedAt = updatedAt)

    private fun farVisit(id: String, placeId: String, deleted: Boolean = false, frozen: Boolean = false, updatedAt: Long = 0L, atHours: Int = 0) =
        Visit(
            id = id, placeId = placeId, start = t0 - (50 - atHours) * hour, end = t0 - (50 - atHours) * hour + 30 * minute, zoneId = "UTC",
            deleted = deleted, frozen = frozen, origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = updatedAt,
        )

    @Test
    fun orphanPlacesArePrunedAndEverythingElseIsKept() = runBlocking<Unit> {
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 0))
        db.tagDao().upsert(Tag(id = "tag", groupId = "g", name = "Tag", sortOrder = 0, updatedAt = 0))
        val recent = now1 - 10 * minute
        listOf(
            orphan("orphan"),
            orphan("deletedVisitLongAgo"),
            orphan("named").copy(name = "Home"),
            orphan("job").copy(defaultJobId = "j"),
            orphan("billable").copy(defaultBillable = false),
            orphan("tagged"),
            orphan("ignored").copy(ignored = true),
            orphan("radius").copy(radiusM = 100f),
            orphan("merged").copy(mergedIntoId = "named"),
            orphan("frozenVisit"),
            orphan("liveVisit"),
            orphan("recentlyDeletedVisit"),
            orphan("recentPlace", updatedAt = recent),
            orphan("alreadyDeleted").copy(deleted = true),
        ).forEach { db.placeDao().upsert(it) }
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("tagged", "tag"))
        db.visitDao().upsert(farVisit("v1", "deletedVisitLongAgo", deleted = true))
        db.visitDao().upsert(farVisit("v2", "frozenVisit", frozen = true))
        db.visitDao().upsert(farVisit("v3", "liveVisit"))
        db.visitDao().upsert(farVisit("v4", "recentlyDeletedVisit", deleted = true, updatedAt = recent))
        insertTwoSiteDay()

        runner.run(now1)

        val all = db.placeDao().observeLabels().first().associateBy { it.id }
        fun deleted(id: String) = all.getValue(id).deleted
        assertThat(deleted("orphan")).isTrue()
        assertThat(deleted("deletedVisitLongAgo")).isTrue()
        assertThat(all.getValue("orphan").updatedAt).isEqualTo(now1)
        for (kept in listOf(
            "named", "job", "billable", "tagged", "ignored", "radius", "merged", "frozenVisit", "liveVisit", "recentlyDeletedVisit",
            "recentPlace",
        )) {
            assertThat(deleted(kept)).isFalse()
        }
        // The two places this run created are kept (and nothing else changed).
        assertThat(all.values.count { !it.deleted && it.updatedAt == now1 }).isEqualTo(2)
    }

    @Test
    fun anOrphanIsKeptUntilTheGracePeriodHasPassed() = runBlocking<Unit> {
        insertTwoSiteDay()
        db.placeDao().upsert(orphan("fresh", updatedAt = now1 - 30 * minute))

        runner.run(now1)
        assertThat(db.placeDao().getById("fresh")!!.deleted).isFalse()

        runner.run(now1 + 40 * minute)
        assertThat(db.placeDao().getById("fresh")!!.deleted).isTrue()
    }

    // endregion

    // region integration (T9)

    @Test
    fun afterAMergeARerunCreatesNoPlaceAtTheSourceAndASecondRerunIsANoOp() = runBlocking<Unit> {
        insertTwoSiteDay()
        runner.run(now1)
        val source = visits().first().placeId!!
        val target = Place(id = "target", centroidLat = siteA.first + 0.003, centroidLng = siteA.second, updatedAt = now1)
        db.placeDao().upsert(target)

        val merged = editor(now1 + minute).mergePlaces(source, "target")
        assertThat(merged).isInstanceOf(EditResult.Ok::class.java)
        runner.run(now1 + 10 * minute)

        val active = db.placeDao().getAllActive()
        assertThat(active.map { it.id }).contains("target")
        assertThat(active.none { it.id == source }).isTrue()
        assertThat(active).hasSize(2) // the target and B's place
        assertThat(visits().first().placeId).isEqualTo("target")

        val visitsBefore = visits()
        val placesBefore = db.placeDao().getAllActive().sortedBy { it.id }
        runner.run(now1 + 20 * minute)
        assertThat(visits()).isEqualTo(visitsBefore)
        assertThat(db.placeDao().getAllActive().sortedBy { it.id }).isEqualTo(placesBefore)
    }

    @Test
    fun afterADeleteUnfrozenStaysGetANewPlaceAndFrozenOnesKeepTheDeletedOne() = runBlocking<Unit> {
        insertTwoSiteDay()
        runner.run(now1)
        val (a, b) = visits()
        val placeA = a.placeId!!
        val placeB = b.placeId!!
        assertThat(editor(now1 + minute).setNotes(me.tarry.core.data.repository.ItemRef.visit(b.id), "mine")).isInstanceOf(EditResult.Ok::class.java)
        val frozenB = db.visitDao().getById(b.id)!!
        editor(now1 + 2 * minute).deletePlace(placeA)
        editor(now1 + 2 * minute).deletePlace(placeB)

        runner.run(now1 + 10 * minute)

        assertThat(db.visitDao().getById(b.id)).isEqualTo(frozenB)
        assertThat(db.placeDao().getById(placeB)!!.deleted).isTrue()
        val newA = visits().first { it.id != b.id }
        assertThat(newA.placeId).isNotEqualTo(placeA)
        assertThat(db.placeDao().getById(newA.placeId!!)!!.deleted).isFalse()

        val visitsBefore = visits()
        val placesBefore = db.placeDao().getAllActive().sortedBy { it.id }
        runner.run(now1 + 20 * minute)
        assertThat(visits()).isEqualTo(visitsBefore)
        assertThat(db.placeDao().getAllActive().sortedBy { it.id }).isEqualTo(placesBefore)
    }

    @Test
    fun shrinkingAPlaceBelowTheStayRadiusSplitsOnlyUnfrozenStaysAndASecondRerunIsANoOp() = runBlocking<Unit> {
        startSession()
        db.placeDao().upsert(Place(id = "q", centroidLat = siteA.first, centroidLng = siteA.second, updatedAt = 0))
        // A long frozen history at Q so later stays barely move its centroid.
        db.visitDao().upsert(farVisit("history", "q", frozen = true).copy(start = t0 - 300 * hour, end = t0 - 200 * hour))
        // Morning and afternoon at A, each a dwell at A then 50 m north; B in between and after.
        db.locationPingDao().upsertAll(
            dwell(siteA, 0, 30) + dwell(siteA2, 33, 63) + drive(siteA2, siteB, 63) +
                dwell(siteB, 75, 135) + drive(siteB, siteA, 135) +
                dwell(siteA, 150, 180) + dwell(siteA2, 183, 213) + drive(siteA2, siteB, 213) + dwell(siteB, 225, 255),
        )
        val end = t0 + 257 * minute
        runner.run(end)
        val before = visits().filter { it.placeId == "q" && it.id != "history" }
        assertThat(before).hasSize(2) // one stay each at Q (A and the dwell 50 m away are one stay)
        val afternoon = before.last()
        assertThat(editor(end + minute).setNotes(me.tarry.core.data.repository.ItemRef.visit(afternoon.id), "mine"))
            .isInstanceOf(EditResult.Ok::class.java)
        val frozenAfternoon = db.visitDao().getById(afternoon.id)!!

        assertThat(editor(end + 2 * minute).setPlaceRadius("q", 20f)).isInstanceOf(EditResult.Ok::class.java)
        runner.run(end + 10 * minute)

        assertThat(db.visitDao().getById(afternoon.id)).isEqualTo(frozenAfternoon)
        val mornings = visits().filter { it.id != "history" && it.start < t0 + 70 * minute }
        assertThat(mornings.map { it.placeId }.distinct()).hasSize(2)
        assertThat(mornings).hasSize(2)

        val visitsBefore = visits()
        val placesBefore = db.placeDao().getAllActive().sortedBy { it.id }
        runner.run(end + 20 * minute)
        assertThat(visits()).isEqualTo(visitsBefore)
        assertThat(db.placeDao().getAllActive().sortedBy { it.id }).isEqualTo(placesBefore)
    }

    // endregion
}
