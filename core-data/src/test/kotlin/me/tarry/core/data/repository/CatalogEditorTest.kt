package me.tarry.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.DbTestBase
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogEditorTest : DbTestBase() {
    private lateinit var catalog: CatalogEditor
    private lateinit var timeline: TimelineEditor
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val clock = EditClock { now }
        catalog = CatalogEditor(db, db.jobDao(), db.jobBlockDao(), db.placeDao(), clock)
        timeline = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(),
            db.segmentationCheckpointDao(), clock,
        )
    }

    private fun job(id: String, name: String = id) = Job(id = id, name = name, colorArgb = 1, updatedAt = 1)

    private fun place(id: String, defaultJobId: String? = null) =
        Place(id = id, centroidLat = 1.0, centroidLng = 1.0, defaultJobId = defaultJobId, updatedAt = 1)

    private fun block(id: String, jobId: String, deleted: Boolean = false) =
        JobBlock(id = id, jobId = jobId, start = 0, end = 1000, zoneId = "UTC", billable = true, updatedAt = 1, deleted = deleted)

    // region jobs

    @Test
    fun renameAndRecolourAJob() = runTest {
        db.jobDao().upsert(job("j"))

        assertThat(catalog.renameJob("j", "  Acme ")).isEqualTo(EditResult.Ok())
        assertThat(catalog.setJobColour("j", 0xFF112233.toInt())).isEqualTo(EditResult.Ok())

        val row = db.jobDao().getById("j")!!
        assertThat(row.name).isEqualTo("Acme")
        assertThat(row.colorArgb).isEqualTo(0xFF112233.toInt())
        assertThat(row.updatedAt).isEqualTo(now)
    }

    @Test
    fun blankNamesAndMissingRowsAreRejectedWithoutWriting() = runTest {
        db.jobDao().upsert(job("j", "Keep"))

        assertThat(catalog.renameJob("j", "  ")).isEqualTo(EditResult.BlankName)
        assertThat(catalog.renameJob("nope", "x")).isEqualTo(EditResult.NotFound)
        assertThat(db.jobDao().getById("j")!!.name).isEqualTo("Keep")
    }

    @Test
    fun jobsAreOrderedByNameIgnoringCase() = runTest {
        listOf("banana", "Apple", "cherry", "Banana2").forEach { db.jobDao().upsert(job(it)) }

        assertThat(db.jobDao().observeAll().first().map { it.name }).containsExactly("Apple", "banana", "Banana2", "cherry").inOrder()
    }

    @Test
    fun archivingAJobClearsPlaceDefaultsAndUndoRestoresThem() = runTest {
        db.jobDao().upsert(job("j"))
        db.jobDao().upsert(job("other"))
        db.placeDao().upsert(place("p1", "j"))
        db.placeDao().upsert(place("p2", "j"))
        db.placeDao().upsert(place("p3", "other"))

        val result = catalog.archiveJob("j") as EditResult.Ok

        assertThat(db.jobDao().getById("j")!!.archived).isTrue()
        assertThat(db.placeDao().getById("p1")!!.defaultJobId).isNull()
        assertThat(db.placeDao().getById("p2")!!.defaultJobId).isNull()
        assertThat(db.placeDao().getById("p3")!!.defaultJobId).isEqualTo("other")

        timeline.restore(result.undo!!)

        assertThat(db.jobDao().getById("j")!!.archived).isFalse()
        assertThat(db.placeDao().getById("p1")!!.defaultJobId).isEqualTo("j")
        assertThat(db.placeDao().getById("p2")!!.defaultJobId).isEqualTo("j")
    }

    @Test
    fun archiveJobUndoRestoresOnlyTheDefaultJobNotOtherPlaceColumns() = runTest {
        db.jobDao().upsert(job("j"))
        db.placeDao().upsert(place("p", "j"))
        val result = catalog.archiveJob("j") as EditResult.Ok

        // Segmentation ran in between and moved the place.
        db.placeDao().upsert(db.placeDao().getById("p")!!.copy(centroidLat = 9.0, lastVisitAt = 5_000))
        timeline.restore(result.undo!!)

        val p = db.placeDao().getById("p")!!
        assertThat(p.defaultJobId).isEqualTo("j")
        assertThat(p.centroidLat).isEqualTo(9.0)
        assertThat(p.lastVisitAt).isEqualTo(5_000)
    }

    @Test
    fun archiveUndoKeepsARenameMadeSinceAndStampsUpdatedAt() = runTest {
        db.jobDao().upsert(job("j"))
        val jobUndo = catalog.archiveJob("j") as EditResult.Ok
        catalog.renameJob("j", "Renamed")

        now = 2_000_000L
        timeline.restore(jobUndo.undo!!)

        val j = db.jobDao().getById("j")!!
        assertThat(j.archived).isFalse()
        assertThat(j.name).isEqualTo("Renamed")
        assertThat(j.updatedAt).isEqualTo(2_000_000L)
    }

    @Test
    fun archiveUndoDoesNotResurrectARowDeletedSince() = runTest {
        db.jobDao().upsert(job("j"))
        val jobUndo = catalog.archiveJob("j") as EditResult.Ok
        assertThat(catalog.deleteJob("j")).isEqualTo(EditResult.Ok())

        timeline.restore(jobUndo.undo!!)

        assertThat(db.jobDao().getById("j")!!.deleted).isTrue()
        assertThat(db.jobDao().getById("j")!!.archived).isTrue()
    }


    @Test
    fun archiveJobUndoKeepsADefaultJobSetInBetween() = runTest {
        db.jobDao().upsert(job("j"))
        db.jobDao().upsert(job("new"))
        db.placeDao().upsert(place("p1", "j"))
        db.placeDao().upsert(place("p2", "j"))
        val result = catalog.archiveJob("j") as EditResult.Ok
        db.placeDao().setDefaultJob("p1", "new", 5)

        timeline.restore(result.undo!!)

        assertThat(db.placeDao().getById("p1")!!.defaultJobId).isEqualTo("new")
        assertThat(db.placeDao().getById("p2")!!.defaultJobId).isEqualTo("j")
    }

    @Test
    fun unarchiveBringsTheJobBackButNotTheDefaults() = runTest {
        db.jobDao().upsert(job("j"))
        db.placeDao().upsert(place("p", "j"))
        catalog.archiveJob("j")

        assertThat(catalog.unarchiveJob("j")).isEqualTo(EditResult.Ok())

        assertThat(db.jobDao().getById("j")!!.archived).isFalse()
        assertThat(db.placeDao().getById("p")!!.defaultJobId).isNull()
    }
    @Test
    fun deletingAJobIsBlockedWhileAnythingLiveUsesIt() = runTest {
        listOf("byBlock", "byPlace", "onlyDeletedBlock").forEach { db.jobDao().upsert(job(it)) }
        db.jobBlockDao().upsert(block("b", "byBlock"))
        db.jobBlockDao().upsert(block("gone", "onlyDeletedBlock", deleted = true))
        db.placeDao().upsert(place("p", "byPlace"))

        assertThat(catalog.deleteJob("byBlock")).isEqualTo(EditResult.InUse(1))
        assertThat(catalog.deleteJob("byPlace")).isEqualTo(EditResult.InUse(1))
        assertThat(db.jobDao().getById("byBlock")!!.deleted).isFalse()

        assertThat(catalog.deleteJob("onlyDeletedBlock")).isEqualTo(EditResult.Ok())
        assertThat(db.jobDao().getById("onlyDeletedBlock")!!.deleted).isTrue()
        assertThat(db.jobDao().observeAll().first().map { it.id }).doesNotContain("onlyDeletedBlock")
        assertThat(catalog.deleteJob("onlyDeletedBlock")).isEqualTo(EditResult.NotFound)
    }


    @Test
    fun jobDefaultBillableCanBeToggled() = runTest {
        db.jobDao().upsert(job("j"))
        assertThat(db.jobDao().getById("j")!!.defaultBillable).isTrue()

        assertThat(catalog.setJobDefaultBillable("j", false)).isEqualTo(EditResult.Ok())

        val row = db.jobDao().getById("j")!!
        assertThat(row.defaultBillable).isFalse()
        assertThat(row.updatedAt).isEqualTo(now)
        assertThat(catalog.setJobDefaultBillable("nope", true)).isEqualTo(EditResult.NotFound)
    }

    // endregion
}
