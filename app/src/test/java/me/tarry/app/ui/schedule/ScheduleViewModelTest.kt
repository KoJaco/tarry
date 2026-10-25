package me.tarry.app.ui.schedule

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.app.tracking.CaptureCoordinator
import me.tarry.app.tracking.EffectiveScheduleCalculator
import me.tarry.app.tracking.EffectiveScheduleWindowChecker
import me.tarry.app.tracking.FakeActivityClient
import me.tarry.app.tracking.FakeAlarmScheduler
import me.tarry.app.tracking.FakeAlertNotifier
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeGeofenceClient
import me.tarry.app.tracking.FakeLauncher
import me.tarry.app.tracking.FakeLocationClient
import me.tarry.app.tracking.FakePermissions
import me.tarry.app.tracking.FakeSegmentationTrigger
import me.tarry.app.tracking.FakeStayRadius
import me.tarry.app.tracking.HealthGapRecorder
import me.tarry.app.tracking.ScheduleInputsProvider
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.app.tracking.createTrackingStatusDataStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.repository.TimelineRepository
import me.tarry.core.data.repository.TrackingRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScheduleViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class CountingStateRepository : TrackingStateRepository {
        var refreshes = 0
        var failWith: RuntimeException? = null
        override val state: StateFlow<TrackingState> = MutableStateFlow(TrackingState.IDLE)
        override val details: StateFlow<TrackingDetails> = MutableStateFlow(TrackingDetails())
        override fun refresh() {
            failWith?.let { throw it }
            refreshes++
        }
    }

    private val config = TrackingConfig()
    private val sydney = ZoneId.of("Australia/Sydney")
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    // 2026-10-19 is a Monday.
    private val clock = FakeClock(now = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, sydney).toInstant().toEpochMilli(), zone = sydney)
    private val thisWeek = LocalDate.of(2026, 10, 19)
    private val nextWeek = LocalDate.of(2026, 10, 26)

    private lateinit var db: TarryDatabase
    private lateinit var scheduleRepo: ScheduleRepository
    private lateinit var stateRepo: CountingStateRepository
    private lateinit var controller: TrackingController
    private lateinit var viewModel: ScheduleViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        scheduleRepo = ScheduleRepository(db.scheduleDao())
        val store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        val inputs = ScheduleInputsProvider(scheduleRepo, trackingRepo, store, config, clock)
        val calculator = EffectiveScheduleCalculator(config)
        val timelineRepo = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        val health = HealthGapRecorder(
            store, timelineRepo, FakeAlertNotifier(), clock, config, EffectiveScheduleWindowChecker(inputs, calculator, clock),
        )
        val coordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, FakeLocationClient(), FakeActivityClient(), FakeGeofenceClient(), FakeStayRadius(),
            FakePermissions(), EffectiveScheduleWindowChecker(inputs, calculator, clock), health, clock, captureScope,
        )
        controller = TrackingController(
            coordinator, FakeLauncher(), store, trackingRepo, scheduleRepo, inputs, calculator, FakeAlarmScheduler(),
            FakeSegmentationTrigger(), health, clock, config,
        )
        stateRepo = CountingStateRepository()
        runBlocking { scheduleRepo.saveTemplate(Schedule.MON_FRI_7_TO_17, 0) }
        viewModel = ScheduleViewModel(scheduleRepo, controller, stateRepo, clock, captureScope)
    }

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** Room runs suspend queries off the test thread, so wait for the state we expect. */
    private fun awaitState(predicate: (ScheduleUiState) -> Boolean): ScheduleUiState = runBlocking {
        withTimeout(10_000) { viewModel.state.first(predicate) }
    }

    /** Waits until the stored schedule for [mode] / [week] has been applied, which proves a reload finished. */
    private fun awaitLoaded(mode: ScheduleMode, week: LocalDate): ScheduleUiState =
        awaitState { it.loadedFor == LoadedFor(mode, week) && !it.saving }

    @Test
    fun loadsTheTemplateAndStartsClean() {
        val s = awaitState { it.loaded }

        assertThat(s.mode).isEqualTo(ScheduleMode.TEMPLATE)
        assertThat(s.saved).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.draft).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.dirty).isFalse()
        assertThat(s.viewedWeek).isEqualTo(thisWeek)
        assertThat(s.today).isEqualTo(thisWeek)
        assertThat(s.hasOverride).isFalse()
    }

    @Test
    fun weekModeWithoutAnOverrideStartsFromTheTemplate() {
        awaitState { it.loaded }

        viewModel.setMode(ScheduleMode.WEEK)
        val s = awaitLoaded(ScheduleMode.WEEK, thisWeek)

        assertThat(s.draft).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.hasOverride).isFalse()
        assertThat(s.dirty).isFalse()
    }

    @Test
    fun editsMakeTheDraftDirtyAndDiscardRestoresIt() {
        awaitState { it.loaded }

        viewModel.toggleCell(DayOfWeek.SATURDAY, 20)
        assertThat(viewModel.state.value.dirty).isTrue()
        assertThat(viewModel.state.value.draft.isActive(DayOfWeek.SATURDAY, 20)).isTrue()

        viewModel.discard()

        assertThat(viewModel.state.value.dirty).isFalse()
        assertThat(viewModel.state.value.draft).isEqualTo(Schedule.MON_FRI_7_TO_17)
    }

    @Test
    fun dayEditsChangeTheDraft() {
        awaitState { it.loaded }

        viewModel.setDayHours(DayOfWeek.MONDAY, 16, 20)
        viewModel.copyDay(DayOfWeek.MONDAY, setOf(DayOfWeek.TUESDAY, DayOfWeek.MONDAY))
        viewModel.clearDay(DayOfWeek.FRIDAY)

        val draft = viewModel.state.value.draft
        assertThat(draft.daySlots(DayOfWeek.MONDAY)).isEqualTo(List(48) { it in 16..19 })
        assertThat(draft.daySlots(DayOfWeek.TUESDAY)).isEqualTo(draft.daySlots(DayOfWeek.MONDAY))
        assertThat(draft.daySlots(DayOfWeek.FRIDAY)).doesNotContain(true)
        assertThat(draft.daySlots(DayOfWeek.WEDNESDAY)).isEqualTo(Schedule.MON_FRI_7_TO_17.daySlots(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun clearAllEmptiesTheDraft() {
        awaitState { it.loaded }

        viewModel.clearAll()
        assertThat(viewModel.state.value.draft).isEqualTo(Schedule.EMPTY)
    }

    @Test
    fun savingInEveryWeekModeWritesTheTemplateAndRefreshesTheState() = runBlocking {
        awaitState { it.loaded }
        viewModel.clearAll()

        viewModel.save()
        val s = awaitState { it.saved == Schedule.EMPTY && !it.saving }

        assertThat(s.dirty).isFalse()
        assertThat(scheduleRepo.template()).isEqualTo(Schedule.EMPTY)
        assertThat(scheduleRepo.override(thisWeek.toString())).isNull()
        assertThat(stateRepo.refreshes).isEqualTo(1)
    }

    @Test
    fun savingInWeekModeWritesThatWeeksOverrideOnly() = runBlocking {
        awaitState { it.loaded }
        viewModel.setMode(ScheduleMode.WEEK)
        awaitLoaded(ScheduleMode.WEEK, thisWeek)
        viewModel.clearAll()

        viewModel.save()
        val s = awaitState { it.hasOverride && !it.saving }

        assertThat(scheduleRepo.override(thisWeek.toString())).isEqualTo(Schedule.EMPTY)
        assertThat(scheduleRepo.template()).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.currentWeekHasOverride).isTrue()
        assertThat(s.saved).isEqualTo(Schedule.EMPTY)
        assertThat(s.dirty).isFalse()
    }

    @Test
    fun savingAnotherWeekLeavesTheCurrentWeekAlone() = runBlocking {
        awaitState { it.loaded }
        viewModel.setMode(ScheduleMode.WEEK)
        viewModel.shiftWeek(1)
        awaitLoaded(ScheduleMode.WEEK, nextWeek)
        viewModel.setDayHours(DayOfWeek.SATURDAY, 20, 24)

        viewModel.save()
        val s = awaitState { it.hasOverride && !it.saving }

        assertThat(s.viewedWeek).isEqualTo(nextWeek)
        assertThat(s.currentWeekHasOverride).isFalse()
        assertThat(scheduleRepo.override(nextWeek.toString())!!.isActive(DayOfWeek.SATURDAY, 20)).isTrue()
        assertThat(scheduleRepo.override(thisWeek.toString())).isNull()
    }

    @Test
    fun switchingToEveryWeekAfterASaveShowsTheTemplate() = runBlocking {
        awaitState { it.loaded }
        viewModel.setMode(ScheduleMode.WEEK)
        awaitLoaded(ScheduleMode.WEEK, thisWeek)
        viewModel.clearAll()
        viewModel.save()
        // Navigation is ignored while the save is in flight, so the reload cannot be clobbered or clobber it.
        viewModel.setMode(ScheduleMode.TEMPLATE)
        awaitState { !it.saving && it.hasOverride }

        viewModel.setMode(ScheduleMode.TEMPLATE)
        val s = awaitLoaded(ScheduleMode.TEMPLATE, thisWeek)

        assertThat(s.mode).isEqualTo(ScheduleMode.TEMPLATE)
        assertThat(s.saved).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.draft).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.dirty).isFalse()
    }

    @Test
    fun aFailedSaveKeepsTheDraftAndReportsTheError() = runBlocking {
        awaitState { it.loaded }
        viewModel.clearAll()
        stateRepo.failWith = IllegalStateException("disk full")

        viewModel.save()
        val s = awaitState { it.saveFailed }

        assertThat(s.saving).isFalse()
        assertThat(s.dirty).isTrue()
        assertThat(s.draft).isEqualTo(Schedule.EMPTY)

        viewModel.toggleCell(DayOfWeek.MONDAY, 14)
        assertThat(viewModel.state.value.saveFailed).isFalse()
    }

    @Test
    fun useEveryWeekScheduleClearsTheOverride() = runBlocking {
        scheduleRepo.saveOverride(thisWeek.toString(), Schedule.EMPTY, 0)
        viewModel = ScheduleViewModel(scheduleRepo, controller, stateRepo, clock, captureScope)
        awaitState { it.loaded }
        viewModel.setMode(ScheduleMode.WEEK)
        awaitLoaded(ScheduleMode.WEEK, thisWeek)
        assertThat(viewModel.state.value.hasOverride).isTrue()
        assertThat(viewModel.state.value.draft).isEqualTo(Schedule.EMPTY)

        viewModel.useEveryWeekSchedule()
        val s = awaitState { !it.hasOverride && !it.saving }

        assertThat(scheduleRepo.override(thisWeek.toString())).isNull()
        assertThat(s.draft).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.currentWeekHasOverride).isFalse()
        assertThat(stateRepo.refreshes).isEqualTo(1)
    }

    @Test
    fun previousWeekIsDisabledAtTheCurrentWeekAndNavigationNeverGoesBack() {
        val start = awaitState { it.loaded }
        assertThat(start.canGoPrevious).isFalse()

        viewModel.shiftWeek(-1)
        assertThat(viewModel.state.value.viewedWeek).isEqualTo(thisWeek)

        viewModel.shiftWeek(1)
        val next = awaitLoaded(ScheduleMode.TEMPLATE, nextWeek)
        assertThat(next.canGoPrevious).isTrue()

        viewModel.shiftWeek(-1)
        assertThat(awaitLoaded(ScheduleMode.TEMPLATE, thisWeek).canGoPrevious).isFalse()
    }

    @Test
    fun changingModeWithUnsavedEditsAsksFirst() {
        awaitState { it.loaded }
        viewModel.toggleCell(DayOfWeek.SATURDAY, 20)

        viewModel.setMode(ScheduleMode.WEEK)
        assertThat(viewModel.state.value.mode).isEqualTo(ScheduleMode.TEMPLATE)
        assertThat(viewModel.state.value.pending).isEqualTo(PendingNavigation.ToMode(ScheduleMode.WEEK))

        viewModel.cancelPending()
        assertThat(viewModel.state.value.pending).isNull()
        assertThat(viewModel.state.value.dirty).isTrue()

        viewModel.setMode(ScheduleMode.WEEK)
        viewModel.confirmPending()
        val s = awaitLoaded(ScheduleMode.WEEK, thisWeek)

        assertThat(s.pending).isNull()
        assertThat(s.draft).isEqualTo(Schedule.MON_FRI_7_TO_17)
    }

    @Test
    fun changingWeekWithUnsavedEditsAsksFirst() {
        awaitState { it.loaded }
        viewModel.setMode(ScheduleMode.WEEK)
        awaitLoaded(ScheduleMode.WEEK, thisWeek)
        viewModel.clearAll()

        viewModel.shiftWeek(1)
        assertThat(viewModel.state.value.pending).isEqualTo(PendingNavigation.ShiftWeek(1))
        assertThat(viewModel.state.value.viewedWeek).isEqualTo(thisWeek)

        viewModel.confirmPending()
        val s = awaitLoaded(ScheduleMode.WEEK, nextWeek)
        assertThat(s.draft).isEqualTo(Schedule.MON_FRI_7_TO_17)
    }

    @Test
    fun observeOverrideFollowsSavesAndClears() = runBlocking {
        val key = thisWeek.toString()
        assertThat(withTimeout(10_000) { scheduleRepo.observeOverride(key).first() }).isNull()

        scheduleRepo.saveOverride(key, Schedule.EMPTY, 0)
        assertThat(withTimeout(10_000) { scheduleRepo.observeOverride(key).first { it != null } }).isEqualTo(Schedule.EMPTY)

        scheduleRepo.clearOverride(key)
        assertThat(withTimeout(10_000) { scheduleRepo.observeOverride(key).first { it == null } }).isNull()
    }
}
