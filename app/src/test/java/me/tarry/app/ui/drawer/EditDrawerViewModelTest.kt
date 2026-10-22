package me.tarry.app.ui.drawer

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
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.EditClock
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.MergeDirection
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TagRepository
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
    private lateinit var tags: TagRepository
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
        jobs = JobRepository(db.jobDao())
        tags = TagRepository(db.tagDao())
        // Workers hang instead of running segmentation: these tests only check that a run was requested.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(HangingWorkerFactory).build())
        workManager = WorkManager.getInstance(context)
        val scheduler = SegmentationScheduler(context, TrackingConfig())
        val editor = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
            db.segmentationCheckpointDao(), EditClock { base + 1_000 * minute },
        )
        undoEvents = UndoEvents(editor, scheduler)
        viewModel = EditDrawerViewModel(timeline, places, jobs, tags, editor, undoEvents, scheduler)
    }

    @After
    fun tearDown() {
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

    private fun place(id: String = "p", name: String = "Office", defaultJobId: String? = null, defaultBillable: Boolean? = null) =
        Place(id = id, centroidLat = -33.0, centroidLng = 151.0, name = name, defaultJobId = defaultJobId, defaultBillable = defaultBillable, updatedAt = 0)

    private suspend fun seed(vararg visits: Visit) {
        places.save(place())
        visits.forEach { timeline.saveVisit(it) }
    }

    private fun awaitState(predicate: (EditDrawerState) -> Boolean = { true }): EditDrawerState =
        runBlocking { withTimeout(10_000) { viewModel.state.first { it != null && predicate(it) } } }!!

    @Test
    fun annotatingFreezesTheVisitAndRecordsTheJob() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        viewModel.load(EditTarget.Visit("v"))
        assertThat(awaitState().needsReview).isTrue()
        assertThat(awaitState().frozen).isFalse()

        viewModel.setJob("j")

        val state = awaitState { it.ownJob != null }
        assertThat(state.ownJob?.id).isEqualTo("j")
        assertThat(state.needsReview).isFalse()
        assertThat(state.frozen).isTrue()
        val row = timeline.visit("v")!!
        assertThat(row.frozen).isTrue()
        assertThat(row.jobId).isEqualTo("j")
        assertThat(row.updatedAt).isGreaterThan(0)
    }

    @Test
    fun placeDefaultsAreInheritedUntilTheVisitSetsItsOwn() = runBlocking<Unit> {
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        jobs.save(Job(id = "k", name = "Beta", colorArgb = 2, updatedAt = 0))
        places.save(place(defaultJobId = "j", defaultBillable = true))
        timeline.saveVisit(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))

        val inherited = awaitState()
        assertThat(inherited.ownJob).isNull()
        assertThat(inherited.placeJob?.id).isEqualTo("j")
        assertThat(inherited.resolvedJob?.id).isEqualTo("j")
        assertThat(inherited.needsReview).isFalse()
        assertThat(inherited.billableInherited).isTrue()
        assertThat(inherited.resolvedBillable).isTrue()

        viewModel.setJob("k")
        viewModel.setBillable(false)

        val own = awaitState { it.ownJob != null && it.billable != null }
        assertThat(own.resolvedJob?.id).isEqualTo("k")
        assertThat(own.billableInherited).isFalse()
        assertThat(own.resolvedBillable).isFalse()
    }

    @Test
    fun setAsPlaceDefaultWritesTheResolvedValuesToThePlace() = runBlocking<Unit> {
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        viewModel.setJob("j")
        viewModel.setBillable(true)
        awaitState { it.ownJob != null && it.billable == true }

        viewModel.setPlaceDefault(true)

        val state = awaitState { it.isPlaceDefault }
        assertThat(state.placeJob?.id).isEqualTo("j")
        val saved = places.place("p")!!
        assertThat(saved.defaultJobId).isEqualTo("j")
        assertThat(saved.defaultBillable).isTrue()

        viewModel.setPlaceDefault(false)

        awaitState { !it.isPlaceDefault && it.placeJob == null }
        assertThat(places.place("p")!!.defaultJobId).isNull()
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
        assertThat(event.snapshot.visits.map { it.id }).contains("v")
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
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        viewModel.setJob("j")
        assertThat(awaitState { it.ownJob != null }.canReset).isTrue()

        viewModel.resetToDetected()

        val state = awaitState { it.ownJob == null }
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
    fun newJobTakesTheNextPaletteSlotAndIsAppliedToTheVisit() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))

        viewModel.createJob("Antifoul")

        val state = awaitState { it.ownJob != null }
        assertThat(state.ownJob?.name).isEqualTo("Antifoul")
        assertThat(state.ownJob?.colorArgb).isEqualTo(JobPalette.slots.first().key)
        assertThat(timeline.visit("v")!!.jobId).isEqualTo(state.ownJob?.id)
    }

    @Test
    fun blankNewJobNameIsAnInlineError() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        awaitState()

        viewModel.createJob("   ")

        assertThat(awaitState { it.error != null }.error).isEqualTo(EditError.BLANK_NAME)
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
    fun newTagIsCreatedSelectedAndToggleable() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))

        viewModel.createTag("Parts")

        val created = awaitState { it.tagIds.isNotEmpty() }
        val tag = created.tagGroups.single().tags.single()
        assertThat(tag.name).isEqualTo("Parts")
        assertThat(created.tagIds).containsExactly(tag.id)
        assertThat(timeline.visit("v")!!.frozen).isTrue()

        viewModel.toggleTag(tag.id)

        awaitState { it.tagIds.isEmpty() }
        assertThat(timeline.visitTags("v")).isEmpty()
    }

    @Test
    fun placeDefaultTagsShowAsInheritedAndTogglingStartsFromThem() = runBlocking<Unit> {
        seed(visit("v", 0, 60))
        viewModel.load(EditTarget.Visit("v"))
        viewModel.createTag("Parts")
        val parts = awaitState { it.tagIds.isNotEmpty() }.tagGroups.single().tags.single()
        viewModel.createTag("Tools")
        val tools = awaitState { it.tagIds.size == 2 }.tagGroups.single().tags.first { it.name == "Tools" }
        places.addDefaultTag(me.tarry.core.data.db.entity.PlaceDefaultTag("p", parts.id))
        timeline.saveVisit(visit("w", 100, 160))

        viewModel.load(EditTarget.Visit("w"))

        val inherited = awaitState { it.id == "w" }
        assertThat(inherited.tagsInherited).isTrue()
        assertThat(inherited.tagIds).containsExactly(parts.id)

        viewModel.toggleTag(tools.id)

        val own = awaitState { it.id == "w" && !it.tagsInherited }
        assertThat(own.tagIds).containsExactly(parts.id, tools.id)
    }

    @Test
    fun tripAttributionAndAnnotationsFreezeTheTrip() = runBlocking<Unit> {
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        timeline.saveTrip(
            Trip(id = "t", start = base, end = base + 20 * minute, zoneId = zone, distanceM = 5_000f, mode = TripMode.VEHICLE, updatedAt = 0),
        )
        viewModel.load(EditTarget.Trip("t"))
        val initial = awaitState()
        assertThat(initial.kind).isEqualTo(EditKind.TRIP)
        assertThat(initial.needsReview).isFalse()

        viewModel.setJob("j")
        viewModel.setNotes("fuel stop")

        val state = awaitState { it.ownJob != null && it.notes == "fuel stop" }
        assertThat(state.frozen).isTrue()
        assertThat(timeline.trip("t")!!.attributedJobId).isEqualTo("j")

        viewModel.setJob(null)

        awaitState { it.ownJob == null }
        assertThat(timeline.trip("t")!!.attributedJobId).isNull()
    }

    @Test
    fun newVisitTargetHasATitleOnlyState() = runBlocking<Unit> {
        viewModel.load(EditTarget.NewVisit(base, base + 30 * minute, contextTripId = "t"))

        val state = awaitState()

        assertThat(state.kind).isEqualTo(EditKind.NEW_VISIT)
        assertThat(state.start).isEqualTo(base)
        assertThat(state.end).isEqualTo(base + 30 * minute)
    }
}

private object HangingWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
        object : CoroutineWorker(appContext, workerParameters) {
            override suspend fun doWork(): Result = awaitCancellation()
        }
}
