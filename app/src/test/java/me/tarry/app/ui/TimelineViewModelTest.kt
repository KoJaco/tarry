package me.tarry.app.ui

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.app.map.GeoPoint
import me.tarry.app.ui.timeline.TimelineItem
import me.tarry.core.data.db.dao.PingPosition
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
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
import me.tarry.core.data.repository.AnnotationOp
import me.tarry.core.data.repository.EditResult
import me.tarry.core.data.repository.ItemRef
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.Snapshot
import me.tarry.core.data.repository.TagRepository
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.app.R
import me.tarry.app.ui.undo.UndoEvent
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
    private lateinit var tags: TagRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeline = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        places = PlaceRepository(db.placeDao())
        jobs = JobRepository(db.jobDao())
        tags = TagRepository(db.tagDao())
    }

    @After
    fun tearDown() {
        // Cancel every view model's scope first: its init collector would otherwise keep observing the Room flows after
        // db.close() and leak an uncaught exception into whichever test runs next. Cancellation completes on Room's threads
        // and resumes through Dispatchers.Main, so wait for every scope to finish before resetMain (else "used concurrently").
        val scopes = registered.map { it.viewModelScope.coroutineContext.job }
        store.clear()
        runBlocking { withTimeout(10_000) { scopes.joinAll() } }
        db.close()
        Dispatchers.resetMain()
    }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val store = ViewModelStore()
    private var nextKey = 0
    private val registered = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TimelineViewModel, Boolean>())

    /** Registers the view model with [store] (once) so tearDown can clear it (cancelling viewModelScope) before the DB closes. */
    private fun TimelineViewModel.managed(): TimelineViewModel {
        val vm = this
        if (!registered.add(vm)) return vm
        return ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })["vm${nextKey++}", TimelineViewModel::class.java]
    }

    private fun viewModel(today: () -> LocalDate = { day }) =
        TimelineViewModel(timeline, places, jobs, tags, zone, today).managed()

    private fun TimelineViewModel.awaitItems(predicate: (List<TimelineItem>) -> Boolean): List<TimelineItem> =
        runBlocking { withTimeout(10_000) { items.first { it != null && predicate(it) } } }.orEmpty()

    /** Saves one live visit per id on [day] and returns a view model that has already loaded them (selection is pruned to live visits). */
    private fun loadedViewModel(vararg ids: String, build: () -> TimelineViewModel = { viewModel() }): TimelineViewModel {
        runBlocking { ids.forEachIndexed { i, id -> timeline.saveVisit(visit(id, at(day, 8 + 2 * i), at(day, 9 + 2 * i), null)) } }
        return build().managed().also { it.awaitItems { rows -> rows.size == ids.size } }
    }

    private fun visit(id: String, start: Long, end: Long, placeId: String?, jobId: String? = null) = Visit(
        id = id, placeId = placeId, jobId = jobId, start = start, end = end, zoneId = zone.id,
        origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0,
    )

    private fun place(id: String, name: String? = null, address: String? = null) = Place(
        id = id, centroidLat = -33.865143, centroidLng = 151.2099, name = name, address = address, updatedAt = 0,
    )

    @Test
    fun dayMapPinsVisitsAtTheirPlaceAndDrawsTheDaysPings() = runBlocking {
        places.save(place("p", name = "Office"))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 11), "p"))
        timeline.saveVisit(visit("loose", at(day, 12), at(day, 13), null))
        val asked = mutableListOf<Pair<Long, Long>>()
        val pings = listOf(
            PingPosition(at(day, 11), -33.865, 151.209, 5f),
            PingPosition(at(day, 11, 5), -33.870, 151.205, 5f),
        )
        val vm = TimelineViewModel(
            timeline, places, jobs, tags, zone, { day },
            pingPositions = { from, to -> asked += from to to; flowOf(pings) },
        ).managed()

        val map = runBlocking { withTimeout(10_000) { vm.dayMap.first { it != null && it.visitCount == 2 } } }!!

        assertThat(asked).containsExactly(dayBounds(day, zone))
        assertThat(map.pins.map { it.visitId to it.number }).containsExactly("v" to 1) // "loose" has no place
        assertThat(map.pins.single().position).isEqualTo(GeoPoint(-33.865143, 151.2099))
        assertThat(map.route).hasSize(1)
        assertThat(map.routeMissing).isFalse()
    }

    @Test
    fun dayMapNeverMixesTwoDaysWhenTheDateChanges() = runBlocking {
        // Each day's place and pings share one latitude, so a mixed map has pins and route at different latitudes.
        val yesterday = day.minusDays(1)
        places.save(place("today").copy(centroidLat = -33.0))
        places.save(place("yday").copy(centroidLat = -34.0))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 11), "today"))
        timeline.saveVisit(visit("y", at(yesterday, 9), at(yesterday, 11), "yday"))
        val vm = TimelineViewModel(
            timeline, places, jobs, tags, zone, { day },
            pingPositions = { from, _ ->
                val lat = if (from == dayBounds(day, zone).first) -33.0 else -34.0
                flowOf(listOf(PingPosition(from + 3_600_000, lat, 151.2, 5f), PingPosition(from + 3_660_000, lat, 151.21, 5f)))
            },
        ).managed()
        val seen = java.util.concurrent.CopyOnWriteArrayList<me.tarry.app.map.DayMap>()
        val job = launch(Dispatchers.Default) { vm.dayMap.collect { m -> if (m != null) seen += m } }
        withTimeout(10_000) { while (seen.none { m -> m.pins.any { it.visitId == "v" } }) delay(10) }
        vm.previousDay()
        withTimeout(10_000) { while (seen.none { m -> m.pins.any { it.visitId == "y" } }) delay(10) }
        job.cancelAndJoin()

        seen.forEach { m ->
            val pinLats = m.pins.map { it.position.lat }.toSet()
            val routeLats = m.route.flatten().map { it.lat }.toSet()
            assertThat(pinLats + routeLats).hasSize(1)
        }
    }

    @Test
    fun dayMapFlagsAMissingRouteWhenTheDayHasNoPings() = runBlocking {
        places.save(place("p"))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 11), "p"))

        val map = runBlocking { withTimeout(10_000) { viewModel().dayMap.first { it != null && it.visitCount == 1 } } }!!

        assertThat(map.route).isEmpty()
        assertThat(map.routeMissing).isTrue()
    }

    @Test
    fun combinesAndSortsByStart() = runBlocking {
        places.save(place("p", name = "Office"))
        timeline.saveGap(Gap(id = "g", start = at(day, 13), end = at(day, 14), zoneId = zone.id, cause = GapCause.NO_FIX, updatedAt = 0))
        timeline.saveTrip(Trip(id = "t", start = at(day, 11), end = at(day, 12), zoneId = zone.id, distanceM = 900f, mode = TripMode.VEHICLE, updatedAt = 0))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 11), "p"))

        val result = viewModel().awaitItems { it.size == 3 }

        assertThat(result.map { it.id }).containsExactly("v", "t", "g").inOrder()
        assertThat(result[0]).isEqualTo(TimelineItem.Visit("v", at(day, 9), at(day, 11), "Office", placeId = "p"))
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
        assertThat(v.jobId).isEqualTo("j")
    }

    @Test
    fun visitWithoutJobHasNullJobFields() = runBlocking {
        timeline.saveVisit(visit("v", at(day, 9), at(day, 10), null))

        val v = viewModel().awaitItems { it.size == 1 }.single() as TimelineItem.Visit

        assertThat(v.jobName).isNull()
        assertThat(v.jobColorArgb).isNull()
        assertThat(v.jobId).isNull()
    }

    @Test
    fun unknownOrDeletedJobGivesNullJobFields() = runBlocking {
        jobs.save(Job(id = "gone", name = "Old", colorArgb = 1, updatedAt = 0, deleted = true))
        timeline.saveVisit(visit("v1", at(day, 9), at(day, 10), null, jobId = "gone"))
        timeline.saveVisit(visit("v2", at(day, 10), at(day, 11), null, jobId = "missing"))

        val result = viewModel().awaitItems { it.size == 2 }.map { it as TimelineItem.Visit }

        assertThat(result.map { it.jobName }).containsExactly(null, null).inOrder()
        assertThat(result.map { it.jobColorArgb }).containsExactly(null, null).inOrder()
        assertThat(result.map { it.jobId }).containsExactly(null, null).inOrder()
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
    fun selectDateJumpsAndClampsToToday() {
        val vm = viewModel()
        vm.selectDate(day.minusDays(10))
        assertThat(vm.selectedDate.value).isEqualTo(day.minusDays(10))
        vm.selectDate(day.plusDays(3))
        assertThat(vm.selectedDate.value).isEqualTo(day)
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

    @Test
    fun visitWithoutOwnJobInheritsThePlaceDefaultJob() = runBlocking {
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 0xFF112233.toInt(), updatedAt = 0))
        places.save(place("p", name = "Yard").copy(defaultJobId = "j"))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 10), "p"))

        val v = viewModel().awaitItems { it.size == 1 }.single() as TimelineItem.Visit

        assertThat(v.jobName).isEqualTo("Acme")
        assertThat(v.jobInherited).isTrue()
        assertThat(v.needsReview).isFalse()
    }

    @Test
    fun frozenVisitAtADeletedPlaceKeepsItsLabelAndInheritedJob() = runBlocking {
        jobs.save(Job(id = "j", name = "Acme", colorArgb = 0xFF112233.toInt(), updatedAt = 0))
        places.save(place("p", name = "Yard").copy(defaultJobId = "j", deleted = true))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 10), "p").copy(frozen = true))

        val v = viewModel().awaitItems { it.size == 1 }.single() as TimelineItem.Visit

        assertThat(v.placeLabel).isEqualTo("Yard")
        assertThat(v.jobName).isEqualTo("Acme")
        assertThat(v.jobInherited).isTrue()
    }

    @Test
    fun visitExposesFrozenBillableOriginPlaceAndIgnoredFlags() = runBlocking {
        places.save(place("p", name = "Home").copy(ignored = true))
        timeline.saveVisit(visit("v", at(day, 9), at(day, 10), "p").copy(frozen = true, billable = true, origin = VisitOrigin.MANUAL))

        val v = viewModel().awaitItems { it.size == 1 }.single() as TimelineItem.Visit

        assertThat(v.placeId).isEqualTo("p")
        assertThat(v.frozen).isTrue()
        assertThat(v.billable).isTrue()
        assertThat(v.origin).isEqualTo(VisitOrigin.MANUAL)
        assertThat(v.placeIgnored).isTrue()
        assertThat(v.jobInherited).isFalse()
        assertThat(v.needsReview).isFalse() // ignored place
    }

    private val emptySnapshot = Snapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private class BulkCall(val refs: List<ItemRef>, val op: AnnotationOp)

    @Test
    fun bulkActionEmitsExactlyOneUndoEventForAllSelectedAndLeavesSelection() = runBlocking {
        val calls = mutableListOf<BulkCall>()
        val undos = mutableListOf<UndoEvent>()
        var reSegmented = 0
        val vm = loadedViewModel("a", "b", "c") {
            TimelineViewModel(
                timeline, places, jobs, tags, zone, { day },
                offerUndo = { undos += it }, onEdited = { reSegmented++ },
                bulk = { refs, op -> calls += BulkCall(refs, op); EditResult.Ok(undo = emptySnapshot) },
            )
        }
        vm.startSelection("a")
        vm.toggleSelected("b")
        vm.toggleSelected("c")
        assertThat(vm.selection.value).containsExactly("a", "b", "c").inOrder()

        vm.bulkSetBillable(true)

        assertThat(calls).hasSize(1)
        assertThat(calls.single().refs).containsExactly(ItemRef.visit("a"), ItemRef.visit("b"), ItemRef.visit("c")).inOrder()
        assertThat(calls.single().op).isEqualTo(AnnotationOp.SetBillable(true))
        assertThat(undos).hasSize(1)
        assertThat(undos.single().message).isEqualTo(R.plurals.selection_undo_updated)
        assertThat(undos.single().count).isEqualTo(3)
        assertThat(reSegmented).isEqualTo(0) // annotations do not re-segment
        assertThat(vm.selection.value).isEmpty()
    }

    @Test
    fun aDoubleTapOnABarActionRunsTheBulkOnceAndOffersOneUndo() = runBlocking {
        val calls = mutableListOf<BulkCall>()
        val undos = mutableListOf<UndoEvent>()
        val gate = CompletableDeferred<Unit>()
        val vm = loadedViewModel("a", "b") {
            TimelineViewModel(
                timeline, places, jobs, tags, zone, { day },
                offerUndo = { undos += it },
                // Suspends like a real Room write, so the second tap lands before the first bulk has finished.
                bulk = { refs, op -> calls += BulkCall(refs, op); gate.await(); EditResult.Ok(undo = emptySnapshot) },
            )
        }
        vm.startSelection("a")
        vm.toggleSelected("b")

        vm.bulkSetBillable(true)
        vm.bulkSetBillable(true)
        gate.complete(Unit)
        withTimeout(10_000) { while (undos.isEmpty()) delay(10) }

        assertThat(calls).hasSize(1)
        assertThat(undos).hasSize(1)
        assertThat(undos.single().count).isEqualTo(2)
        assertThat(vm.selection.value).isEmpty()
    }

    @Test
    fun aDoubleTapOnNewJobCreatesOneJobAndRunsOneBulk() = runBlocking {
        val calls = mutableListOf<BulkCall>()
        var created = 0
        val gate = CompletableDeferred<Unit>()
        val vm = loadedViewModel("a") {
            TimelineViewModel(
                timeline, places, jobs, tags, zone, { day },
                bulk = { refs, op -> calls += BulkCall(refs, op); EditResult.Ok(undo = emptySnapshot) },
                createJob = { created++; gate.await(); EditResult.Ok(id = "new-job") },
            )
        }
        vm.startSelection("a")

        vm.bulkSetNewJob("Acme")
        vm.bulkSetNewJob("Acme")
        gate.complete(Unit)
        withTimeout(10_000) { while (calls.isEmpty()) delay(10) }

        assertThat(created).isEqualTo(1)
        assertThat(calls).hasSize(1)
    }

    @Test
    fun selectionExitsWhenEmptiedCancelledOrTheDayChanges() = runBlocking {
        val vm = loadedViewModel("a")
        vm.startSelection("a")
        vm.toggleSelected("a")
        assertThat(vm.selection.value).isEmpty()

        vm.toggleSelected("b") // not selecting: ignored
        assertThat(vm.selection.value).isEmpty()

        vm.startSelection("a")
        vm.clearSelection()
        assertThat(vm.selection.value).isEmpty()

        vm.startSelection("a")
        vm.previousDay()
        assertThat(vm.selection.value).isEmpty()

        vm.nextDay()
        vm.awaitItems { it.size == 1 }
        vm.startSelection("a")
        vm.selectDate(vm.selectedDate.value)
        assertThat(vm.selection.value).containsExactly("a") // same day: still selecting
        vm.previousDay()
        vm.startSelection("a")
        vm.nextDay()
        assertThat(vm.selection.value).isEmpty()
    }

    @Test
    fun selectionDropsVisitsThatNoLongerExistAndExitsWhenNoneRemain() = runBlocking<Unit> {
        val vm = loadedViewModel("a", "b")
        vm.startSelection("a")
        vm.toggleSelected("b")

        timeline.saveVisit(visit("a", at(day, 8), at(day, 9), null).copy(deleted = true))
        withTimeout(10_000) { vm.selection.first { it == setOf("b") } }

        timeline.saveVisit(visit("b", at(day, 10), at(day, 11), null).copy(deleted = true))
        withTimeout(10_000) { vm.selection.first { it.isEmpty() } }
    }

    @Test
    fun aRefusedBulkShowsANoticeWithNoUndoAndStillExitsSelection() = runBlocking {
        val undos = mutableListOf<UndoEvent>()
        val vm = loadedViewModel("a") {
            TimelineViewModel(timeline, places, jobs, tags, zone, { day }, offerUndo = { undos += it })
        }
        vm.startSelection("a")
        vm.bulkIgnorePlace() // default bulk returns NotFound
        assertThat(undos).hasSize(1)
        assertThat(undos.single().message).isEqualTo(R.string.edit_error_not_found)
        assertThat(undos.single().snapshot).isNull()
        assertThat(vm.selection.value).isEmpty()
    }

    @Test
    fun aFailedNewJobShowsANoticeAndRunsNoBulk() = runBlocking {
        val calls = mutableListOf<BulkCall>()
        val undos = mutableListOf<UndoEvent>()
        val vm = loadedViewModel("a") {
            TimelineViewModel(
                timeline, places, jobs, tags, zone, { day }, offerUndo = { undos += it },
                bulk = { refs, op -> calls += BulkCall(refs, op); EditResult.Ok(undo = emptySnapshot) },
                createJob = { EditResult.BlankName },
            )
        }
        vm.startSelection("a")
        vm.bulkSetNewJob(" ")
        assertThat(calls).isEmpty()
        assertThat(undos.single().message).isEqualTo(R.string.edit_error_blank_name)
    }

    @Test
    fun newJobFromTheBulkPickerCreatesItThenSetsItOnEverySelectedVisit() = runBlocking {
        val calls = mutableListOf<BulkCall>()
        val vm = loadedViewModel("a", "b") {
            TimelineViewModel(
                timeline, places, jobs, tags, zone, { day },
                bulk = { refs, op -> calls += BulkCall(refs, op); EditResult.Ok(undo = emptySnapshot) },
                createJob = { EditResult.Ok(id = "new-job") },
            )
        }
        vm.startSelection("a")
        vm.toggleSelected("b")
        vm.bulkSetNewJob("Acme")
        assertThat(calls.single().op).isEqualTo(AnnotationOp.SetJob("new-job"))
        assertThat(calls.single().refs).hasSize(2)
        assertThat(vm.selection.value).isEmpty()
    }

    @Test
    fun pickersHideArchivedJobsAndTagsAndEmptyGroups() = runBlocking {
        jobs.save(Job(id = "live", name = "Live", colorArgb = 0, updatedAt = 0))
        jobs.save(Job(id = "old", name = "Old", colorArgb = 0, archived = true, updatedAt = 0))
        tags.saveGroup(TagGroup(id = "g1", name = "Kind", colorArgb = 0, sortOrder = 0, updatedAt = 0))
        tags.saveGroup(TagGroup(id = "g2", name = "Gone", colorArgb = 0, sortOrder = 1, updatedAt = 0))
        tags.save(Tag(id = "t1", groupId = "g1", name = "Rush", sortOrder = 0, updatedAt = 0))
        tags.save(Tag(id = "t2", groupId = "g1", name = "Old tag", sortOrder = 1, archived = true, updatedAt = 0))
        tags.save(Tag(id = "t3", groupId = "g2", name = "Only archived", sortOrder = 0, archived = true, updatedAt = 0))
        val vm = viewModel()

        val pickedJobs = withTimeout(10_000) { vm.pickerJobs.first { it.isNotEmpty() } }
        val pickedGroups = withTimeout(10_000) { vm.pickerTagGroups.first { it.isNotEmpty() } }

        assertThat(pickedJobs.map { it.id }).containsExactly("live")
        assertThat(pickedGroups.map { it.id }).containsExactly("g1")
        assertThat(pickedGroups.single().tags.map { it.id }).containsExactly("t1").inOrder()
    }
}
