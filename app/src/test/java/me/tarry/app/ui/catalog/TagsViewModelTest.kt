package me.tarry.app.ui.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.app.R
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.CatalogEditor
import me.tarry.core.data.repository.EditClock
import me.tarry.core.data.repository.TagRepository
import me.tarry.core.data.repository.TimelineEditor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TagsViewModelTest {
    private lateinit var db: TarryDatabase
    private lateinit var workManager: WorkManager
    private lateinit var undoEvents: UndoEvents
    private lateinit var viewModel: TagsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(HangingWorkerFactory).build())
        workManager = WorkManager.getInstance(context)
        val clock = EditClock { 1_000_000L }
        val timeline = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
            db.segmentationCheckpointDao(), clock,
        )
        val catalog = CatalogEditor(db, db.jobDao(), db.tagDao(), db.placeDao(), clock)
        undoEvents = UndoEvents(timeline, SegmentationScheduler(context, TrackingConfig()))
        viewModel = TagsViewModel(TagRepository(db.tagDao()), catalog, timeline, undoEvents)
    }

    @After
    fun tearDown() {
        // Cancel and wait so no WorkManager future outlives the Robolectric work database and leaks into later Compose tests.
        workManager.cancelAllWork().result.get()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun group(id: String, order: Int, color: Int = 0) =
        db.tagDao().upsertGroup(TagGroup(id = id, name = id.uppercase(), colorArgb = color, sortOrder = order, updatedAt = 0))

    private suspend fun tag(id: String, group: String, order: Int, archived: Boolean = false) =
        db.tagDao().upsert(Tag(id = id, groupId = group, name = id, sortOrder = order, archived = archived, updatedAt = 0))

    private fun awaitState(predicate: (TagsUiState) -> Boolean = { true }): TagsUiState =
        runBlocking { withTimeout(10_000) { viewModel.state.first { it != null && predicate(it) } } }!!

    @Test
    fun groupsFollowTheirOrderAndKeepArchivedTagsInTheStoredOrder() = runBlocking<Unit> {
        group("g2", 1)
        group("g1", 0, color = 5)
        tag("b", "g1", 1)
        tag("a", "g1", 0)
        tag("old", "g1", 2, archived = true)
        db.tagDao().upsert(Tag(id = "gone", groupId = "g1", name = "gone", sortOrder = 3, updatedAt = 0, deleted = true))

        val state = awaitState()

        assertThat(state.groups.map { it.id }).containsExactly("g1", "g2").inOrder()
        val g1 = state.groups.first()
        assertThat(g1.colorArgb).isEqualTo(5)
        assertThat(g1.activeTags.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(g1.archivedTags.map { it.id }).containsExactly("old")
        assertThat(state.groups.last().tags).isEmpty()
    }

    @Test
    fun createGroupAndTag() = runBlocking<Unit> {
        viewModel.createGroup("Materials")
        val groupId = awaitState { it.groups.isNotEmpty() }.groups.single().id

        viewModel.createTag(groupId, " Timber ")

        val g = awaitState { it.groups.single().tags.isNotEmpty() }.groups.single()
        assertThat(g.name).isEqualTo("Materials")
        assertThat(g.tags.single().name).isEqualTo("Timber")
    }

    @Test
    fun renameAndRecolourAGroupAndRenameATag() = runBlocking<Unit> {
        group("g", 0)
        tag("t", "g", 0)

        viewModel.renameGroup("g", "Site")
        viewModel.setGroupColour("g", 0xFF112233.toInt())
        viewModel.renameTag("t", "Concrete")

        val g = awaitState { it.groups.single().name == "Site" && it.groups.single().tags.single().name == "Concrete" }.groups.single()
        assertThat(g.colorArgb).isEqualTo(0xFF112233.toInt())
    }

    @Test
    fun reorderTagsKeepsArchivedTagsAfterTheActiveOnes() = runBlocking<Unit> {
        group("g", 0)
        tag("a", "g", 0)
        tag("old", "g", 1, archived = true)
        tag("b", "g", 2)

        viewModel.reorderTags("g", listOf("b", "a"))

        val g = awaitState { it.groups.single().activeTags.map { t -> t.id } == listOf("b", "a") }.groups.single()
        assertThat(g.tags.map { it.id }).containsExactly("b", "a", "old").inOrder()
    }

    @Test
    fun aStaleReorderChangesNothing() = runBlocking<Unit> {
        group("g", 0)
        tag("a", "g", 0)
        tag("b", "g", 1)

        viewModel.reorderTags("g", listOf("b", "a", "ghost"))

        assertThat(db.tagDao().getByGroup("g").map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun reorderGroups() = runBlocking<Unit> {
        group("x", 0)
        group("y", 1)

        viewModel.reorderGroups(listOf("y", "x"))

        assertThat(awaitState { it.groups.first().id == "y" }.groups.map { it.id }).containsExactly("y", "x").inOrder()
    }

    @Test
    fun archivingATagOffersUndoThatRestoresItsPlaceDefaults() = runBlocking<Unit> {
        group("g", 0)
        tag("t", "g", 0)
        db.placeDao().upsert(Place(id = "p", centroidLat = 1.0, centroidLng = 1.0, updatedAt = 0))
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("p", "t"))
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.archiveTag("t")

        assertThat(awaitState { it.groups.single().archivedTags.isNotEmpty() }.groups.single().activeTags).isEmpty()
        assertThat(db.placeDao().getDefaultTags("p")).isEmpty()
        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.tags_undo_archived)

        undoEvents.undo(event)

        assertThat(awaitState { it.groups.single().activeTags.isNotEmpty() }.groups.single().archivedTags).isEmpty()
        assertThat(db.placeDao().getDefaultTags("p").map { it.tagId }).containsExactly("t")
    }

    @Test
    fun archiveAllOffersOneUndoAndUnarchiveBringsATagBack() = runBlocking<Unit> {
        group("g", 0)
        tag("a", "g", 0)
        tag("b", "g", 1)
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.archiveAll("g")

        assertThat(awaitState { it.groups.single().activeTags.isEmpty() }.groups.single().archivedTags).hasSize(2)
        assertThat(withTimeout(10_000) { offered.await() }.message).isEqualTo(R.string.tags_undo_archived_all)

        viewModel.unarchiveTag("a")

        assertThat(awaitState { it.groups.single().activeTags.isNotEmpty() }.groups.single().activeTags.map { it.id }).containsExactly("a")
    }

    @Test
    fun deleteIsBlockedWhileATagIsUsedOrAGroupHasTags() = runBlocking<Unit> {
        group("g", 0)
        group("empty", 1)
        tag("used", "g", 0)
        tag("free", "g", 1)
        db.visitDao().upsert(
            Visit(id = "v", placeId = null, start = 0, end = 1000, zoneId = "UTC", origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0),
        )
        db.visitDao().upsertTag(VisitTag("v", "used"))

        assertThat(viewModel.tagUses("used")).isEqualTo(1)
        assertThat(viewModel.tagUses("free")).isEqualTo(0)
        viewModel.deleteTag("used")
        viewModel.deleteTag("free")
        viewModel.deleteGroup("g")
        viewModel.deleteGroup("empty")

        val state = awaitState { it.groups.size == 1 }
        assertThat(state.groups.single().id).isEqualTo("g")
        assertThat(state.groups.single().tags.map { it.id }).containsExactly("used")
    }

    @Test
    fun aRefusedTagEditShowsANoticeWithNoUndoAction() = runBlocking<Unit> {
        group("g", 0)
        tag("t", "g", 0)
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.renameTag("t", "  ")

        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.edit_error_blank_name)
        assertThat(event.snapshot).isNull()
    }
}
