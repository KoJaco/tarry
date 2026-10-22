package me.tarry.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.DbTestBase
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
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
class CatalogEditorTest : DbTestBase() {
    private lateinit var catalog: CatalogEditor
    private lateinit var timeline: TimelineEditor
    private var now = 1_000_000L

    @Before
    fun setUp() {
        val clock = EditClock { now }
        catalog = CatalogEditor(db, db.jobDao(), db.tagDao(), db.placeDao(), clock)
        timeline = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
            db.segmentationCheckpointDao(), clock,
        )
    }

    private fun job(id: String, name: String = id) = Job(id = id, name = name, colorArgb = 1, updatedAt = 1)

    private fun place(id: String, defaultJobId: String? = null) =
        Place(id = id, centroidLat = 1.0, centroidLng = 1.0, defaultJobId = defaultJobId, updatedAt = 1)

    private fun visit(id: String, jobId: String? = null, deleted: Boolean = false) =
        Visit(id = id, placeId = null, start = 0, end = 1000, zoneId = "UTC", jobId = jobId, deleted = deleted, origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 1)

    private fun trip(id: String, attributedJobId: String? = null) =
        Trip(id = id, start = 0, end = 1, zoneId = "UTC", distanceM = 1f, mode = TripMode.VEHICLE, attributedJobId = attributedJobId, updatedAt = 1)

    private fun tag(id: String, groupId: String = "g", sortOrder: Int = 0) =
        Tag(id = id, groupId = groupId, name = id, sortOrder = sortOrder, updatedAt = 1)

    private suspend fun seedGroup(vararg tagIds: String) {
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 1))
        tagIds.forEachIndexed { i, id -> db.tagDao().upsert(tag(id, sortOrder = i)) }
    }

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
        seedGroup("t")

        assertThat(catalog.renameJob("j", "  ")).isEqualTo(EditResult.BlankName)
        assertThat(catalog.renameJob("nope", "x")).isEqualTo(EditResult.NotFound)
        assertThat(catalog.renameTag("t", "")).isEqualTo(EditResult.BlankName)
        assertThat(catalog.createTagGroup(" ")).isEqualTo(EditResult.BlankName)
        assertThat(catalog.renameTagGroup("g", " ")).isEqualTo(EditResult.BlankName)
        assertThat(db.jobDao().getById("j")!!.name).isEqualTo("Keep")
        assertThat(db.tagDao().getById("t")!!.name).isEqualTo("t")
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
        db.visitDao().upsert(visit("v", jobId = "j"))

        val result = catalog.archiveJob("j") as EditResult.Ok

        assertThat(db.jobDao().getById("j")!!.archived).isTrue()
        assertThat(db.placeDao().getById("p1")!!.defaultJobId).isNull()
        assertThat(db.placeDao().getById("p2")!!.defaultJobId).isNull()
        assertThat(db.placeDao().getById("p3")!!.defaultJobId).isEqualTo("other")
        assertThat(db.visitDao().getById("v")!!.jobId).isEqualTo("j")

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
        listOf("byVisit", "byTrip", "byPlace", "onlyDeletedVisit").forEach { db.jobDao().upsert(job(it)) }
        db.visitDao().upsert(visit("v", jobId = "byVisit"))
        db.visitDao().upsert(visit("gone", jobId = "onlyDeletedVisit", deleted = true))
        db.tripDao().upsert(trip("t", attributedJobId = "byTrip"))
        db.placeDao().upsert(place("p", "byPlace"))

        assertThat(catalog.deleteJob("byVisit")).isEqualTo(EditResult.InUse(1))
        assertThat(catalog.deleteJob("byTrip")).isEqualTo(EditResult.InUse(1))
        assertThat(catalog.deleteJob("byPlace")).isEqualTo(EditResult.InUse(1))
        assertThat(db.jobDao().getById("byVisit")!!.deleted).isFalse()

        assertThat(catalog.deleteJob("onlyDeletedVisit")).isEqualTo(EditResult.Ok())
        assertThat(db.jobDao().getById("onlyDeletedVisit")!!.deleted).isTrue()
        assertThat(db.jobDao().observeAll().first().map { it.id }).doesNotContain("onlyDeletedVisit")
        assertThat(catalog.deleteJob("onlyDeletedVisit")).isEqualTo(EditResult.NotFound)
    }

    // endregion

    // region tags

    @Test
    fun archivingATagRemovesPlaceDefaultsAndUndoRestoresOnlyThePlacesItTouched() = runTest {
        seedGroup("a", "b")
        listOf("p1", "p2", "p3").forEach { db.placeDao().upsert(place(it)) }
        listOf("p1" to "a", "p1" to "b", "p2" to "a", "p3" to "b").forEach { (p, t) -> db.placeDao().upsertDefaultTag(PlaceDefaultTag(p, t)) }
        db.visitDao().upsert(visit("v"))
        db.visitDao().upsertTag(VisitTag("v", "a"))

        val result = catalog.archiveTag("a") as EditResult.Ok

        assertThat(db.tagDao().getById("a")!!.archived).isTrue()
        assertThat(db.placeDao().getDefaultTags("p1").map { it.tagId }).containsExactly("b")
        assertThat(db.placeDao().getDefaultTags("p2")).isEmpty()
        assertThat(db.visitDao().getTags("v").map { it.tagId }).containsExactly("a")
        // A later edit to a place the undo does not cover must survive the undo.
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("p3", "a"))

        timeline.restore(result.undo!!)

        assertThat(db.tagDao().getById("a")!!.archived).isFalse()
        assertThat(db.placeDao().getDefaultTags("p1").map { it.tagId }).containsExactly("a", "b")
        assertThat(db.placeDao().getDefaultTags("p2").map { it.tagId }).containsExactly("a")
        assertThat(db.placeDao().getDefaultTags("p3").map { it.tagId }).containsExactly("b", "a")
    }

    @Test
    fun unarchiveAndRenameATag() = runTest {
        seedGroup("a")
        catalog.archiveTag("a")

        assertThat(catalog.unarchiveTag("a")).isEqualTo(EditResult.Ok())
        assertThat(catalog.renameTag("a", " Urgent ")).isEqualTo(EditResult.Ok())

        val row = db.tagDao().getById("a")!!
        assertThat(row.archived).isFalse()
        assertThat(row.name).isEqualTo("Urgent")
    }

    @Test
    fun deletingATagIsBlockedWhileAVisitTripOrPlaceDefaultCarriesIt() = runTest {
        seedGroup("onVisit", "onTrip", "onPlace", "free")
        db.visitDao().upsert(visit("v"))
        db.visitDao().upsertTag(VisitTag("v", "onVisit"))
        db.tripDao().upsert(trip("t"))
        db.tripDao().upsertTag(TripTag("t", "onTrip"))
        db.placeDao().upsert(place("p"))
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("p", "onPlace"))

        assertThat(catalog.deleteTag("onVisit")).isEqualTo(EditResult.InUse(1))
        assertThat(catalog.deleteTag("onTrip")).isEqualTo(EditResult.InUse(1))
        assertThat(catalog.deleteTag("onPlace")).isEqualTo(EditResult.InUse(1))

        assertThat(catalog.deleteTag("free")).isEqualTo(EditResult.Ok())
        assertThat(db.tagDao().getById("free")!!.deleted).isTrue()
    }

    // endregion

    // region groups

    @Test
    fun createdGroupsGoLastAndAcceptAColour() = runTest {
        seedGroup()

        val id = (catalog.createTagGroup("Second", 0xFF00FF00.toInt()) as EditResult.Ok).id!!

        val groups = db.tagDao().getGroups()
        assertThat(groups.map { it.id }).containsExactly("g", id).inOrder()
        assertThat(groups.last().name).isEqualTo("Second")
        assertThat(groups.last().sortOrder).isEqualTo(1)
        assertThat(groups.last().colorArgb).isEqualTo(0xFF00FF00.toInt())
    }

    @Test
    fun renameAndRecolourAGroup() = runTest {
        seedGroup()

        assertThat(catalog.renameTagGroup("g", " Kinds ")).isEqualTo(EditResult.Ok())
        assertThat(catalog.setTagGroupColour("g", 0xFF123456.toInt())).isEqualTo(EditResult.Ok())
        assertThat(db.tagDao().getGroupById("g")!!.colorArgb).isEqualTo(0xFF123456.toInt())
        assertThat(catalog.setTagGroupColour("g", 0)).isEqualTo(EditResult.Ok())

        val group = db.tagDao().getGroupById("g")!!
        assertThat(group.name).isEqualTo("Kinds")
        assertThat(group.colorArgb).isEqualTo(0)
    }

    @Test
    fun archiveAllTagsArchivesTheWholeGroupAndUndoRestores() = runTest {
        seedGroup("a", "b", "c")
        db.tagDao().upsertGroup(TagGroup(id = "h", name = "H", colorArgb = 0, sortOrder = 1, updatedAt = 1))
        db.tagDao().upsert(tag("other", groupId = "h"))
        db.tagDao().upsert(tag("c", sortOrder = 2).copy(archived = true))
        db.placeDao().upsert(place("p"))
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("p", "a"))
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("p", "other"))

        val result = catalog.archiveAllTags("g") as EditResult.Ok

        assertThat(db.tagDao().getByGroup("g").map { it.archived }).containsExactly(true, true, true)
        assertThat(db.tagDao().getById("other")!!.archived).isFalse()
        assertThat(db.placeDao().getDefaultTags("p").map { it.tagId }).containsExactly("other")

        timeline.restore(result.undo!!)

        assertThat(db.tagDao().getByGroup("g").map { it.archived }).containsExactly(false, false, true).inOrder()
        assertThat(db.placeDao().getDefaultTags("p").map { it.tagId }).containsExactly("a", "other")
    }

    @Test
    fun aGroupIsDeletedOnlyWhenItHasNoTagsLeft() = runTest {
        seedGroup("a", "b")

        assertThat(catalog.deleteTagGroup("g")).isEqualTo(EditResult.InUse(2))
        catalog.deleteTag("a")
        assertThat(catalog.deleteTagGroup("g")).isEqualTo(EditResult.InUse(1))
        // An archived tag still counts: the group is not empty.
        catalog.archiveTag("b")
        assertThat(catalog.deleteTagGroup("g")).isEqualTo(EditResult.InUse(1))
        catalog.deleteTag("b")

        assertThat(catalog.deleteTagGroup("g")).isEqualTo(EditResult.Ok())
        assertThat(db.tagDao().getGroups()).isEmpty()
        assertThat(catalog.deleteTagGroup("g")).isEqualTo(EditResult.NotFound)
    }

    // endregion

    // region reorder

    @Test
    fun reorderTagsWritesSortOrderForTheRowsThatMoved() = runTest {
        seedGroup("a", "b", "c")
        now = 5_000_000L

        assertThat(catalog.reorderTags("g", listOf("a", "c", "b"))).isEqualTo(EditResult.Ok())

        val tags = db.tagDao().getByGroup("g")
        assertThat(tags.map { it.id }).containsExactly("a", "c", "b").inOrder()
        assertThat(tags.map { it.sortOrder }).containsExactly(0, 1, 2).inOrder()
        // Only the rows that moved are rewritten.
        assertThat(db.tagDao().getById("a")!!.updatedAt).isEqualTo(1)
        assertThat(db.tagDao().getById("b")!!.updatedAt).isEqualTo(5_000_000L)
    }

    @Test
    fun staleTagReordersAreRejectedAndWriteNothing() = runTest {
        seedGroup("a", "b", "c")

        assertThat(catalog.reorderTags("g", listOf("c", "b"))).isEqualTo(EditResult.NotFound)
        assertThat(catalog.reorderTags("g", listOf("c", "b", "a", "x"))).isEqualTo(EditResult.NotFound)
        assertThat(catalog.reorderTags("g", listOf("c", "b", "x"))).isEqualTo(EditResult.NotFound)
        assertThat(catalog.reorderTags("g", listOf("c", "c", "b"))).isEqualTo(EditResult.NotFound)
        assertThat(catalog.reorderTags("missing", listOf("a"))).isEqualTo(EditResult.NotFound)

        assertThat(db.tagDao().getByGroup("g").map { it.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun reorderTagGroupsFollowsTheLiveSetAndRejectsStaleLists() = runTest {
        listOf("x", "y", "z").forEachIndexed { i, id ->
            db.tagDao().upsertGroup(TagGroup(id = id, name = id, colorArgb = 0, sortOrder = i, updatedAt = 1))
        }
        db.tagDao().upsertGroup(TagGroup(id = "dead", name = "dead", colorArgb = 0, sortOrder = 3, updatedAt = 1, deleted = true))

        assertThat(catalog.reorderTagGroups(listOf("z", "x"))).isEqualTo(EditResult.NotFound)
        assertThat(catalog.reorderTagGroups(listOf("z", "x", "y", "dead"))).isEqualTo(EditResult.NotFound)
        assertThat(db.tagDao().getGroups().map { it.id }).containsExactly("x", "y", "z").inOrder()

        assertThat(catalog.reorderTagGroups(listOf("z", "x", "y"))).isEqualTo(EditResult.Ok())

        assertThat(db.tagDao().getGroups().map { it.id }).containsExactly("z", "x", "y").inOrder()
    }

    // endregion
}
