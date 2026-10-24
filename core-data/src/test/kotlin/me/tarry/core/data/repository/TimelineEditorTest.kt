package me.tarry.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.DbTestBase
import me.tarry.core.data.db.entity.ExcludedRange
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.TripTag
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineEditorTest : DbTestBase() {
    private lateinit var editor: TimelineEditor
    private var now = 1_000_000L

    private val min = 60_000L
    private val t0 = 10_000_000L

    @Before
    fun setUp() {
        editor = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
            db.segmentationCheckpointDao(), EditClock { now },
        )
    }

    private fun visit(
        id: String, startMin: Int, endMin: Int, placeId: String? = "p1", jobId: String? = null, frozen: Boolean = false,
        notes: String = "", billable: Boolean? = null, origin: VisitOrigin = VisitOrigin.DETECTED,
    ) = Visit(
        id = id, placeId = placeId, start = t0 + startMin * min, end = t0 + endMin * min, zoneId = "UTC", jobId = jobId,
        freeNotes = notes, billable = billable, frozen = frozen, origin = origin, confidence = 0.8f, updatedAt = 1,
    )

    private fun trip(id: String, startMin: Int, endMin: Int, frozen: Boolean = false) = Trip(
        id = id, start = t0 + startMin * min, end = t0 + endMin * min, zoneId = "UTC", distanceM = 100f,
        mode = TripMode.VEHICLE, frozen = frozen, updatedAt = 1,
    )

    private suspend fun seed() {
        db.placeDao().upsert(Place(id = "p1", centroidLat = 1.0, centroidLng = 1.0, updatedAt = 1))
        db.placeDao().upsert(Place(id = "p2", centroidLat = 2.0, centroidLng = 2.0, updatedAt = 1))
        db.jobDao().upsert(Job(id = "j1", name = "Job 1", colorArgb = 1, updatedAt = 1))
        db.jobDao().upsert(Job(id = "j2", name = "Job 2", colorArgb = 2, updatedAt = 1))
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 1))
        for (t in listOf("t1", "t2", "t3")) db.tagDao().upsert(Tag(id = t, groupId = "g", name = t, sortOrder = 0, updatedAt = 1))
    }

    /** A at 0..60, trip 60..70, B at 70..130, C at 140..200. */
    private suspend fun seedDay() {
        seed()
        db.visitDao().upsertAll(
            listOf(visit("A", 0, 60, jobId = "j1"), visit("B", 70, 130, placeId = "p2"), visit("C", 140, 200)),
        )
        db.tripDao().upsert(trip("T1", 60, 70))
        db.visitDao().upsertTag(VisitTag("A", "t1"))
        db.visitDao().upsertTag(VisitTag("B", "t2"))
        db.tripDao().upsertTag(TripTag("T1", "t3"))
    }

    private data class State(
        val visits: List<Visit>, val trips: List<Trip>, val exclusions: List<ExcludedRange>, val tags: Map<String, Set<String>>,
    )

    private suspend fun state(): State {
        val vs = db.visitDao().getInRange(0, Long.MAX_VALUE)
        val ts = db.tripDao().getInRange(0, Long.MAX_VALUE)
        val tags = vs.associate { it.id to db.visitDao().getTags(it.id).map { l -> l.tagId }.toSet() } +
            ts.associate { it.id to db.tripDao().getTags(it.id).map { l -> l.tagId }.toSet() }
        return State(vs, ts, db.excludedRangeDao().getInRange(0, Long.MAX_VALUE), tags)
    }

    private suspend fun checkpoint() = db.segmentationCheckpointDao().getById()?.processedUntil

    private suspend fun visitById(id: String) = db.visitDao().getById(id)!!

    private fun EditResult.undo(): Snapshot = (this as EditResult.Ok).undo!!

    // region annotations

    @Test
    fun annotationsFreezeAndBumpUpdatedAtWithoutTouchingTheCheckpoint() = runTest {
        seedDay()
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))
        val ref = ItemRef.visit("B")

        assertThat(editor.setJob(ref, "j2")).isEqualTo(EditResult.Ok())
        assertThat(visitById("B").jobId).isEqualTo("j2")
        assertThat(editor.setBillable(ref, true)).isEqualTo(EditResult.Ok())
        assertThat(editor.setNotes(ref, "hello")).isEqualTo(EditResult.Ok())
        assertThat(editor.setTags(ref, setOf("t1", "t3"))).isEqualTo(EditResult.Ok())

        val b = visitById("B")
        assertThat(b.billable).isTrue()
        assertThat(b.freeNotes).isEqualTo("hello")
        assertThat(b.frozen).isTrue()
        assertThat(b.updatedAt).isEqualTo(now)
        assertThat(db.visitDao().getTags("B").map { it.tagId }).containsExactly("t1", "t3")
        assertThat(checkpoint()).isEqualTo(t0 + 500 * min)
    }

    @Test
    fun tripAnnotationsAndAttribution() = runTest {
        seedDay()
        val ref = ItemRef.trip("T1")

        editor.setBillable(ref, false)
        editor.setNotes(ref, "toll")
        editor.setTags(ref, setOf("t1"))
        editor.setAttribution("T1", "j2")

        val t = db.tripDao().getById("T1")!!
        assertThat(t.billable).isFalse()
        assertThat(t.freeNotes).isEqualTo("toll")
        assertThat(t.attributedJobId).isEqualTo("j2")
        assertThat(t.frozen).isTrue()
        assertThat(t.updatedAt).isEqualTo(now)
        assertThat(db.tripDao().getTags("T1").map { it.tagId }).containsExactly("t1")

        editor.setAttribution("T1", null)
        assertThat(db.tripDao().getById("T1")!!.attributedJobId).isNull()
    }

    @Test
    fun annotationOnMissingOrDeletedItemIsNotFound() = runTest {
        seedDay()
        db.visitDao().upsert(visitById("C").copy(deleted = true))

        assertThat(editor.setJob(ItemRef.visit("nope"), "j1")).isEqualTo(EditResult.NotFound)
        assertThat(editor.setJob(ItemRef.visit("C"), "j1")).isEqualTo(EditResult.NotFound)
        assertThat(editor.setAttribution("nope", null)).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun placeDefaultReplacesJobBillableAndTags() = runTest {
        seedDay()
        editor.setPlaceDefault("p1", "j1", true, setOf("t1", "t2"))
        editor.setPlaceDefault("p1", "j2", null, setOf("t3"))

        val p = db.placeDao().getById("p1")!!
        assertThat(p.defaultJobId).isEqualTo("j2")
        assertThat(p.defaultBillable).isNull()
        assertThat(p.updatedAt).isEqualTo(now)
        assertThat(db.placeDao().getDefaultTags("p1").map { it.tagId }).containsExactly("t3")
        assertThat(editor.setPlaceDefault("nope", null, null, emptySet())).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun renamePlaceSetsTrimsAndClearsTheName() = runTest {
        seedDay()

        assertThat(editor.renamePlace("p1", "  Smith site ")).isEqualTo(EditResult.Ok())
        assertThat(db.placeDao().getById("p1")!!.name).isEqualTo("Smith site")
        assertThat(db.placeDao().getById("p1")!!.updatedAt).isEqualTo(now)

        editor.renamePlace("p1", "   ")
        assertThat(db.placeDao().getById("p1")!!.name).isNull()
        assertThat(editor.renamePlace("nope", "x")).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun createJobTakesTheNextUnusedPaletteColour() = runTest {
        val palette = listOf(10, 20, 30)

        val first = (editor.createJob("  One ", palette) as EditResult.Ok).id!!
        val second = (editor.createJob("Two", palette) as EditResult.Ok).id!!
        db.jobDao().upsert(db.jobDao().getById(first)!!.copy(deleted = true))
        val third = (editor.createJob("Three", palette) as EditResult.Ok).id!!

        assertThat(db.jobDao().getById(first)!!.name).isEqualTo("One")
        assertThat(db.jobDao().getById(first)!!.colorArgb).isEqualTo(10)
        assertThat(db.jobDao().getById(second)!!.colorArgb).isEqualTo(20)
        // First job was deleted, so its colour is free again.
        assertThat(db.jobDao().getById(third)!!.colorArgb).isEqualTo(10)
        assertThat(db.jobDao().getById(third)!!.updatedAt).isEqualTo(now)
        assertThat(editor.createJob("  ", palette)).isEqualTo(EditResult.BlankName)
    }

    @Test
    fun createJobCyclesWhenThePaletteIsExhausted() = runTest {
        val palette = listOf(10, 20)
        editor.createJob("a", palette)
        editor.createJob("b", palette)

        val id = (editor.createJob("c", palette) as EditResult.Ok).id!!

        // Two live jobs, so index 2 % 2 = 0.
        assertThat(db.jobDao().getById(id)!!.colorArgb).isEqualTo(10)
    }

    @Test
    fun createTagAppendsToTheGroupAndCreatesADefaultGroupWhenNeeded() = runTest {
        val id = (editor.createTag("first") as EditResult.Ok).id!!
        val group = db.tagDao().getGroups().single()
        assertThat(db.tagDao().getById(id)!!.groupId).isEqualTo(group.id)

        val second = (editor.createTag("second", group.id) as EditResult.Ok).id!!
        assertThat(db.tagDao().getById(second)!!.sortOrder).isEqualTo(1)
        assertThat(editor.createTag("x", "nope")).isEqualTo(EditResult.NotFound)
        assertThat(editor.createTag(" ")).isEqualTo(EditResult.BlankName)
    }

    // endregion

    // region structural

    @Test
    fun trimClampsToNeighboursFreezesAndRewindsTheCheckpoint() = runTest {
        seedDay()
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))

        // B is 70..130; A ends at 60 and C starts at 140, so 0..500 clamps to 60..140.
        val r = editor.trim("B", t0, t0 + 500 * min)

        assertThat(r).isInstanceOf(EditResult.Ok::class.java)
        val b = visitById("B")
        assertThat(b.start).isEqualTo(t0 + 60 * min)
        assertThat(b.end).isEqualTo(t0 + 140 * min)
        assertThat(b.frozen).isTrue()
        assertThat(b.updatedAt).isEqualTo(now)
        assertThat(checkpoint()).isEqualTo(t0 + 60 * min)
    }

    @Test
    fun trimToNothingIsEmptyRangeAndWritesNothing() = runTest {
        seedDay()
        val before = state()

        assertThat(editor.trim("B", t0 + 100 * min, t0 + 100 * min)).isEqualTo(EditResult.EmptyRange)
        assertThat(editor.trim("B", t0 + 100 * min, t0 + 90 * min)).isEqualTo(EditResult.EmptyRange)
        assertThat(editor.trim("nope", 0, 1)).isEqualTo(EditResult.NotFound)
        assertThat(state()).isEqualTo(before)
    }

    @Test
    fun resizeWithNoNeighbourMovesBothEdgesFreezesAndRewindsTheCheckpoint() = runTest {
        seedDay()
        db.visitDao().upsert(visit("D", 300, 360))
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 900 * min))

        val r = editor.resize("D", t0 + 290 * min, t0 + 400 * min)

        assertThat(r.undo()).isNotNull()
        val d = visitById("D")
        assertThat(d.start).isEqualTo(t0 + 290 * min)
        assertThat(d.end).isEqualTo(t0 + 400 * min)
        assertThat(d.frozen).isTrue()
        assertThat(d.updatedAt).isEqualTo(now)
        assertThat(checkpoint()).isEqualTo(t0 + 290 * min)
        assertThat(visitById("C").frozen).isFalse()
    }

    @Test
    fun resizeIntoThePreviousVisitTrimsIt() = runTest {
        seedDay()

        editor.resize("B", t0 + 30 * min, t0 + 130 * min)

        assertThat(visitById("B").start).isEqualTo(t0 + 30 * min)
        val a = visitById("A")
        assertThat(a.start).isEqualTo(t0)
        assertThat(a.end).isEqualTo(t0 + 30 * min)
        assertThat(a.frozen).isTrue()
        assertThat(a.deleted).isFalse()
        assertThat(a.updatedAt).isEqualTo(now)
    }

    @Test
    fun resizeIntoTheNextVisitTrimsIt() = runTest {
        seedDay()

        editor.resize("B", t0 + 70 * min, t0 + 160 * min)

        assertThat(visitById("B").end).isEqualTo(t0 + 160 * min)
        val c = visitById("C")
        assertThat(c.start).isEqualTo(t0 + 160 * min)
        assertThat(c.end).isEqualTo(t0 + 200 * min)
        assertThat(c.frozen).isTrue()
    }

    @Test
    fun resizeKeepsAtLeastTheMinimumOnTheVisitAndOnTheTrimmedNeighbour() = runTest {
        seedDay()

        // Dragging B's start past its own end leaves 5 min.
        editor.resize("B", t0 + 135 * min, t0 + 130 * min)
        assertThat(visitById("B").start).isEqualTo(t0 + 125 * min)
        assertThat(visitById("B").end).isEqualTo(t0 + 130 * min)

        // Dragging B's start far into A stops 5 min after A's start.
        editor.resize("B", t0 + 2 * min, t0 + 130 * min)
        assertThat(visitById("B").start).isEqualTo(t0 + 5 * min)
        assertThat(visitById("A").end).isEqualTo(t0 + 5 * min)

        // Dragging B's end far into C stops 5 min before C's end.
        editor.resize("B", t0 + 5 * min, t0 + 500 * min)
        assertThat(visitById("B").end).isEqualTo(t0 + 195 * min)
        assertThat(visitById("C").start).isEqualTo(t0 + 195 * min)
        assertThat(visitById("C").end).isEqualTo(t0 + 200 * min)
    }

    @Test
    fun resizeTrimsAFrozenNeighbourAndKeepsItsAnnotations() = runTest {
        seedDay()
        editor.setNotes(ItemRef.visit("A"), "keep")
        now += 10

        editor.resize("B", t0 + 50 * min, t0 + 130 * min)

        val a = visitById("A")
        assertThat(a.end).isEqualTo(t0 + 50 * min)
        assertThat(a.frozen).isTrue()
        assertThat(a.freeNotes).isEqualTo("keep")
        assertThat(a.jobId).isEqualTo("j1")
        assertThat(a.updatedAt).isEqualTo(now)
    }

    @Test
    fun resizeWithoutChangeWritesNothingAndMissingVisitIsNotFound() = runTest {
        seedDay()
        val before = state()

        assertThat(editor.resize("B", t0 + 70 * min, t0 + 130 * min)).isEqualTo(EditResult.Ok())
        assertThat(editor.resize("nope", 0, 1)).isEqualTo(EditResult.NotFound)
        assertThat(state()).isEqualTo(before)
    }

    @Test
    fun resizeNeverLeavesTwoLiveVisitsOverlapping() = runTest {
        seedDay()
        val edges = listOf(-50, 0, 20, 65, 100, 135, 150, 199, 260)
        for (s in edges) for (e in edges) {
            for (id in listOf("A", "B", "C")) editor.resize(id, t0 + s * min, t0 + e * min)
            val live = db.visitDao().getInRange(0, Long.MAX_VALUE).sortedBy { it.start }
            live.zipWithNext { x, y -> assertThat(x.end).isAtMost(y.start) }
            live.forEach { assertThat(it.end - it.start).isAtLeast(5 * min) }
        }
    }

    @Test
    fun undoRoundTripResizeIntoBothNeighbours() = runTest {
        seedDay()
        assertRoundTrip {
            editor.resize("B", t0 + 30 * min, t0 + 160 * min)
        }
    }

    @Test
    fun resizeOverAFrozenTripUnfreezesItAndUndoRestoresIt() = runTest {
        seedDay()
        assertUnfreezesTripAndUndoes { editor.resize("B", t0 + 65 * min, t0 + 130 * min) }
    }

    @Test
    fun checkpointIsOnlyEverLowered() = runTest {
        seedDay()
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 10 * min))

        editor.trim("C", t0 + 150 * min, t0 + 190 * min)

        assertThat(checkpoint()).isEqualTo(t0 + 10 * min)
    }

    @Test
    fun splitMakesTwoFrozenSplitVisitsWithAnnotationsAndTagsCopied() = runTest {
        seedDay()
        editor.setBillable(ItemRef.visit("A"), true)
        editor.setNotes(ItemRef.visit("A"), "n")
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))
        now += 10

        val r = editor.split("A", t0 + 20 * min) as EditResult.Ok

        val first = visitById("A")
        val second = visitById(r.id!!)
        assertThat(first.end).isEqualTo(t0 + 20 * min)
        assertThat(second.start).isEqualTo(t0 + 20 * min)
        assertThat(second.end).isEqualTo(t0 + 60 * min)
        for (v in listOf(first, second)) {
            assertThat(v.origin).isEqualTo(VisitOrigin.SPLIT)
            assertThat(v.frozen).isTrue()
            assertThat(v.updatedAt).isEqualTo(now)
            assertThat(v.jobId).isEqualTo("j1")
            assertThat(v.billable).isTrue()
            assertThat(v.freeNotes).isEqualTo("n")
            assertThat(v.placeId).isEqualTo("p1")
            assertThat(db.visitDao().getTags(v.id).map { it.tagId }).containsExactly("t1")
        }
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(checkpoint()).isEqualTo(t0)
    }

    @Test
    fun splitOutsideTheVisitIsEmptyRange() = runTest {
        seedDay()
        assertThat(editor.split("A", t0)).isEqualTo(EditResult.EmptyRange)
        assertThat(editor.split("A", t0 + 60 * min)).isEqualTo(EditResult.EmptyRange)
        assertThat(editor.split("A", t0 + 90 * min)).isEqualTo(EditResult.EmptyRange)
    }

    @Test
    fun mergeWithNextMakesOneFrozenMergedVisit() = runTest {
        seedDay()
        db.visitDao().upsert(notes())
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))

        val r = editor.merge("A", MergeDirection.NEXT) as EditResult.Ok

        val merged = visitById("A")
        assertThat(r.id).isEqualTo("A")
        assertThat(merged.start).isEqualTo(t0)
        assertThat(merged.end).isEqualTo(t0 + 130 * min)
        assertThat(merged.origin).isEqualTo(VisitOrigin.MERGED)
        assertThat(merged.frozen).isTrue()
        assertThat(merged.updatedAt).isEqualTo(now)
        // The earlier visit's job wins; notes are joined; both place-less defaults fall back to the longer visit's place.
        assertThat(merged.jobId).isEqualTo("j1")
        assertThat(merged.freeNotes).isEqualTo("b note")
        assertThat(merged.placeId).isEqualTo("p1")
        assertThat(db.visitDao().getTags("A").map { it.tagId }).containsExactly("t1", "t2")
        assertThat(visitById("B").deleted).isTrue()
        assertThat(checkpoint()).isEqualTo(t0)
    }

    private fun notes() = visit("B", 70, 130, placeId = "p2", notes = "b note", jobId = "j2")

    @Test
    fun mergeWithPreviousUsesTheEarlierVisitsJobUnlessNull() = runTest {
        seedDay()
        db.visitDao().upsert(visitById("A").copy(jobId = null))

        editor.merge("B", MergeDirection.PREVIOUS)

        val merged = visitById("A")
        assertThat(merged.jobId).isNull()
        assertThat(merged.end).isEqualTo(t0 + 130 * min)

        db.visitDao().upsert(visitById("C").copy(jobId = "j2"))
        editor.merge("C", MergeDirection.PREVIOUS)
        assertThat(visitById("A").jobId).isEqualTo("j2")
    }

    @Test
    fun mergeWithoutANeighbourIsNotFound() = runTest {
        seedDay()
        assertThat(editor.merge("A", MergeDirection.PREVIOUS)).isEqualTo(EditResult.NotFound)
        assertThat(editor.merge("C", MergeDirection.NEXT)).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun reassignPlaceFreezesAndRewinds() = runTest {
        seedDay()
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))

        editor.reassignPlace("C", "p2")

        val c = visitById("C")
        assertThat(c.placeId).isEqualTo("p2")
        assertThat(c.frozen).isTrue()
        assertThat(c.updatedAt).isEqualTo(now)
        assertThat(checkpoint()).isEqualTo(t0 + 140 * min)
        assertThat(editor.reassignPlace("C", "nope")).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun deleteSoftDeletesAndExcludesTheSpan() = runTest {
        seedDay()
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))

        editor.delete("B")

        assertThat(visitById("B").deleted).isTrue()
        val ex = db.excludedRangeDao().getInRange(0, Long.MAX_VALUE).single()
        assertThat(ex.start).isEqualTo(t0 + 70 * min)
        assertThat(ex.end).isEqualTo(t0 + 130 * min)
        assertThat(ex.zoneId).isEqualTo("UTC")
        assertThat(ex.updatedAt).isEqualTo(now)
        assertThat(checkpoint()).isEqualTo(t0 + 70 * min)
        assertThat(editor.delete("B")).isEqualTo(EditResult.NotFound)
    }

    @Test
    fun createManualMakesAFrozenManualVisitAndRejectsOverlapWithLiveVisits() = runTest {
        seedDay()
        db.visitDao().upsert(visitById("C").copy(deleted = true))
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))

        // Inside the trip T1? No room there (10 min). Use the gap after B instead, overlapping trips is allowed.
        val r = editor.createManual("p1", t0 + 130 * min, t0 + 150 * min, "Australia/Sydney") as EditResult.Ok

        val v = visitById(r.id!!)
        assertThat(v.origin).isEqualTo(VisitOrigin.MANUAL)
        assertThat(v.frozen).isTrue()
        assertThat(v.zoneId).isEqualTo("Australia/Sydney")
        assertThat(v.updatedAt).isEqualTo(now)
        assertThat(checkpoint()).isEqualTo(t0 + 130 * min)

        assertThat(editor.createManual("p1", t0 + 120 * min, t0 + 135 * min, "UTC")).isEqualTo(EditResult.Overlap)
        assertThat(editor.createManual("p1", t0 + 10 * min, t0 + 20 * min, "UTC")).isEqualTo(EditResult.Overlap)
        assertThat(editor.createManual("p1", t0 + 160 * min, t0 + 160 * min, "UTC")).isEqualTo(EditResult.EmptyRange)
        assertThat(editor.createManual("nope", t0 + 160 * min, t0 + 170 * min, "UTC")).isEqualTo(EditResult.NotFound)
        // Touching edges are fine.
        assertThat(editor.createManual("p1", t0 + 150 * min, t0 + 170 * min, "UTC")).isInstanceOf(EditResult.Ok::class.java)
    }

    @Test
    fun createManualInsideATripIsAllowed() = runTest {
        seed()
        db.visitDao().upsertAll(listOf(visit("A", 0, 10), visit("B", 100, 110)))
        db.tripDao().upsert(trip("T", 10, 100))

        val r = editor.createManual("p2", t0 + 40 * min, t0 + 50 * min, "UTC")

        assertThat(r).isInstanceOf(EditResult.Ok::class.java)
        // The trip is derived: left alone for segmentation to recompute.
        assertThat(db.tripDao().getById("T")!!.deleted).isFalse()
    }

    @Test
    fun resetToDetectedClearsAnnotationsUnfreezesAndRemovesExclusionsInsideTheSpan() = runTest {
        seedDay()
        editor.setNotes(ItemRef.visit("A"), "mine")
        editor.setBillable(ItemRef.visit("A"), true)
        db.excludedRangeDao().upsert(ExcludedRange(id = "inside", start = t0 + 10 * min, end = t0 + 20 * min, zoneId = "UTC", updatedAt = 1))
        db.excludedRangeDao().upsert(ExcludedRange(id = "outside", start = t0 + 50 * min, end = t0 + 65 * min, zoneId = "UTC", updatedAt = 1))
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = t0 + 500 * min))
        now += 10

        editor.resetToDetected("A")

        val a = visitById("A")
        assertThat(a.jobId).isNull()
        assertThat(a.billable).isNull()
        assertThat(a.freeNotes).isEmpty()
        assertThat(a.frozen).isFalse()
        assertThat(a.updatedAt).isEqualTo(now)
        assertThat(db.visitDao().getTags("A")).isEmpty()
        assertThat(db.excludedRangeDao().getInRange(0, Long.MAX_VALUE).map { it.id }).containsExactly("outside")
        assertThat(db.excludedRangeDao().getById("inside")!!.deleted).isTrue()
        assertThat(checkpoint()).isEqualTo(t0)
    }

    // endregion

    // region undo

    private suspend fun assertRoundTrip(op: suspend () -> EditResult) {
        val before = state()
        val r = op()
        assertThat(r).isInstanceOf(EditResult.Ok::class.java)
        assertThat(state()).isNotEqualTo(before)

        // The checkpoint moved on; undo must pull it back.
        db.segmentationCheckpointDao().upsert(SegmentationCheckpoint(processedUntil = Long.MAX_VALUE))
        editor.restore(r.undo())

        assertThat(state()).isEqualTo(before)
        assertThat(checkpoint()).isAtMost(t0 + 200 * min)
    }

    @Test
    fun undoRoundTripTrim() = runTest { seedDay(); assertRoundTrip { editor.trim("B", t0 + 80 * min, t0 + 120 * min) } }

    @Test
    fun undoRoundTripSplit() = runTest { seedDay(); assertRoundTrip { editor.split("A", t0 + 30 * min) } }

    @Test
    fun undoRoundTripMerge() = runTest { seedDay(); assertRoundTrip { editor.merge("B", MergeDirection.NEXT) } }

    @Test
    fun undoRoundTripReassignPlace() = runTest { seedDay(); assertRoundTrip { editor.reassignPlace("A", "p2") } }

    @Test
    fun undoRoundTripDelete() = runTest { seedDay(); assertRoundTrip { editor.delete("B") } }

    @Test
    fun undoRoundTripCreateManual() = runTest { seedDay(); assertRoundTrip { editor.createManual("p1", t0 + 130 * min, t0 + 140 * min, "UTC") } }

    @Test
    fun undoRoundTripResetToDetected() = runTest {
        seedDay()
        editor.setNotes(ItemRef.visit("A"), "mine")
        db.excludedRangeDao().upsert(ExcludedRange(id = "inside", start = t0 + 10 * min, end = t0 + 20 * min, zoneId = "UTC", updatedAt = 1))
        assertRoundTrip { editor.resetToDetected("A") }
    }

    @Test
    fun undoPutsBackTheTripsSegmentationChangedInBetween() = runTest {
        seedDay()
        val before = state()
        val r = editor.delete("B")
        // Segmentation, run after the delete, re-derived the trip around the gap.
        db.tripDao().upsert(db.tripDao().getById("T1")!!.copy(end = t0 + 140 * min, updatedAt = 99))
        db.tripDao().upsert(trip("T2", 130, 140))

        editor.restore(r.undo())

        assertThat(state()).isEqualTo(before)
    }

    // endregion

    // region bulk

    @Test
    fun bulkAppliesToManyItemsInOneTransactionWithOneUndo() = runTest {
        seedDay()
        val before = state()
        val items = listOf(ItemRef.visit("A"), ItemRef.visit("B"), ItemRef.trip("T1"))

        val r = editor.bulk(items, AnnotationOp.SetJob("j2"))

        assertThat(visitById("A").jobId).isEqualTo("j2")
        assertThat(visitById("B").jobId).isEqualTo("j2")
        assertThat(db.tripDao().getById("T1")!!.attributedJobId).isEqualTo("j2")
        assertThat(listOf(visitById("A"), visitById("B")).all { it.frozen && it.updatedAt == now }).isTrue()

        editor.restore(r.undo())
        assertThat(state()).isEqualTo(before)
    }

    @Test
    fun bulkAddTagsKeepsExistingTagsAndUndoRestoresThem() = runTest {
        seedDay()
        val before = state()

        val r = editor.bulk(listOf(ItemRef.visit("A"), ItemRef.visit("C")), AnnotationOp.AddTags(setOf("t2", "t3")))

        assertThat(db.visitDao().getTags("A").map { it.tagId }).containsExactly("t1", "t2", "t3")
        assertThat(db.visitDao().getTags("C").map { it.tagId }).containsExactly("t2", "t3")
        editor.restore(r.undo())
        assertThat(state()).isEqualTo(before)
    }

    @Test
    fun bulkSetBillableFreezesAllItemsAndUndoRestoresThem() = runTest {
        seedDay()
        val before = state()

        val r = editor.bulk(listOf(ItemRef.visit("A"), ItemRef.visit("C")), AnnotationOp.SetBillable(false))

        assertThat(visitById("A").billable).isFalse()
        assertThat(visitById("C").billable).isFalse()
        assertThat(visitById("A").frozen).isTrue()
        editor.restore(r.undo())
        assertThat(state()).isEqualTo(before)
    }

    @Test
    fun bulkIgnorePlaceMarksThePlacesAndUndoClearsIt() = runTest {
        seedDay()
        val before = state()

        val r = editor.bulk(listOf(ItemRef.visit("A"), ItemRef.visit("B")), AnnotationOp.IgnorePlace())

        assertThat(db.placeDao().getById("p1")!!.ignored).isTrue()
        assertThat(db.placeDao().getById("p2")!!.ignored).isTrue()
        assertThat(db.placeDao().getById("p1")!!.updatedAt).isEqualTo(now)
        // Ignoring a place is not a label: the visits stay unfrozen.
        assertThat(visitById("A").frozen).isFalse()

        editor.restore(r.undo())
        assertThat(db.placeDao().getById("p1")!!.ignored).isFalse()
        assertThat(db.placeDao().getById("p2")!!.ignored).isFalse()
        assertThat(state()).isEqualTo(before)
    }

    @Test
    fun bulkWithAMissingItemWritesNothing() = runTest {
        seedDay()
        val before = state()

        val r = editor.bulk(listOf(ItemRef.visit("A"), ItemRef.visit("nope")), AnnotationOp.SetBillable(true))

        assertThat(r).isEqualTo(EditResult.NotFound)
        assertThat(state()).isEqualTo(before)
    }

    // endregion

    // region frozen trips under structural edits

    private suspend fun frozenAnnotatedTrip() {
        editor.setNotes(ItemRef.trip("T1"), "toll")
        editor.setAttribution("T1", "j2")
        now += 10
    }

    private suspend fun assertUnfreezesTripAndUndoes(op: suspend () -> EditResult) {
        frozenAnnotatedTrip()
        val before = state()
        assertThat(db.tripDao().getById("T1")!!.frozen).isTrue()

        val r = op()

        val t = db.tripDao().getById("T1")!!
        assertThat(t.frozen).isFalse()
        assertThat(t.updatedAt).isEqualTo(now)
        assertThat(t.freeNotes).isEqualTo("toll")
        assertThat(t.attributedJobId).isEqualTo("j2")
        assertThat(t.deleted).isFalse()

        editor.restore(r.undo())
        assertThat(state()).isEqualTo(before)
        assertThat(db.tripDao().getById("T1")!!.frozen).isTrue()
    }

    @Test
    fun trimIntoAFrozenTripUnfreezesItAndUndoRestoresIt() = runTest {
        seedDay()
        assertUnfreezesTripAndUndoes { editor.trim("B", t0 + 65 * min, t0 + 130 * min) }
    }

    @Test
    fun mergeOverAFrozenTripUnfreezesItAndUndoRestoresIt() = runTest {
        seedDay()
        assertUnfreezesTripAndUndoes { editor.merge("A", MergeDirection.NEXT) }
    }

    @Test
    fun createManualInsideAFrozenTripUnfreezesItAndUndoRestoresIt() = runTest {
        seedDay()
        assertUnfreezesTripAndUndoes { editor.createManual("p2", t0 + 62 * min, t0 + 68 * min, "UTC") }
    }

    @Test
    fun splitUnfreezesAFrozenTripOverlappingTheVisit() = runTest {
        seedDay()
        db.tripDao().upsert(trip("T2", 20, 30, frozen = true))
        val before = state()

        val r = editor.split("A", t0 + 40 * min)

        assertThat(db.tripDao().getById("T2")!!.frozen).isFalse()
        editor.restore(r.undo())
        assertThat(state()).isEqualTo(before)
    }

    // endregion
}
