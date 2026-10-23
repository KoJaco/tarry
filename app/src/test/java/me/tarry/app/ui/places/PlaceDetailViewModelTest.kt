package me.tarry.app.ui.places

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.app.R
import me.tarry.app.segmentation.PlaceRadiusPreviewLoader
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.ui.catalog.HangingWorkerFactory
import me.tarry.app.ui.undo.UndoEvents
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Job
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
import me.tarry.core.data.repository.JobRepository
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
class PlaceDetailViewModelTest {
    private lateinit var db: TarryDatabase
    private lateinit var workManager: WorkManager
    private lateinit var undoEvents: UndoEvents
    private lateinit var viewModel: PlaceDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(HangingWorkerFactory).build())
        workManager = WorkManager.getInstance(context)
        val scheduler = SegmentationScheduler(context, TrackingConfig())
        val timeline = TimelineEditor(
            db, db.visitDao(), db.tripDao(), db.excludedRangeDao(), db.placeDao(), db.jobDao(), db.tagDao(),
            db.segmentationCheckpointDao(), EditClock { NOW },
        )
        undoEvents = UndoEvents(timeline, scheduler)
        viewModel = PlaceDetailViewModel(
            PlaceRepository(db.placeDao()),
            TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao()),
            JobRepository(db.jobDao()),
            TagRepository(db.tagDao()),
            timeline,
            PlaceRadiusPreviewLoader(db.placeDao(), db.visitDao(), db.locationPingDao()),
            undoEvents,
            scheduler,
        )
    }

    @After
    fun tearDown() {
        // Cancel and wait so no WorkManager future outlives the Robolectric work database.
        workManager.cancelAllWork().result.get()
        db.close()
        Dispatchers.resetMain()
    }

    private fun place(id: String, lat: Double = 1.0, radius: Float = 75f, name: String? = null) =
        Place(id = id, centroidLat = lat, centroidLng = 1.0, radiusM = radius, name = name, lastVisitAt = 1, updatedAt = 0)

    private fun visit(id: String, placeId: String, frozen: Boolean = false, start: Long = 1_000, end: Long = 600_000) = Visit(
        id = id, placeId = placeId, start = start, end = end, zoneId = "UTC", origin = VisitOrigin.DETECTED, confidence = 1f,
        frozen = frozen, updatedAt = 0,
    )

    private fun awaitLoad(predicate: (PlaceDetailLoad) -> Boolean): PlaceDetailLoad =
        runBlocking { withTimeout(10_000) { viewModel.state.first(predicate) } }

    private fun ready(predicate: (PlaceDetailUi) -> Boolean = { true }): PlaceDetailUi =
        (awaitLoad { it is PlaceDetailLoad.Ready && predicate(it.ui) } as PlaceDetailLoad.Ready).ui

    private fun nextEvent() = CoroutineScope(Dispatchers.Unconfined).async { undoEvents.events.first() }

    private fun enqueued() = workManager.getWorkInfosForUniqueWork(SegmentationScheduler.NOW).get().size

    @Test
    fun showsTheDetailsAndHidesArchivedJobsAndTagsUnlessCurrent() = runBlocking<Unit> {
        db.jobDao().upsert(Job(id = "live", name = "Live", colorArgb = 1, updatedAt = 0))
        db.jobDao().upsert(Job(id = "old", name = "Old", colorArgb = 1, archived = true, updatedAt = 0))
        db.jobDao().upsert(Job(id = "gone", name = "Gone", colorArgb = 1, archived = true, updatedAt = 0))
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 0))
        db.tagDao().upsert(Tag(id = "t1", groupId = "g", name = "Applied", sortOrder = 0, archived = true, updatedAt = 0))
        db.tagDao().upsert(Tag(id = "t2", groupId = "g", name = "Hidden", sortOrder = 1, archived = true, updatedAt = 0))
        db.tagDao().upsert(Tag(id = "t3", groupId = "g", name = "Open", sortOrder = 2, updatedAt = 0))
        db.placeDao().upsert(place("p", name = "Shop").copy(defaultJobId = "old", defaultBillable = true))
        db.placeDao().upsert(place("q", name = "Other"))
        db.placeDao().upsertDefaultTag(PlaceDefaultTag("p", "t1"))

        viewModel.load("p")
        val ui = ready()

        assertThat(ui.label).isEqualTo("Shop")
        assertThat(ui.defaultJob?.id).isEqualTo("old")
        assertThat(ui.pickableJobs.map { it.id }).containsExactly("live", "old")
        assertThat(ui.defaultBillable).isTrue()
        assertThat(ui.tagIds).containsExactly("t1")
        assertThat(ui.pickableTagGroups.single().tags.map { it.id }).containsExactly("t1", "t3")
        assertThat(ui.otherPlaces.map { it.id }).containsExactly("q")
    }

    @Test
    fun renameSetsAndBlankClearsTheName() = runBlocking<Unit> {
        db.placeDao().upsert(place("p"))
        viewModel.load("p")

        viewModel.rename(" Depot ")
        assertThat(ready { it.name == "Depot" }.label).isEqualTo("Depot")
        viewModel.rename("")
        assertThat(ready { it.name == null }.name).isNull()
    }

    @Test
    fun eachDefaultChangeKeepsTheOtherDefaults() = runBlocking<Unit> {
        db.jobDao().upsert(Job(id = "j", name = "J", colorArgb = 1, updatedAt = 0))
        db.tagDao().upsertGroup(TagGroup(id = "g", name = "G", colorArgb = 0, sortOrder = 0, updatedAt = 0))
        db.tagDao().upsert(Tag(id = "t", groupId = "g", name = "T", sortOrder = 0, updatedAt = 0))
        db.placeDao().upsert(place("p"))
        viewModel.load("p")

        viewModel.setDefaultJob("j")
        viewModel.setDefaultBillable(false)
        viewModel.setDefaultTags(setOf("t"))

        val ui = ready { it.tagIds.isNotEmpty() && it.defaultBillable == false && it.defaultJob != null }
        assertThat(ui.defaultJob?.id).isEqualTo("j")
        viewModel.setDefaultBillable(null)
        viewModel.setDefaultJob(null)
        val cleared = ready { it.defaultBillable == null && it.defaultJob == null }
        assertThat(cleared.tagIds).containsExactly("t")
    }

    @Test
    fun ignoreIsToggledThroughAVisitOfThePlace() = runBlocking<Unit> {
        db.placeDao().upsert(place("p"))
        db.visitDao().upsert(visit("v", "p"))
        viewModel.load("p")

        viewModel.setIgnored(true)
        assertThat(ready { it.ignored }.ignored).isTrue()
        viewModel.setIgnored(false)
        assertThat(ready { !it.ignored }.ignored).isFalse()
    }

    @Test
    fun ignoreOnAPlaceWithNoVisitsShowsANotice() = runBlocking<Unit> {
        db.placeDao().upsert(place("p"))
        viewModel.load("p")
        val event = nextEvent()

        viewModel.setIgnored(true)

        assertThat(withTimeout(10_000) { event.await() }.message).isEqualTo(R.string.edit_error_not_found)
    }

    @Test
    fun savingARadiusWritesOnceOffersUndoAndEnqueuesSegmentation() = runBlocking<Unit> {
        db.placeDao().upsert(place("p", radius = 75f))
        db.visitDao().upsert(visit("v", "p"))
        viewModel.load("p")
        val event = nextEvent()

        viewModel.setRadius(120f)

        val offered = withTimeout(10_000) { event.await() }
        assertThat(offered.message).isEqualTo(R.string.places_undo_radius)
        assertThat(offered.snapshot).isNotNull()
        assertThat(ready { it.radiusM == 120f }.radiusM).isEqualTo(120f)
        assertThat(enqueued()).isEqualTo(1)

        undoEvents.undo(offered)
        assertThat(ready { it.radiusM == 75f }.radiusM).isEqualTo(75f)
    }

    @Test
    fun anUnchangedRadiusOffersNoUndoAndDoesNotEnqueue() = runBlocking<Unit> {
        db.placeDao().upsert(place("p", radius = 75f))
        viewModel.load("p")

        viewModel.setRadius(75f).join()

        assertThat(enqueued()).isEqualTo(0)
    }

    @Test
    fun radiusInputCountsUnfrozenVisitsByTheMeanOfTheirPings() = runBlocking<Unit> {
        db.placeDao().upsert(place("p"))
        db.visitDao().upsert(visit("v", "p", start = 1_000, end = 5_000))
        db.visitDao().upsert(visit("f", "p", frozen = true, start = 10_000, end = 20_000))
        val session = db.trackingSessionDao().upsert(TrackingSession(start = 0, zoneId = "UTC", trigger = SessionTrigger.SCHEDULE))
        db.locationPingDao().upsert(
            LocationPing(timestamp = 2_000, zoneId = "UTC", lat = 1.0, lng = 1.0, accuracyM = 5f, source = PingSource.FUSED, sessionId = session),
        )
        viewModel.load("p")

        val input = viewModel.radiusInput()!!

        assertThat(input.visits.map { it.placeId to it.frozen }).containsExactly("p" to false, "p" to true)
        assertThat(input.visits.first { !it.frozen }.lat).isEqualTo(1.0)
        assertThat(input.visits.first { it.frozen }.lat).isNull()
    }

    @Test
    fun previewMergeReportsTheCombinedRadiusAndTheCap() = runBlocking<Unit> {
        db.placeDao().upsert(place("a", lat = 1.0))
        db.placeDao().upsert(place("near", lat = 1.0005))
        db.placeDao().upsert(place("far", lat = 1.01))
        viewModel.load("a")

        val near = viewModel.previewMerge("near")!!
        val far = viewModel.previewMerge("far")!!

        assertThat(near.capped).isFalse()
        assertThat(near.radiusM).isAtLeast(75f)
        assertThat(far.capped).isTrue()
        assertThat(far.radiusM).isEqualTo(300f)
    }

    @Test
    fun mergeMovesVisitsOffersUndoEnqueuesAndTheSourceScreenCloses() = runBlocking<Unit> {
        db.placeDao().upsert(place("a"))
        db.placeDao().upsert(place("b", lat = 1.0003))
        db.visitDao().upsert(visit("v", "a"))
        viewModel.load("a")
        ready()
        val event = nextEvent()

        viewModel.merge("b")

        val offered = withTimeout(10_000) { event.await() }
        assertThat(offered.message).isEqualTo(R.string.places_undo_merged)
        assertThat(awaitLoad { it is PlaceDetailLoad.Missing }).isEqualTo(PlaceDetailLoad.Missing)
        assertThat(db.visitDao().getById("v")!!.placeId).isEqualTo("b")
        assertThat(enqueued()).isEqualTo(1)

        undoEvents.undo(offered)
        ready()
        assertThat(db.visitDao().getById("v")!!.placeId).isEqualTo("a")
    }

    @Test
    fun deleteOffersUndoEnqueuesAndClosesTheScreen() = runBlocking<Unit> {
        db.placeDao().upsert(place("p"))
        db.visitDao().upsert(visit("v", "p"))
        viewModel.load("p")
        ready()
        val event = nextEvent()

        viewModel.delete()

        val offered = withTimeout(10_000) { event.await() }
        assertThat(offered.message).isEqualTo(R.string.places_undo_deleted)
        awaitLoad { it is PlaceDetailLoad.Missing }
        assertThat(enqueued()).isEqualTo(1)

        undoEvents.undo(offered)
        assertThat(ready().id).isEqualTo("p")
    }

    @Test
    fun aPlacePrunedElsewhereMakesTheScreenCloseAndAnUnknownIdIsMissing() = runBlocking<Unit> {
        db.placeDao().upsert(place("p"))
        viewModel.load("p")
        ready()

        db.placeDao().upsert(place("p").copy(deleted = true))
        awaitLoad { it is PlaceDetailLoad.Missing }

        viewModel.load("nope")
        assertThat(awaitLoad { it is PlaceDetailLoad.Missing }).isEqualTo(PlaceDetailLoad.Missing)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
