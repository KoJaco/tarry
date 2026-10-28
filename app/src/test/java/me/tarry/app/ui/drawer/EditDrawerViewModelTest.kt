package me.tarry.app.ui.drawer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.EditClock
import me.tarry.core.data.repository.JobBlockRepository
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.MergeDirection
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineEditor
import me.tarry.core.data.repository.TimelineRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EditDrawerViewModelTest {
    private lateinit var db: TarryDatabase
    private lateinit var timeline: TimelineRepository
    private lateinit var places: PlaceRepository
    private lateinit var jobs: JobRepository
    private lateinit var jobBlocks: JobBlockRepository
    private lateinit var workManager: WorkManager
    private lateinit var undoEvents: UndoEvents
    private lateinit var viewModel: EditDrawerViewModel

    private val minute = 60_000L
    private val base = 1_800_000_000_000L
    private val zone = "Australia/Sydney"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        timeline = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        places = PlaceRepository(db.placeDao())
        // Workers hang instead of running segmentation: these tests only check that a run was requested.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(HangingWorkerFactory).build())
        workManager = WorkManager.getInstance(context)
        val scheduler = SegmentationScheduler(context, TrackingConfig())
        val editor = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(),
            db.segmentationCheckpointDao(), EditClock { base + 1_000 * minute },
        )
        undoEvents = UndoEvents(editor, scheduler)
        jobs = JobRepository(db.jobDao())
        jobBlocks = JobBlockRepository(db.jobBlockDao())
        val created = EditDrawerViewModel(timeline, places, editor, undoEvents, scheduler, jobs, jobBlocks)
        // Held in a store so tearDown can clear it (cancelling viewModelScope) before the database closes.
        viewModel = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = created as T
        })["vm", EditDrawerViewModel::class.java]
    }

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        // Cancel the view model's scope and wait for it: its state collector would otherwise keep observing Room after
        // db.close() and leak an exception into the next test (see TimelineViewModelTest).
        val scope = viewModel.viewModelScope.coroutineContext.job
        store.clear()
        runBlocking { withTimeout(10_000) { scope.join() } }
        // Cancel and wait so no WorkManager future outlives the Robolectric work database and leaks into later Compose tests.
        workManager.cancelAllWork().result.get()
        db.close()
        Dispatchers.resetMain()
    }

    private fun visit(id: String, startMin: Int, endMin: Int, placeId: String? = "p", frozen: Boolean = false, origin: VisitOrigin = VisitOrigin.DETECTED) =
        Visit(
            id = id, placeId = placeId, start = base + startMin * minute, end = base + endMin * minute, zoneId = zone,
            frozen = frozen, origin = origin, confidence = 1f, updatedAt = 0,
        )

    private fun place(id: String = "p", name: String = "Office") =
        Place(id = id, centroidLat = -33.0, centroidLng = 151.0, name = name, updatedAt = 0)

    private suspend fun seed(vararg visits: Visit) {
        places.save(place())
        visits.forEach { timeline.saveVisit(it) }
    }

    private fun awaitState(predicate: (EditDrawerState) -> Boolean = { true }): EditDrawerState =
        runBlocking { withTimeout(10_000) { viewModel.state.first { it != null && predicate(it) } } }!!

    @Test
    fun annotatingFreezesTheVisitAndRecordsTheNotes() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        assertThat(awaitState().frozen).isFalse()

        viewModel.setNotes("gate code 1234")

        val state = awaitState { it.notes == "gate code 1234" }
        assertThat(state.frozen).isTrue()
        val row = timeline.visit("v")!!
        assertThat(row.frozen).isTrue()
        assertThat(row.freeNotes).isEqualTo("gate code 1234")
        assertThat(row.updatedAt).isGreaterThan(0)
    }

    @Test
    fun frozenVisitAtADeletedPlaceKeepsItsHeaderButCannotRenameThePlace() = runBlocking<Unit> {
        places.save(place().copy(deleted = true))
        timeline.saveVisit(visit("v", 0, 60, frozen = true))
        viewModel.load(EditTarget.Visit("v"))

        val state = awaitState()

        assertThat(state.placeLabel).isEqualTo("Office")
        assertThat(state.hasPlace).isFalse() // hides Rename place
        assertThat(state.places).isEmpty() // pickers still exclude the deleted place
    }

    @Test
    fun deleteOffersAnUndoEventRequestsSegmentationAndDismissesTheDrawer() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        awaitState()
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.delete()

        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.edit_undo_deleted)
        assertThat(event.snapshot!!.visits.map { it.id }).contains("v")
        assertThat(timeline.visit("v")!!.deleted).isTrue()
        assertThat(withTimeout(10_000) { viewModel.events.first() }).isEqualTo(EditDrawerEvent.Dismiss)
        assertThat(workManager.getWorkInfosForUniqueWork(SegmentationScheduler.NOW).get()).hasSize(1)
        // Undo puts the exact row back.
        undoEvents.undo(event)
        assertThat(timeline.visit("v")!!.deleted).isFalse()
    }

    @Test
    fun splitOutsideTheVisitShowsAnInlineErrorAndOffersNoUndo() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        awaitState()
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.split(base - minute)

        assertThat(awaitState { it.error != null }.error).isEqualTo(EditError.EMPTY_RANGE)
        assertThat(offered.isCompleted).isFalse()
        offered.cancel()
        assertThat(timeline.visitsInRange(base - 10 * minute, base + 100 * minute)).hasSize(1)
        assertThat(timeline.visit("v")!!.frozen).isFalse()
    }

    @Test
    fun aValidEditClearsAPreviousError() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        viewModel.split(base - minute)
        awaitState { it.error == EditError.EMPTY_RANGE }

        viewModel.setNotes("checked the pump")

        val state = awaitState { it.error == null }
        assertThat(state.notes).isEqualTo("checked the pump")
    }

    @Test
    fun trimAndSplitOfferUndoAndKeepTheDrawerOnTheVisit() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        awaitState()

        viewModel.trimEnd(base + 50 * minute)

        val trimmed = awaitState { it.end == base + 50 * minute }
        assertThat(trimmed.frozen).isTrue()

        viewModel.split(base + 20 * minute)

        awaitState { it.end == base + 20 * minute }
        assertThat(timeline.visitsInRange(base, base + 100 * minute).map { it.origin }).containsExactly(VisitOrigin.SPLIT, VisitOrigin.SPLIT)
    }

    @Test
    fun mergingIntoThePreviousVisitRetargetsToTheKeptVisit() = runBlocking<Unit> {
        seed(visit("a", 0, 30), visit("b", 30, 60))
        viewModel.load(EditTarget.Visit("b"))
        awaitState()

        viewModel.merge(MergeDirection.PREVIOUS)

        assertThat(withTimeout(10_000) { viewModel.events.first() }).isEqualTo(EditDrawerEvent.Retarget(EditTarget.Visit("a")))
        assertThat(timeline.visit("b")!!.deleted).isTrue()
        assertThat(timeline.visit("a")!!.origin).isEqualTo(VisitOrigin.MERGED)
    }

    @Test
    fun mergeWithNothingNextIsAnInlineError() = runBlocking<Unit> {
        seed(visit("a", 0, 30))
        viewModel.load(EditTarget.Visit("a"))
        awaitState()

        viewModel.merge(MergeDirection.NEXT)

        assertThat(awaitState { it.error != null }.error).isEqualTo(EditError.NOT_FOUND)
    }

    @Test
    fun resetShowsForEditedDetectedVisitsAndClearsTheAnnotation() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        viewModel.setNotes("x")
        assertThat(awaitState { it.notes == "x" }.canReset).isTrue()

        viewModel.resetToDetected()

        val state = awaitState { it.notes.isEmpty() }
        assertThat(state.canReset).isFalse()
        assertThat(timeline.visit("v")!!.frozen).isFalse()
    }

    @Test
    fun manualVisitsCannotBeReset() = runBlocking<Unit> {
        seed(visit("v", 0, 60, frozen = true, origin = VisitOrigin.MANUAL))
        viewModel.load(EditTarget.Visit("v"))

        assertThat(awaitState().canReset).isFalse()
    }

    @Test
    fun changePlaceReassignsTheVisitAndListsKnownPlaces() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        places.save(place(id = "q", name = "Depot"))
        viewModel.load(EditTarget.Visit("v"))
        assertThat(awaitState { it.places.size == 2 }.places.map { it.label }).containsExactly("Office", "Depot")

        viewModel.changePlace("q")

        val state = awaitState { it.placeLabel == "Depot" }
        assertThat(state.frozen).isTrue()
        assertThat(timeline.visit("v")!!.placeId).isEqualTo("q")
    }

    @Test
    fun renamingThePlaceWritesTheNameAndBlankClearsIt() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        places.save(place().copy(address = "1 Main St"))
        viewModel.load(EditTarget.Visit("v"))
        assertThat(awaitState().placeLabel).isEqualTo("Office")

        viewModel.renamePlace("  Smith site ")

        val named = awaitState { it.placeLabel == "Smith site" }
        assertThat(named.placeName).isEqualTo("Smith site")
        assertThat(places.place("p")!!.name).isEqualTo("Smith site")

        viewModel.renamePlace("   ")

        val cleared = awaitState { it.placeName == null }
        assertThat(cleared.placeLabel).isEqualTo("1 Main St")
        assertThat(places.place("p")!!.name).isNull()
    }

    @Test
    fun tripAnnotationsFreezeTheTrip() = runBlocking<Unit> {
        timeline.saveTrip(
            Trip(id = "t", start = base, end = base + 20 * minute, zoneId = zone, distanceM = 5_000f, mode = TripMode.VEHICLE, updatedAt = 0),
        )
        viewModel.load(EditTarget.Trip("t"))
        val initial = awaitState()
        assertThat(initial.kind).isEqualTo(EditKind.TRIP)

        viewModel.setNotes("fuel stop")

        val state = awaitState { it.notes == "fuel stop" }
        assertThat(state.frozen).isTrue()
        assertThat(timeline.trip("t")!!.freeNotes).isEqualTo("fuel stop")
    }

    @Test
    fun newVisitTargetIsPrefilledAndListsThePlaces() = runBlocking<Unit> {
        places.save(place())
        viewModel.load(EditTarget.NewVisit(base, base + 30 * minute, contextTripId = "t"))

        val state = awaitState { it.places.isNotEmpty() }

        assertThat(state.kind).isEqualTo(EditKind.NEW_VISIT)
        assertThat(state.start).isEqualTo(base)
        assertThat(state.end).isEqualTo(base + 30 * minute)
        assertThat(state.places.map { it.id }).containsExactly("p")
    }

    @Test
    fun createManualMakesAFrozenManualVisitOffersUndoAndFollowsIt() = runBlocking<Unit> {
        places.save(place())
        viewModel.load(EditTarget.NewVisit(base, base + 30 * minute))
        awaitState()
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.createManual("p", base + 5 * minute, base + 25 * minute)

        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.edit_undo_visit_added)
        val created = timeline.visitsInRange(base - minute, base + 100 * minute).single()
        assertThat(created.placeId).isEqualTo("p")
        assertThat(created.start).isEqualTo(base + 5 * minute)
        assertThat(created.end).isEqualTo(base + 25 * minute)
        assertThat(created.origin).isEqualTo(VisitOrigin.MANUAL)
        assertThat(created.frozen).isTrue()
        assertThat(withTimeout(10_000) { viewModel.events.first() })
            .isEqualTo(EditDrawerEvent.Retarget(EditTarget.Visit(created.id)))
        assertThat(workManager.getWorkInfosForUniqueWork(SegmentationScheduler.NOW).get()).hasSize(1)

        undoEvents.undo(event)

        assertThat(timeline.visitsInRange(base - minute, base + 100 * minute)).isEmpty()
    }

    @Test
    fun createManualAtNewPlaceMakesTheVisitAndPlaceFollowsItAndOneUndoRemovesBoth() = runBlocking<Unit> {
        viewModel.load(EditTarget.NewVisit(base, base + 30 * minute))
        awaitState()
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.createManualAtNewPlace(-33.9, 151.2, base + 5 * minute, base + 25 * minute)

        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.edit_undo_visit_added)
        val created = timeline.visitsInRange(base - minute, base + 100 * minute).single()
        assertThat(created.origin).isEqualTo(VisitOrigin.MANUAL)
        assertThat(created.frozen).isTrue()
        val place = places.place(created.placeId!!)!!
        assertThat(place.centroidLat).isEqualTo(-33.9)
        assertThat(place.centroidLng).isEqualTo(151.2)
        assertThat(place.deleted).isFalse()
        assertThat(withTimeout(10_000) { viewModel.events.first() })
            .isEqualTo(EditDrawerEvent.Retarget(EditTarget.Visit(created.id)))
        assertThat(workManager.getWorkInfosForUniqueWork(SegmentationScheduler.NOW).get()).hasSize(1)

        undoEvents.undo(event)

        assertThat(timeline.visitsInRange(base - minute, base + 100 * minute)).isEmpty()
        assertThat(places.place(place.id)!!.deleted).isTrue()
    }

    @Test
    fun createManualAtNewPlaceOverAnotherVisitShowsOverlapAndCreatesNothing() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.NewVisit(base + 30 * minute, base + 90 * minute))
        awaitState()
        val placesBefore = places.observeAll().first().map { it.id }

        viewModel.createManualAtNewPlace(-33.9, 151.2, base + 30 * minute, base + 90 * minute)

        assertThat(awaitState { it.error != null }.error).isEqualTo(EditError.OVERLAP)
        assertThat(timeline.visitsInRange(base - minute, base + 100 * minute).map { it.id }).containsExactly("v")
        assertThat(places.observeAll().first().map { it.id }).containsExactlyElementsIn(placesBefore)
    }

    @Test
    fun createManualOverAnotherVisitShowsOverlapAndCreatesNothing() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.NewVisit(base + 30 * minute, base + 90 * minute))
        awaitState()
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.createManual("p", base + 30 * minute, base + 90 * minute)

        assertThat(awaitState { it.error != null }.error).isEqualTo(EditError.OVERLAP)
        assertThat(offered.isCompleted).isFalse()
        offered.cancel()
        assertThat(timeline.visitsInRange(base - minute, base + 100 * minute).map { it.id }).containsExactly("v")
    }

    @Test
    fun createManualWithEndBeforeStartShowsEmptyRangeAndCreatesNothing() = runBlocking<Unit> {
        places.save(place())
        viewModel.load(EditTarget.NewVisit(base, base + 30 * minute))
        awaitState()

        viewModel.createManual("p", base + 20 * minute, base + 10 * minute)

        assertThat(awaitState { it.error != null }.error).isEqualTo(EditError.EMPTY_RANGE)
        assertThat(timeline.visitsInRange(base - minute, base + 100 * minute)).isEmpty()
    }

    // region job blocks (phase 6b T5)

    private fun block(id: String, jobId: String, startMin: Int, endMin: Int, billable: Boolean = true, notes: String? = null) =
        JobBlock(
            id = id, jobId = jobId, start = base + startMin * minute, end = base + endMin * minute, zoneId = zone,
            billable = billable, notes = notes, updatedAt = 0,
        )

    private suspend fun seedJob(id: String = "j", name: String = "Acme", billable: Boolean = true) =
        jobs.save(Job(id = id, name = name, colorArgb = 1, defaultBillable = billable, updatedAt = 0))

    private fun liveBlocks() = runBlocking { jobBlocks.observeOverlapping(base - 1_000 * minute, base + 2_000 * minute).first() }

    @Test
    fun newJobNeedsAJobAndAnEndAfterTheStart() = runBlocking<Unit> {
        seedJob()
        viewModel.load(EditTarget.NewJob(base, base + 60 * minute))
        assertThat(awaitState().kind).isEqualTo(EditKind.NEW_JOB)

        viewModel.createBlock(null, base, base + 60 * minute, null, "")
        assertThat(awaitState { it.error != null }.error).isEqualTo(EditError.JOB_REQUIRED)

        viewModel.createBlock("j", base + 60 * minute, base + 60 * minute, null, "")
        assertThat(awaitState { it.error == EditError.EMPTY_RANGE }.error).isEqualTo(EditError.EMPTY_RANGE)
        assertThat(liveBlocks()).isEmpty()
    }

    @Test
    fun newJobSavesABlockBillablePerTheJobDefaultAndClosesTheDrawer() = runBlocking<Unit> {
        seedJob(billable = false)
        viewModel.load(EditTarget.NewJob(base, base + 60 * minute, jobId = "j"))
        val preselected = awaitState()
        assertThat(preselected.jobId).isEqualTo("j")
        assertThat(preselected.billable).isFalse() // the state's own default follows the job

        viewModel.createBlock("j", base + 10 * minute, base + 70 * minute, null, "site visit")

        assertThat(withTimeout(10_000) { viewModel.events.first() }).isEqualTo(EditDrawerEvent.Dismiss)
        val saved = liveBlocks().single()
        assertThat(saved.jobId).isEqualTo("j")
        assertThat(saved.start).isEqualTo(base + 10 * minute)
        assertThat(saved.end).isEqualTo(base + 70 * minute)
        assertThat(saved.billable).isFalse()
        assertThat(saved.notes).isEqualTo("site visit")
        assertThat(saved.zoneId).isEqualTo(java.time.ZoneId.systemDefault().id)
    }

    @Test
    fun newJobExplicitBillableOverridesTheJobDefault() = runBlocking<Unit> {
        seedJob(billable = false)
        viewModel.load(EditTarget.NewJob(base, base + 60 * minute))
        awaitState()

        viewModel.createBlock("j", base, base + 60 * minute, true, "")

        withTimeout(10_000) { viewModel.events.first() }
        assertThat(liveBlocks().single().billable).isTrue()
        assertThat(liveBlocks().single().notes).isNull()
    }

    @Test
    fun jobEditsSaveAsTheyAreMadeAndABadRangeStaysInlineWithoutSaving() = runBlocking<Unit> {
        seedJob()
        seedJob("k", "Beta", billable = false)
        jobBlocks.save(block("b", "j", 0, 60))
        viewModel.load(EditTarget.Job("b"))
        assertThat(awaitState().kind).isEqualTo(EditKind.JOB)

        viewModel.setBlockJob("k")
        viewModel.setBlockBillable(false)
        viewModel.setBlockStart(base + 5 * minute)
        viewModel.setBlockEnd(base + 90 * minute)
        viewModel.setNotes("invoice weekly")

        val state = awaitState { it.notes == "invoice weekly" && it.end == base + 90 * minute }
        assertThat(state.jobId).isEqualTo("k")
        assertThat(state.billable).isFalse()
        assertThat(state.start).isEqualTo(base + 5 * minute)
        val row = jobBlocks.byId("b")!!
        assertThat(row.jobId).isEqualTo("k")
        assertThat(row.notes).isEqualTo("invoice weekly")
        assertThat(row.updatedAt).isGreaterThan(0)

        viewModel.setBlockEnd(base) // before the start
        assertThat(awaitState { it.error == EditError.EMPTY_RANGE }.error).isEqualTo(EditError.EMPTY_RANGE)
        assertThat(jobBlocks.byId("b")!!.end).isEqualTo(base + 90 * minute)
        viewModel.setBlockBillable(true)
        awaitState { it.error == null }
    }

    @Test
    fun jobDrawerCountsTheVisitsAndTripsTheBlockCovers() = runBlocking<Unit> {
        seedJob()
        seed(visit("v1", 0, 30), visit("v2", 40, 60), visit("outside", 200, 230))
        timeline.saveTrip(Trip(id = "t", start = base + 30 * minute, end = base + 40 * minute, zoneId = zone, distanceM = 1f, mode = TripMode.VEHICLE, updatedAt = 0))
        jobBlocks.save(block("b", "j", 0, 100))
        viewModel.load(EditTarget.Job("b"))

        val state = awaitState()

        assertThat(state.coveredVisits).isEqualTo(2)
        assertThat(state.coveredTrips).isEqualTo(1)
    }

    @Test
    fun deletingABlockOffersUndoAndRestoreBringsItBack() = runBlocking<Unit> {
        seedJob()
        jobBlocks.save(block("b", "j", 0, 60))
        viewModel.load(EditTarget.Job("b"))
        awaitState()
        val offered = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

        viewModel.deleteBlock()

        val event = withTimeout(10_000) { offered.await() }
        assertThat(event.message).isEqualTo(R.string.edit_undo_job_deleted)
        assertThat(event.undoable).isTrue()
        assertThat(jobBlocks.byId("b")!!.deleted).isTrue()
        assertThat(withTimeout(10_000) { viewModel.events.first() }).isEqualTo(EditDrawerEvent.Dismiss)

        undoEvents.undo(event)

        assertThat(jobBlocks.byId("b")!!.deleted).isFalse()
    }

    @Test
    fun visitListsTheBlocksThatOverlapIt() = runBlocking<Unit> {
        seedJob()
        seedJob("k", "Beta")
        seed(visit("v", 0, 60))
        jobBlocks.save(block("b1", "j", 30, 90))
        jobBlocks.save(block("far", "k", 500, 600))
        viewModel.load(EditTarget.Visit("v"))

        val chips = awaitState().blocks

        assertThat(chips.map { it.id }).containsExactly("b1")
        assertThat(chips.single().job.name).isEqualTo("Acme")
    }

    @Test
    fun addToJobExtendsANearbyBlockOfTheSameJob() = runBlocking<Unit> {
        seedJob()
        seed(visit("v", 60, 90))
        jobBlocks.save(block("b", "j", 0, 50, billable = false)) // ends 10 min before the visit: within the join window
        viewModel.load(EditTarget.Visit("v"))
        awaitState()

        viewModel.addToJob("j")

        val state = awaitState { it.blocks.any { c -> c.end == base + 90 * minute } }
        assertThat(state.blocks.map { it.id }).containsExactly("b")
        val row = jobBlocks.byId("b")!!
        assertThat(row.start).isEqualTo(base)
        assertThat(row.end).isEqualTo(base + 90 * minute)
        assertThat(row.billable).isFalse() // an extension keeps the block's own flag
        assertThat(liveBlocks()).hasSize(1)
    }

    @Test
    fun addToJobCreatesABlockEqualToTheSegmentWhenNoneIsNear() = runBlocking<Unit> {
        seedJob(billable = false)
        seedJob("k", "Beta")
        seed(visit("v", 60, 90))
        jobBlocks.save(block("other-job", "k", 55, 95)) // overlaps but is another job
        jobBlocks.save(block("far", "j", 300, 360)) // same job but outside the window
        viewModel.load(EditTarget.Visit("v"))
        awaitState()

        viewModel.addToJob("j")

        awaitState { it.blocks.size == 2 }
        val created = liveBlocks().single { it.id != "other-job" && it.id != "far" }
        assertThat(created.jobId).isEqualTo("j")
        assertThat(created.start).isEqualTo(base + 60 * minute)
        assertThat(created.end).isEqualTo(base + 90 * minute)
        assertThat(created.billable).isFalse()
    }

    @Test
    fun addToJobWorksFromATripToo() = runBlocking<Unit> {
        seedJob()
        timeline.saveTrip(Trip(id = "t", start = base, end = base + 20 * minute, zoneId = zone, distanceM = 1f, mode = TripMode.VEHICLE, updatedAt = 0))
        viewModel.load(EditTarget.Trip("t"))
        awaitState()

        viewModel.addToJob("j")

        val created = awaitState { it.blocks.size == 1 }.blocks.single()
        assertThat(created.start).isEqualTo(base)
        assertThat(created.end).isEqualTo(base + 20 * minute)
    }


    // region same-job blocks never overlap

    @Test
    fun addToJobBridgingTwoBlocksEndsAsOneBlock() = runBlocking<Unit> {
        seedJob()
        seed(visit("v", 90, 210)) // 9:30-11:30 relative to A 9-10 (60..120) and B 11-12 (180..240)
        jobBlocks.save(block("A", "j", 60, 120, billable = false, notes = "a"))
        jobBlocks.save(block("B", "j", 180, 240, notes = "b"))
        viewModel.load(EditTarget.Visit("v"))
        awaitState()

        viewModel.addToJob("j")

        awaitState { it.blocks.size == 1 }
        val live = liveBlocks().single()
        assertThat(live.id).isEqualTo("A")
        assertThat(live.start).isEqualTo(base + 60 * minute)
        assertThat(live.end).isEqualTo(base + 240 * minute)
        assertThat(live.billable).isFalse()
        assertThat(live.notes).isEqualTo("a\nb")
        assertThat(jobBlocks.byId("B")!!.deleted).isTrue()
    }

    @Test
    fun newJobOverlappingASameJobBlockMergesButADifferentJobStaysSeparate() = runBlocking<Unit> {
        seedJob()
        seedJob("k", "Beta")
        jobBlocks.save(block("same", "j", 0, 60))
        jobBlocks.save(block("other", "k", 0, 60))
        viewModel.load(EditTarget.NewJob(base, base + 60 * minute))
        awaitState()

        viewModel.createBlock("j", base + 30 * minute, base + 120 * minute, null, "")
        withTimeout(10_000) { viewModel.events.first() }

        val live = liveBlocks()
        assertThat(live.map { it.id }).containsExactly("same", "other")
        assertThat(live.single { it.id == "same" }.end).isEqualTo(base + 120 * minute)
        assertThat(live.single { it.id == "other" }.end).isEqualTo(base + 60 * minute)
    }

    @Test
    fun editingAJobEndOverALaterSameJobBlockMergesThem() = runBlocking<Unit> {
        seedJob()
        jobBlocks.save(block("a", "j", 0, 60))
        jobBlocks.save(block("b", "j", 120, 180))
        viewModel.load(EditTarget.Job("a"))
        awaitState()

        viewModel.setBlockEnd(base + 150 * minute)

        awaitState { it.end == base + 180 * minute }
        assertThat(liveBlocks().map { it.id }).containsExactly("a")
        assertThat(jobBlocks.byId("b")!!.deleted).isTrue()
    }

    @Test
    fun changingABlocksJobMergesItIntoTheNewJobsOverlappingBlock() = runBlocking<Unit> {
        seedJob()
        seedJob("k", "Beta")
        jobBlocks.save(block("a", "j", 0, 60))
        jobBlocks.save(block("b", "k", 30, 90))
        viewModel.load(EditTarget.Job("a"))
        awaitState()

        viewModel.setBlockJob("k")

        awaitState { it.jobId == "k" }
        assertThat(liveBlocks().map { it.id }).containsExactly("a")
        assertThat(liveBlocks().single().end).isEqualTo(base + 90 * minute)
    }

    // endregion
    @Test
    fun placeDefaultJobIsSetAndCleared() = runBlocking<Unit> {
        seedJob()
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        assertThat(awaitState().placeDefaultJob).isNull()

        viewModel.setPlaceDefaultJob("j")
        assertThat(awaitState { it.placeDefaultJob != null }.placeDefaultJob!!.id).isEqualTo("j")
        assertThat(places.place("p")!!.defaultJobId).isEqualTo("j")

        viewModel.setPlaceDefaultJob(null)
        awaitState { it.placeDefaultJob == null }
        assertThat(places.place("p")!!.defaultJobId).isNull()
    }

    @Test
    fun placeDefaultJobIsHiddenForAnIgnoredPlace() = runBlocking<Unit> {
        seedJob()
        places.save(place().copy(ignored = true, defaultJobId = "j"))
        timeline.saveVisit(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))

        assertThat(awaitState().placeDefaultJob).isNull()
    }

    @Test
    fun createJobReportsTheNewIdAndABlankNameStaysInline() = runBlocking<Unit> {
        viewModel.load(EditTarget.NewJob(base, base + 60 * minute))
        awaitState()
        var created: String? = null

        viewModel.createJob("  Bathroom reno ") { created = it }
        withTimeout(10_000) { while (created == null) delay(10) }

        val id = created!!
        assertThat(jobs.job(id)!!.name).isEqualTo("Bathroom reno")
        assertThat(awaitState { s -> s.jobs.any { it.id == id } }.jobs.single { it.id == id }.defaultBillable).isTrue()

        viewModel.createJob("   ") { created = "should not be called" }
        assertThat(awaitState { it.error == EditError.BLANK_NAME }.error).isEqualTo(EditError.BLANK_NAME)
        assertThat(created).isEqualTo(id)
    }

    // endregion
}

private object HangingWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
        object : CoroutineWorker(appContext, workerParameters) {
            override suspend fun doWork(): Result = awaitCancellation()
        }
}
