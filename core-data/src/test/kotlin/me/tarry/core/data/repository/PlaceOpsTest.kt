package me.tarry.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.DbTestBase
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Place radius, merge and delete (phase 4 T7): semantics, rewind and exact undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceOpsTest : DbTestBase() {
    private lateinit var editor: TimelineEditor
    private var now = 100_000_000L

    private val min = 60_000L
    private val t0 = 10_000_000L

    // One degree of latitude is about 111.2 km.
    private val baseLat = -33.0
    private val baseLng = 151.0
    private fun lat(metresNorth: Double) = baseLat + metresNorth / 111_195.0

    @Before
    fun setUp() {
        editor = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
            db.segmentationCheckpointDao(), EditClock { now },
        )
    }

    private fun place(id: String, northM: Double = 0.0, radius: Float = 75f, name: String? = null, lastVisitAt: Long? = null) =
        Place(id = id, centroidLat = lat(northM), centroidLng = baseLng, radiusM = radius, name = name, lastVisitAt = lastVisitAt, updatedAt = 1)

    private fun visit(id: String, startMin: Int, endMin: Int, placeId: String?, frozen: Boolean = false, jobId: String? = null) = Visit(
        id = id, placeId = placeId, start = t0 + startMin * min, end = t0 + endMin * min, zoneId = "UTC", jobId = jobId,
        frozen = frozen, origin = VisitOrigin.DETECTED, confidence = 0.8f, updatedAt = 1,
    )

    private suspend fun save(vararg rows: Any) = rows.forEach { if (it is Place) db.placeDao().upsert(it) else db.visitDao().upsert(it as Visit) }

    private suspend fun setCheckpoint(ms: Long) = db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = ms))
    private suspend fun checkpoint() = db.segmentationCheckpointDao().getById()?.processedUntil
    private suspend fun visitById(id: String) = db.visitDao().getById(id)!!
    private suspend fun placeById(id: String) = db.placeDao().getById(id)!!

    private data class State(val places: List<Place>, val visits: List<Visit>, val tags: Map<String, Set<String>>)

    private suspend fun state(): State {
        val ps = db.placeDao().observeLabels().first().sortedBy { it.id }
        return State(
            ps, db.visitDao().getInRange(0, Long.MAX_VALUE),
            ps.associate { it.id to db.placeDao().getDefaultTags(it.id).map { l -> l.tagId }.toSet() },
        )
    }

    private suspend fun undoOf(result: EditResult): Snapshot = (result as EditResult.Ok).undo!!

    /** A re-run after an op: the unfrozen visit is replaced by a new one in the same range (new id, other place). */
    private suspend fun simulateRerun(oldId: String, newPlaceId: String) {
        db.visitDao().upsert(visitById(oldId).copy(deleted = true, updatedAt = now))
        save(visit("rerun-$oldId", 5, 55, newPlaceId).copy(updatedAt = now))
    }

    // region radius

    @Test
    fun radiusIsCoercedToRangeAndStep() = runTest {
        save(place("p"))
        editor.setPlaceRadius("p", 17f)
        assertThat(placeById("p").radiusM).isEqualTo(20f)
        editor.setPlaceRadius("p", 62f)
        assertThat(placeById("p").radiusM).isEqualTo(60f)
        editor.setPlaceRadius("p", 1000f)
        assertThat(placeById("p").radiusM).isEqualTo(300f)
    }

    @Test
    fun radiusUnchangedIsOkWithoutUndoAndRewindsNothing() = runTest {
        save(place("p"), visit("v", 0, 60, "p"))
        setCheckpoint(t0 + 500 * min)
        val result = editor.setPlaceRadius("p", 77f) // coerces to 75, the current value
        assertThat(result).isEqualTo(EditResult.Ok())
        assertThat(checkpoint()).isEqualTo(t0 + 500 * min)
    }

    @Test
    fun radiusOnMissingOrDeletedPlaceIsNotFound() = runTest {
        save(place("gone").copy(deleted = true))
        assertThat(editor.setPlaceRadius("nope", 100f)).isEqualTo(EditResult.NotFound)
        assertThat(editor.setPlaceRadius("gone", 100f)).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun radiusNeverChangesAFrozenVisitAndRewindsToTheEarliestUnfrozenOne() = runTest {
        save(place("p"), visit("frozen", 0, 60, "p", frozen = true, jobId = null), visit("live", 100, 160, "p"))
        setCheckpoint(t0 + 500 * min)
        val frozenBefore = visitById("frozen")

        val result = editor.setPlaceRadius("p", 120f)

        assertThat(result).isInstanceOf(EditResult.Ok::class.java)
        assertThat(visitById("frozen")).isEqualTo(frozenBefore)
        assertThat(placeById("p").radiusM).isEqualTo(120f)
        assertThat(checkpoint()).isEqualTo(t0 + 100 * min)
    }

    @Test
    fun radiusRewindStartIncludesNeighboursWithinReachButNotFarOnes() = runTest {
        // 150 m apart: within 75 + 75 only when the radius grows to 100+ (max(old,new) + r_N = 100 + 75 = 175 >= 150).
        save(place("p"), place("near", northM = 150.0), place("far", northM = 5_000.0))
        save(visit("pv", 300, 360, "p", frozen = true), visit("nv", 200, 260, "near"), visit("fv", 10, 50, "far"))
        setCheckpoint(t0 + 900 * min)

        editor.setPlaceRadius("p", 100f)

        assertThat(checkpoint()).isEqualTo(t0 + 200 * min)
    }

    @Test
    fun radiusDoesNotRewindWhenNothingIsUnfrozen() = runTest {
        save(place("p"), visit("frozen", 0, 60, "p", frozen = true))
        setCheckpoint(t0 + 500 * min)
        val result = editor.setPlaceRadius("p", 120f)
        assertThat(checkpoint()).isEqualTo(t0 + 500 * min)
        assertThat((result as EditResult.Ok).undo!!.range).isNull()
    }

    @Test
    fun radiusUndoIsExactEvenAfterARerun() = runTest {
        save(place("p"), place("q", northM = 20_000.0), visit("frozen", 0, 30, "p", frozen = true), visit("live", 40, 90, "p"))
        setCheckpoint(t0 + 500 * min)
        val before = state()

        val undo = undoOf(editor.setPlaceRadius("p", 200f))
        simulateRerun("live", "q")
        editor.restore(undo)

        assertThat(state()).isEqualTo(before)
    }

    // endregion

    // region merge

    @Test
    fun mergeRepointsFrozenAndUnfrozenVisitsAndTombstonesTheSource() = runTest {
        save(
            place("s", northM = 0.0, lastVisitAt = t0 + 500 * min), place("t", northM = 50.0, lastVisitAt = t0 + 100 * min),
            place("old", northM = 10.0).copy(deleted = true, mergedIntoId = "s"),
        )
        save(visit("sf", 0, 30, "s", frozen = true, jobId = "j"), visit("su", 40, 80, "s"), visit("tv", 100, 160, "t"))
        val frozenBefore = visitById("sf")
        setCheckpoint(t0 + 900 * min)

        val result = editor.mergePlaces("s", "t")

        assertThat(result).isInstanceOf(EditResult.Ok::class.java)
        assertThat(visitById("sf")).isEqualTo(frozenBefore.copy(placeId = "t", updatedAt = now))
        assertThat(visitById("su").placeId).isEqualTo("t")
        val source = placeById("s")
        assertThat(source.deleted).isTrue()
        assertThat(source.mergedIntoId).isEqualTo("t")
        assertThat(placeById("old").mergedIntoId).isEqualTo("t") // chains stay flat
        assertThat(placeById("t").lastVisitAt).isEqualTo(t0 + 500 * min)
        assertThat(checkpoint()).isEqualTo(t0 + 40 * min)
    }

    @Test
    fun mergeFillsTheTargetsGapsFromTheSourceAndKeepsItsOwnValues() = runTest {
        db.jobDao().upsert(Job(id = "j", name = "J", colorArgb = 1, updatedAt = 1))
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 1))
        db.tagDao().upsert(Tag(id = "t1", groupId = "g", name = "t1", sortOrder = 0, updatedAt = 1))
        save(
            place("s", name = "Source").copy(address = "1 Main St", defaultJobId = "j", defaultBillable = true, ignored = true),
            place("t", northM = 10.0, name = " ").copy(address = "", ignored = false),
            place("t2", northM = 10.0, name = "Mine").copy(address = "Mine St", defaultBillable = false, ignored = false),
        )
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("s", "t1"))

        editor.mergePlaces("s", "t")
        val t = placeById("t")
        assertThat(t.name).isEqualTo("Source")
        assertThat(t.address).isEqualTo("1 Main St")
        assertThat(t.defaultJobId).isEqualTo("j")
        assertThat(t.defaultBillable).isTrue()
        assertThat(t.ignored).isFalse() // the target's, not the source's
        assertThat(db.placeDao().getDefaultTags("t").map { it.tagId }).containsExactly("t1")

        save(place("s2", name = "Other").copy(defaultBillable = true, defaultJobId = "j"))
        editor.mergePlaces("s2", "t2")
        val t2 = placeById("t2")
        assertThat(t2.name).isEqualTo("Mine")
        assertThat(t2.address).isEqualTo("Mine St")
        assertThat(t2.defaultBillable).isFalse()
        assertThat(t2.defaultJobId).isEqualTo("j")
    }

    @Test
    fun mergeKeepsTheTargetsDefaultTagsWhenItHasAny() = runTest {
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 1))
        for (id in listOf("a", "b")) db.tagDao().upsert(Tag(id = id, groupId = "g", name = id, sortOrder = 0, updatedAt = 1))
        save(place("s"), place("t"))
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("s", "a"))
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("t", "b"))

        editor.mergePlaces("s", "t")

        assertThat(db.placeDao().getDefaultTags("t").map { it.tagId }).containsExactly("b")
    }

    @Test
    fun mergeCentroidIsDurationWeightedAndTheCircleCoversBoth() = runTest {
        save(place("s", northM = 0.0), place("t", northM = 100.0))
        save(visit("sv", 0, 30, "s"), visit("tv", 100, 190, "t")) // 30 min vs 90 min: centroid 75 m from s

        val preview = editor.previewMerge("s", "t")!!
        editor.mergePlaces("s", "t")

        val t = placeById("t")
        assertThat(t.centroidLat).isWithin(1e-7).of(lat(75.0))
        // s is 75 m from the centroid: 75 + 75 = 150 m.
        assertThat(t.radiusM).isWithin(0.5f).of(150f)
        assertThat(preview.radiusM).isEqualTo(t.radiusM)
        assertThat(preview.capped).isFalse()
    }

    @Test
    fun mergeFallsBackToEqualWeightsAndNeverShrinksTheTarget() = runTest {
        save(place("s", northM = 0.0, radius = 30f), place("t", northM = 0.0, radius = 100f))
        val result = editor.mergePlaces("s", "t")
        assertThat(placeById("t").radiusM).isEqualTo(100f)
        assertThat((result as EditResult.Ok).capped).isFalse()

        save(place("s3", northM = 0.0), place("t3", northM = 100.0))
        editor.mergePlaces("s3", "t3")
        assertThat(placeById("t3").centroidLat).isWithin(1e-7).of(lat(50.0))
    }

    @Test
    fun mergeRadiusIsCappedAndSaysSo() = runTest {
        save(place("s", northM = 0.0), place("t", northM = 600.0)) // equal weights: 300 + 75 > 300
        val preview = editor.previewMerge("s", "t")!!
        assertThat(preview).isEqualTo(MergePreview(300f, capped = true))

        val result = editor.mergePlaces("s", "t") as EditResult.Ok

        assertThat(result.capped).isTrue()
        assertThat(placeById("t").radiusM).isEqualTo(300f)
    }

    @Test
    fun mergeRejectsSameMissingAndDeletedPlaces() = runTest {
        save(place("s"), place("t"), place("gone").copy(deleted = true))
        assertThat(editor.mergePlaces("s", "s")).isEqualTo(EditResult.NotFound)
        assertThat(editor.mergePlaces("s", "nope")).isEqualTo(EditResult.NotFound)
        assertThat(editor.mergePlaces("nope", "t")).isEqualTo(EditResult.NotFound)
        assertThat(editor.mergePlaces("gone", "t")).isEqualTo(EditResult.NotFound)
        assertThat(editor.mergePlaces("s", "gone")).isEqualTo(EditResult.NotFound)
        assertThat(editor.previewMerge("s", "s")).isNull()
        assertThat(editor.previewMerge("s", "gone")).isNull()
        // A live place that is itself marked merged away is not a valid target either.
        save(place("m").copy(mergedIntoId = "t"))
        assertThat(editor.mergePlaces("s", "m")).isEqualTo(EditResult.NotFound)
        assertThat(editor.previewMerge("s", "m")).isNull()
        assertThat(placeById("s").deleted).isFalse()
    }

    @Test
    fun mergeUndoIsExactEvenAfterARerunAndWithFrozenVisitsBeforeTheRewindRange() = runTest {
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 1))
        db.tagDao().upsert(Tag(id = "t1", groupId = "g", name = "t1", sortOrder = 0, updatedAt = 1))
        save(
            place("s", name = "S"), place("t", northM = 100.0), place("old", northM = 5.0).copy(deleted = true, mergedIntoId = "s"),
            place("other", northM = 30_000.0),
        )
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("s", "t1"))
        // The frozen source visit is long before the earliest unfrozen one, outside the rewind range.
        save(visit("sf", 0, 20, "s", frozen = true), visit("su", 200, 260, "s"), visit("tv", 300, 360, "t"))
        setCheckpoint(t0 + 900 * min)
        val before = state()

        val undo = undoOf(editor.mergePlaces("s", "t"))
        assertThat(visitById("sf").placeId).isEqualTo("t")
        // A re-run inside the range replaces the unfrozen source visit.
        db.visitDao().upsert(visitById("su").copy(deleted = true, updatedAt = now))
        save(visit("rerun", 200, 260, "other").copy(updatedAt = now))
        editor.restore(undo)

        assertThat(state()).isEqualTo(before)
    }

    // endregion

    // region delete

    @Test
    fun deleteSoftDeletesUnfrozenVisitsKeepsFrozenOnesAndRecordsNoExclusion() = runTest {
        save(place("p"), visit("frozen", 0, 30, "p", frozen = true), visit("live", 40, 90, "p"))
        setCheckpoint(t0 + 900 * min)
        val frozenBefore = visitById("frozen")

        val result = editor.deletePlace("p")

        assertThat(result).isInstanceOf(EditResult.Ok::class.java)
        assertThat(placeById("p").deleted).isTrue()
        assertThat(visitById("live").deleted).isTrue()
        assertThat(visitById("frozen")).isEqualTo(frozenBefore)
        assertThat(db.excludedRangeDao().getInRange(0, Long.MAX_VALUE)).isEmpty()
        assertThat(checkpoint()).isEqualTo(t0 + 40 * min)
    }

    @Test
    fun deleteOfAMissingOrDeletedPlaceIsNotFound() = runTest {
        save(place("gone").copy(deleted = true))
        assertThat(editor.deletePlace("nope")).isEqualTo(EditResult.NotFound)
        assertThat(editor.deletePlace("gone")).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun deleteWithOnlyFrozenVisitsDoesNotRewind() = runTest {
        save(place("p"), visit("frozen", 0, 30, "p", frozen = true))
        setCheckpoint(t0 + 900 * min)
        editor.deletePlace("p")
        assertThat(checkpoint()).isEqualTo(t0 + 900 * min)
    }

    @Test
    fun deleteUndoIsExactEvenAfterARerun() = runTest {
        save(place("p"), place("q", northM = 20_000.0), visit("frozen", 0, 30, "p", frozen = true), visit("live", 40, 90, "p"))
        setCheckpoint(t0 + 900 * min)
        val before = state()

        val undo = undoOf(editor.deletePlace("p"))
        // The re-run detects the stay again under a new place.
        save(visit("rerun", 40, 90, "q").copy(updatedAt = now))
        // Note: undoing after a re-run can leave the re-run's new place ("q") with no live visits, because restore only soft-deletes
        // visits. That orphan is intentional here: segmentation's orphan-place prune is the safety net, not undo.
        editor.restore(undo)

        assertThat(state()).isEqualTo(before)
    }

    // endregion

    // region ignore and retention floor

    @Test
    fun setPlaceIgnoredWorksWithoutVisitsAndUndoRestoresTheRow() = runTest {
        save(place("p"))
        val before = placeById("p")

        val undo = undoOf(editor.setPlaceIgnored("p", true))

        assertThat(placeById("p").ignored).isTrue()
        assertThat(placeById("p").updatedAt).isEqualTo(now)
        editor.restore(undo)
        assertThat(placeById("p")).isEqualTo(before)
        assertThat(editor.setPlaceIgnored("p", false)).isEqualTo(EditResult.Ok()) // no change, no undo
    }

    @Test
    fun setPlaceIgnoredAndBulkIgnoreRefuseMissingOrDeletedPlaces() = runTest {
        save(place("gone").copy(deleted = true), visit("v", 0, 30, "gone"))
        assertThat(editor.setPlaceIgnored("nope", true)).isEqualTo(EditResult.NotFound)
        assertThat(editor.setPlaceIgnored("gone", true)).isEqualTo(EditResult.NotFound)
        assertThat(editor.bulk(listOf(ItemRef.visit("v")), AnnotationOp.IgnorePlace())).isEqualTo(EditResult.NotFound)
        assertThat(placeById("gone").ignored).isFalse()
    }

    @Test
    fun rewindNeverGoesEarlierThanTheRetentionFloor() = runTest {
        val floor = t0 + 60 * min
        val clamped = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
            db.segmentationCheckpointDao(), EditClock { now }, RewindFloor { floor },
        )
        save(place("p"), visit("old", 0, 30, "p"), visit("recent", 100, 160, "p"))
        setCheckpoint(t0 + 900 * min)

        val undo = undoOf(clamped.setPlaceRadius("p", 120f)) // earliest unfrozen is "old", before the floor
        assertThat(checkpoint()).isEqualTo(floor)

        setCheckpoint(t0 + 900 * min)
        clamped.restore(undo)
        assertThat(checkpoint()).isEqualTo(floor)

        // A start already after the floor is untouched.
        db.visitDao().upsert(visitById("old").copy(deleted = true))
        setCheckpoint(t0 + 900 * min)
        clamped.setPlaceRadius("p", 150f)
        assertThat(checkpoint()).isEqualTo(t0 + 100 * min)
    }

    // endregion
}
