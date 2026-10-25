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
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.CatalogEditor
import me.tarry.core.data.repository.EditClock
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.PlaceRepository
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
class JobsViewModelTest {
    private lateinit var db: TarryDatabase
    private lateinit var workManager: WorkManager
    private lateinit var undoEvents: UndoEvents
    private lateinit var viewModel: JobsViewModel

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
        viewModel = JobsViewModel(JobRepository(db.jobDao()), PlaceRepository(db.placeDao()), catalog, timeline, undoEvents)
    }

    @After
    fun tearDown() {
        // Cancel and wait so no WorkManager future outlives the Robolectric work database and leaks into later Compose tests.
        workManager.cancelAllWork().result.get()
        db.close()
        Dispatchers.resetMain()
    }

    private fun job(id: String, name: String = id, archived: Boolean = false) =
        Job(id = id, name = name, colorArgb = 1, archived = archived, updatedAt = 0)

    private fun place(id: String, defaultJobId: String? = null) =
        Place(id = id, centroidLat = 1.0, centroidLng = 1.0, defaultJobId = defaultJobId, updatedAt = 0)

    private fun awaitState(predicate: (JobsUiState) -> Boolean = { true }): JobsUiState =
        runBlocking { withTimeout(10_000) { viewModel.state.first { it != null && predicate(it) } } }!!

    @Test
    fun listsActiveJobsByNameIgnoringCaseAndArchivedSeparatelyWithPlaceDefaultCounts() = runBlocking<Unit> {
        db.jobDao().upsert(job("b", "bravo"))
        db.jobDao().upsert(job("a", "Alpha"))
        db.jobDao().upsert(job("z", "Zulu", archived = true))
        db.jobDao().upsert(job("gone").copy(deleted = true))
        db.placeDao().upsert(place("p1", "a"))
        db.placeDao().upsert(place("p2", "a"))

        val state = awaitState()

        assertThat(state.active.map { it.name }).containsExactly("Alpha", "bravo").inOrder()
        assertThat(state.archived.map { it.name }).containsExactly("Zulu")
        assertThat(state.active.first().placeDefaults).isEqualTo(2)
        assertThat(state.active.last().placeDefaults).isEqualTo(0)
    }

    @Test
    fun createPicksTheFirstUnusedPaletteColour() = runBlocking<Unit> {
        viewModel.create("  Acme ")

        val row = awaitState { it.active.isNotEmpty() }.active.single()
        assertThat(row.name).isEqualTo("Acme")
        assertThat(row.colorArgb).isEqualTo(JobPalette.slots.first().key)
    }

    @Test
    fun renameAndRecolour() = runBlocking<Unit> {
        db.jobDao().upsert(job("j", "Old"))

        viewModel.rename("j", "New")
        viewModel.setColour("j", JobPalette.slots[3].key)

        // Two separate transactions: the rename can land (and emit) before the recolour, so wait for both. Each edit
        // re-reads the row, so neither overwrites the other.
        val row = awaitState { s -> s.active.singleOrNull()?.let { it.name == "New" && it.colorArgb == JobPalette.slots[3].key } == true }
            .active.single()
        assertThat(row.name).isEqualTo("New")
        assertThat(row.colorArgb).isEqualTo(JobPalette.slots[3].key)
    }

    @Test
    fun archiveMovesTheJobToArchivedOffersUndoAndUndoRestoresPlaceDefaults() = runBlocking<Unit> {
        db.jobDao().upsert(job("j"))
        db.placeDao().upsert(place("p", "j"))
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.archive("j")

        val state = awaitState { it.archived.isNotEmpty() }
        assertThat(state.active).isEmpty()
        assertThat(db.placeDao().getById("p")!!.defaultJobId).isNull()
        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.jobs_undo_archived)

        undoEvents.undo(event)

        assertThat(awaitState { it.active.isNotEmpty() }.active.single().id).isEqualTo("j")
        assertThat(db.placeDao().getById("p")!!.defaultJobId).isEqualTo("j")
    }

    @Test
    fun unarchiveBringsTheJobBack() = runBlocking<Unit> {
        db.jobDao().upsert(job("j", archived = true))

        viewModel.unarchive("j")

        assertThat(awaitState { it.active.isNotEmpty() }.archived).isEmpty()
    }

    @Test
    fun deleteIsBlockedWhileInUseAndRemovesAnUnusedJob() = runBlocking<Unit> {
        db.jobDao().upsert(job("used"))
        db.jobDao().upsert(job("free"))
        db.visitDao().upsert(
            Visit(id = "v", placeId = null, start = 0, end = 1000, zoneId = "UTC", jobId = "used", origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0),
        )

        assertThat(viewModel.uses("used")).isEqualTo(1)
        assertThat(viewModel.uses("free")).isEqualTo(0)
        viewModel.delete("used")
        viewModel.delete("free")

        assertThat(awaitState { it.active.size == 1 }.active.single().id).isEqualTo("used")
    }

    @Test
    fun aRefusedDeleteOrBlankRenameShowsANoticeWithNoUndoAction() = runBlocking<Unit> {
        db.jobDao().upsert(job("used"))
        db.visitDao().upsert(
            Visit(id = "v", placeId = null, start = 0, end = 1000, zoneId = "UTC", jobId = "used", origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0),
        )
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.delete("used")

        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.edit_error_in_use)
        assertThat(event.snapshot).isNull()

        val blank = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }
        viewModel.rename("used", "  ")
        assertThat(withTimeout(10_000) { blank.await() }.message).isEqualTo(R.string.edit_error_blank_name)
    }
}
