package me.tarry.core.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.dao.PlaceDuration
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Query-only DAO additions used by the segmentation runner. Query window: [1000, 2000). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SegmentationQueriesTest : DbTestBase() {

    private fun visit(
        id: String, start: Long, end: Long, placeId: String? = null, frozen: Boolean = false, deleted: Boolean = false,
    ) = Visit(
        id = id, placeId = placeId, start = start, end = end, zoneId = "UTC", frozen = frozen,
        origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0, deleted = deleted,
    )

    private fun trip(id: String, start: Long, end: Long, frozen: Boolean = false, deleted: Boolean = false) = Trip(
        id = id, start = start, end = end, zoneId = "UTC", distanceM = 1f, mode = TripMode.VEHICLE,
        frozen = frozen, updatedAt = 0, deleted = deleted,
    )

    @Test
    fun placeGetAllActive_excludesDeleted() = runTest {
        db.placeDao().upsert(Place(id = "a", centroidLat = 0.0, centroidLng = 0.0, updatedAt = 0))
        db.placeDao().upsert(Place(id = "merged", centroidLat = 0.0, centroidLng = 0.0, mergedIntoId = "a", updatedAt = 0))
        db.placeDao().upsert(Place(id = "gone", centroidLat = 0.0, centroidLng = 0.0, updatedAt = 0, deleted = true))
        assertThat(db.placeDao().getAllActive().map { it.id }).containsExactly("a", "merged")
    }

    @Test
    fun visitUnfrozenAndFrozenInRange_splitByFrozenAndSkipDeleted() = runTest {
        db.visitDao().upsertAll(
            listOf(
                visit("free", 1100, 1200),
                visit("freeEdge", 900, 1100),
                visit("frozen", 1300, 1400, frozen = true),
                visit("deleted", 1500, 1600, deleted = true),
                visit("deletedFrozen", 1500, 1600, frozen = true, deleted = true),
                visit("outside", 2000, 2100),
                visit("outsideFrozen", 100, 1000, frozen = true),
            ),
        )
        assertThat(db.visitDao().getUnfrozenInRange(1000, 2000).map { it.id }).containsExactly("freeEdge", "free").inOrder()
        assertThat(db.visitDao().getFrozenInRange(1000, 2000).map { it.id }).containsExactly("frozen")
    }

    @Test
    fun tripUnfrozenAndFrozenInRange_splitByFrozenAndSkipDeleted() = runTest {
        db.tripDao().upsertAll(
            listOf(
                trip("free", 1100, 1200),
                trip("frozen", 1300, 1400, frozen = true),
                trip("deleted", 1500, 1600, deleted = true),
                trip("outside", 2000, 2100),
            ),
        )
        assertThat(db.tripDao().getUnfrozenInRange(1000, 2000).map { it.id }).containsExactly("free")
        assertThat(db.tripDao().getFrozenInRange(1000, 2000).map { it.id }).containsExactly("frozen")
    }

    @Test
    fun durationByPlaceOutside_sumsOnlyNonOverlappingNonDeletedVisitsWithAPlace() = runTest {
        db.placeDao().upsert(Place(id = "p1", centroidLat = 0.0, centroidLng = 0.0, updatedAt = 0))
        db.placeDao().upsert(Place(id = "p2", centroidLat = 0.0, centroidLng = 0.0, updatedAt = 0))
        db.visitDao().upsertAll(
            listOf(
                visit("a", 100, 300, placeId = "p1"),
                visit("b", 2000, 2500, placeId = "p1"), // starts at `to`: outside
                visit("c", 500, 1000, placeId = "p1"), // ends at `from`: outside
                visit("overlap", 1500, 1600, placeId = "p1"),
                visit("straddle", 900, 1100, placeId = "p1"),
                visit("deleted", 3000, 4000, placeId = "p1", deleted = true),
                visit("noPlace", 3000, 4000),
                visit("other", 3000, 3050, placeId = "p2"),
            ),
        )
        val result = db.visitDao().durationByPlaceOutside(1000, 2000)
        assertThat(result).containsExactly(PlaceDuration("p1", 200 + 500 + 500), PlaceDuration("p2", 50))
    }

    @Test
    fun sessionGetOverlapping_includesOpenSessionsAndSkipsNonOverlapping() = runTest {
        fun session(start: Long, end: Long?) =
            TrackingSession(start = start, end = end, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE)
        val dao = db.trackingSessionDao()
        dao.upsert(session(0, 900)) // before
        dao.upsert(session(0, 1000)) // ends at from
        dao.upsert(session(500, 1500)) // straddles start
        dao.upsert(session(1200, 1300)) // inside
        dao.upsert(session(1800, null)) // open
        dao.upsert(session(2000, 2100)) // starts at to
        assertThat(dao.getOverlapping(1000, 2000).map { it.start }).containsExactly(500L, 1200L, 1800L).inOrder()
    }
}
