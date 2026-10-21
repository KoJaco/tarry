package me.tarry.app.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.app.R
import me.tarry.app.ui.shell.ShellStatusViewModel
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.Snapshot
import me.tarry.core.data.repository.TimelineRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ShellStatusViewModelTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 10, 21)
    private lateinit var db: TarryDatabase
    private lateinit var timeline: TimelineRepository
    private lateinit var places: PlaceRepository
    private lateinit var jobs: JobRepository
    private val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 1)
    private val undone = mutableListOf<UndoEvent>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeline = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        places = PlaceRepository(db.placeDao())
        jobs = JobRepository(db.jobDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun vm(today: LocalDate = day) =
        ShellStatusViewModel(timeline, places, jobs, zone, flowOf(today), events) { undone += it }

    private fun ShellStatusViewModel.awaitCount(n: Int) {
        runBlocking { withTimeout(10_000) { needsReviewToday.first { it == n } } }
    }

    private fun visit(id: String, hour: Int, placeId: String?, jobId: String? = null, date: LocalDate = day, deleted: Boolean = false) = Visit(
        id = id, placeId = placeId, jobId = jobId, start = at(date, hour), end = at(date, hour + 1), zoneId = zone.id,
        origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0, deleted = deleted,
    )

    private fun place(id: String, defaultJobId: String? = null, ignored: Boolean = false) = Place(
        id = id, centroidLat = 0.0, centroidLng = 0.0, name = id, defaultJobId = defaultJobId, ignored = ignored, updatedAt = 0,
    )

    @Test
    fun countsVisitsWithNoResolvedJob_butNotPlaceDefaultsIgnoredPlacesOrDeletedVisits() = runBlocking {
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        places.save(place("plain"))
        places.save(place("withDefault", defaultJobId = "j"))
        places.save(place("ignored", ignored = true))
        timeline.saveVisit(visit("needs", 8, "plain"))
        timeline.saveVisit(visit("needsNoPlace", 9, null))
        timeline.saveVisit(visit("own", 10, "plain", jobId = "j"))
        timeline.saveVisit(visit("inherits", 11, "withDefault"))
        timeline.saveVisit(visit("ignoredVisit", 12, "ignored"))
        timeline.saveVisit(visit("deleted", 13, "plain", deleted = true))
        timeline.saveVisit(visit("yesterday", 8, "plain", date = day.minusDays(1)))

        vm().awaitCount(2)
    }

    @Test
    fun aVisitWhoseJobWasDeletedFallsBackToThePlaceDefaultOrNeedsReview() = runBlocking {
        jobs.save(Job(id = "gone", name = "Old", colorArgb = 1, updatedAt = 0, deleted = true))
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        places.save(place("withDefault", defaultJobId = "j"))
        places.save(place("plain"))
        timeline.saveVisit(visit("a", 8, "withDefault", jobId = "gone"))
        timeline.saveVisit(visit("b", 9, "plain", jobId = "gone"))

        vm().awaitCount(1)
    }

    @Test
    fun gapsAndTripsNeverCount() = runBlocking {
        timeline.saveGap(Gap(id = "g", start = at(day, 8), end = at(day, 9), zoneId = zone.id, cause = GapCause.NO_FIX, updatedAt = 0))
        timeline.saveTrip(Trip(id = "t", start = at(day, 9), end = at(day, 10), zoneId = zone.id, distanceM = 10f, mode = TripMode.WALK, updatedAt = 0))

        vm().awaitCount(0)
    }

    @Test
    fun countFallsWhenAVisitGetsAJob() = runBlocking {
        places.save(place("plain"))
        timeline.saveVisit(visit("a", 8, "plain"))
        val vm = vm()
        vm.awaitCount(1)

        jobs.save(Job(id = "j", name = "Acme", colorArgb = 1, updatedAt = 0))
        timeline.saveVisit(visit("a", 8, "plain", jobId = "j"))

        vm.awaitCount(0)
    }

    @Test
    fun undoCallsThroughToTheUndoHandler() {
        val event = UndoEvent(R.string.nav_timeline, Snapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        vm().undo(event)
        assertThat(undone).containsExactly(event)
    }
}
