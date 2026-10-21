package me.tarry.app.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.app.ui.timeline.TimelineItem
import me.tarry.app.ui.timeline.TimelineViewModel
import me.tarry.app.ui.timeline.dayBounds
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.repository.JobRepository
import me.tarry.core.data.repository.PlaceRepository
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
class TimelineViewModelTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 10, 21)
    private lateinit var db: TarryDatabase
    private lateinit var timeline: TimelineRepository
    private lateinit var places: PlaceRepository
    private lateinit var jobs: JobRepository

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

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun viewModel(today: () -> LocalDate = { day }) = TimelineViewModel(timeline, places, jobs, zone, today)

    private fun TimelineViewModel.awaitItems(predicate: (List<TimelineItem>) -> Boolean): List<TimelineItem> =
        runBlocking { withTimeout(10_000) { items.first { it != null && predicate(it) } } }.orEmpty()

    private fun visit(id: String, start: Long, end: Long, placeId: String?, jobId: String? = null) = Visit(
        id = id, placeId = placeId, jobId = jobId, start = start, end = end, zoneId = zone.id,
        origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0,
    )

    private fun place(id: String, name: String? = null, address: String? = null) = Place(
        id = id, centroidLat = -33.865143, centroidLng = 151.2099, name = name, address = address, updatedAt = 0,
    )

    @Test
    fun combinesAndSortsByStart() = runBlocking {
        places.save(place("p", name = "Office"))
        timeline.saveGap(Gap(id = "g", start = at(day, 13), end = at(day, 14), zoneId = zone.id, cause = GapCause.NO_FIX, updatedAt = 0))
        timeline.saveTrip(Trip(id = "t", start = at(day, 11), end = at(day, 12), zoneId = zone.id, distanceM = 900f, mode = TripMode.VEHICLE, updatedAt = 0))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 11), "p"))

        val result = viewModel().awaitItems { it.size == 3 }

        assertThat(result.map { it.id }).containsExactly("v", "t", "g").inOrder()
        assertThat(result[0]).isEqualTo(TimelineItem.Visit("v", at(day, 9), at(day, 11), "Office"))
        assertThat(result[1]).isEqualTo(TimelineItem.Trip("t", at(day, 11), at(day, 12), 900f, TripMode.VEHICLE))
        assertThat(result[2]).isEqualTo(TimelineItem.Gap("g", at(day, 13), at(day, 14), GapCause.NO_FIX))
    }

    @Test
    fun placeLabelFallsBackFromNameToAddressToCoordinates() = runBlocking {
        places.save(place("named", name = "Office", address = "1 Main St"))
        places.save(place("addr", address = "2 Main St"))
        places.save(place("blank", name = " ", address = ""))
        places.save(place("none"))
        timeline.saveVisit(visit("v1", at(day, 8), at(day, 9), "named"))
        timeline.saveVisit(visit("v2", at(day, 9), at(day, 10), "addr"))
        timeline.saveVisit(visit("v3", at(day, 10), at(day, 11), "blank"))
        timeline.saveVisit(visit("v4", at(day, 11), at(day, 12), "none"))
        timeline.saveVisit(visit("v5", at(day, 12), at(day, 13), null))

        val labels = viewModel().awaitItems { it.size == 5 }.map { (it as TimelineItem.Visit).placeLabel }

        assertThat(labels).containsExactly(
            "Office", "2 Main St", "-33.86514, 151.20990", "-33.86514, 151.20990", null,
        ).inOrder()
    }

    @Test
    fun visitWithJobGetsJobNameAndColour() = runBlocking {
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 0xFF112233.toInt(), updatedAt = 0))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 10), null, jobId = "j"))

        val v = viewModel().awaitItems { it.size == 1 }.single() as TimelineItem.Visit

        assertThat(v.jobName).isEqualTo("Acme")
        assertThat(v.jobColorArgb).isEqualTo(0xFF112233.toInt())
    }

    @Test
    fun visitWithoutJobHasNullJobFields() = runBlocking {
        timeline.saveVisit(visit("v", at(day, 9), at(day, 10), null))

        val v = viewModel().awaitItems { it.size == 1 }.single() as TimelineItem.Visit

        assertThat(v.jobName).isNull()
        assertThat(v.jobColorArgb).isNull()
    }

    @Test
    fun unknownOrDeletedJobGivesNullJobFields() = runBlocking {
        jobs.save(Job(id = "gone", name = "Old", colorArgb = 1, updatedAt = 0, deleted = true))
        timeline.saveVisit(visit("v1", at(day, 9), at(day, 10), null, jobId = "gone"))
        timeline.saveVisit(visit("v2", at(day, 10), at(day, 11), null, jobId = "missing"))

        val result = viewModel().awaitItems { it.size == 2 }.map { it as TimelineItem.Visit }

        assertThat(result.map { it.jobName }).containsExactly(null, null).inOrder()
        assertThat(result.map { it.jobColorArgb }).containsExactly(null, null).inOrder()
    }

    @Test
    fun changingDayChangesQueriedBounds() = runBlocking {
        val yesterday = day.minusDays(1)
        timeline.saveVisit(visit("today", at(day, 9), at(day, 10), null))
        timeline.saveVisit(visit("yesterday", at(yesterday, 9), at(yesterday, 10), null))
        val vm = viewModel()

        assertThat(vm.awaitItems { it.isNotEmpty() }.map { it.id }).containsExactly("today")
        vm.previousDay()
        assertThat(vm.selectedDate.value).isEqualTo(yesterday)
        assertThat(vm.awaitItems { it.singleOrNull()?.id == "yesterday" }).hasSize(1)
        vm.nextDay()
        assertThat(vm.selectedDate.value).isEqualTo(day)
        assertThat(vm.awaitItems { it.singleOrNull()?.id == "today" }).hasSize(1)
    }

    @Test
    fun dstDayIs23Hours() {
        val (from, to) = dayBounds(LocalDate.of(2026, 10, 4), zone)
        assertThat(to - from).isEqualTo(23 * 60 * 60 * 1000L)
        val (nFrom, nTo) = dayBounds(day, zone)
        assertThat(nTo - nFrom).isEqualTo(24 * 60 * 60 * 1000L)
        assertThat(nFrom).isEqualTo(at(day, 0))
    }

    @Test
    fun itemsAreNullWhileLoadingThenEmptyListForAnEmptyDay() = runBlocking {
        val vm = viewModel()
        assertThat(vm.items.value).isNull()
        val loaded = withTimeout(10_000) { vm.items.first { it != null } }
        assertThat(loaded).isEmpty()
    }

    @Test
    fun sameStartSortsGapThenVisitThenTrip() = runBlocking {
        val s = at(day, 10)
        timeline.saveTrip(Trip(id = "t", start = s, end = at(day, 11), zoneId = zone.id, distanceM = 1f, mode = TripMode.WALK, updatedAt = 0))
        timeline.saveVisit(visit("v", s, at(day, 11), null))
        timeline.saveGap(Gap(id = "g", start = s, end = at(day, 11), zoneId = zone.id, cause = GapCause.NO_FIX, updatedAt = 0))
        timeline.saveVisit(visit("early", at(day, 9), at(day, 9, 30), null))

        val result = viewModel().awaitItems { it.size == 4 }

        assertThat(result.map { it.id }).containsExactly("early", "g", "v", "t").inOrder()
    }

    @Test
    fun rolloverMovesSelectionWhenFollowingToday() {
        var now = day
        val vm = viewModel { now }
        now = day.plusDays(1)

        vm.refreshToday()

        assertThat(vm.today.value).isEqualTo(day.plusDays(1))
        assertThat(vm.selectedDate.value).isEqualTo(day.plusDays(1))
    }

    @Test
    fun rolloverKeepsADayTheUserNavigatedTo() {
        var now = day
        val vm = viewModel { now }
        vm.previousDay()
        now = day.plusDays(1)

        vm.refreshToday()

        assertThat(vm.today.value).isEqualTo(day.plusDays(1))
        assertThat(vm.selectedDate.value).isEqualTo(day.minusDays(1))
    }

    @Test
    fun nextDayStopsAtToday() {
        val vm = viewModel()
        vm.nextDay()
        assertThat(vm.selectedDate.value).isEqualTo(day)
        vm.previousDay()
        vm.nextDay()
        assertThat(vm.selectedDate.value).isEqualTo(day)
    }
}
