package me.tarry.app.ui.onboarding

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.tarry.app.reminder.FakeReviewReminderScheduler
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
import me.tarry.app.tracking.FakeWindowEndGrace
import me.tarry.app.tracking.HealthGapRecorder
import me.tarry.app.tracking.ScheduleInputsProvider
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.app.tracking.createTrackingStatusDataStore
import me.tarry.app.ui.permissions.FakePermissionChecker
import me.tarry.app.ui.permissions.PermissionSnapshot
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.buildPermissionsUiState
import me.tarry.app.ui.schedule.ScheduleSaver
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.repository.TimelineRepository
import me.tarry.core.data.repository.TrackingRepository
import me.tarry.core.data.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnboardingViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeStateRepository : TrackingStateRepository {
        var failWith: RuntimeException? = null
        override val state: StateFlow<TrackingState> = MutableStateFlow(TrackingState.IDLE)
        override val details: StateFlow<TrackingDetails> = MutableStateFlow(TrackingDetails())
        override fun refresh() {
            failWith?.let { throw it }
        }
    }

    private val config = TrackingConfig()
    private val sydney = ZoneId.of("Australia/Sydney")
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    // 2026-10-19 is a Monday; 10:00 is inside the Mon-Fri 7-17 preset.
    private val monday10am = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, sydney).toInstant().toEpochMilli()
    private val saturday10am = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, sydney).toInstant().toEpochMilli()
    private val clock = FakeClock(now = monday10am, zone = sydney)
    private val permissions = FakePermissionChecker()
    private val none = buildPermissionsUiState(PermissionSnapshot(), emptySet())
    private val stateRepo = FakeStateRepository()
    private val launcher = FakeLauncher()

    private lateinit var db: TarryDatabase
    private lateinit var scheduleRepo: ScheduleRepository
    private lateinit var settings: SettingsRepository
    private lateinit var controller: TrackingController
    private lateinit var windows: EffectiveScheduleWindowChecker
    private lateinit var gate: OnboardingGate
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries().build()
        val trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        scheduleRepo = ScheduleRepository(db.scheduleDao())
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("settings.preferences_pb") })
        val store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        val inputs = ScheduleInputsProvider(scheduleRepo, trackingRepo, store, config, clock)
        val calculator = EffectiveScheduleCalculator(config)
        windows = EffectiveScheduleWindowChecker(inputs, calculator, clock)
        val timelineRepo = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        val health = HealthGapRecorder(store, timelineRepo, FakeAlertNotifier(), clock, config, windows)
        val coordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, FakeLocationClient(), FakeActivityClient(), FakeGeofenceClient(), FakeStayRadius(),
            FakePermissions(), windows, health, clock, captureScope,
        )
        controller = TrackingController(
            coordinator, launcher, store, trackingRepo, scheduleRepo, inputs, calculator, FakeAlarmScheduler(),
            FakeSegmentationTrigger(), health, clock, config, FakeWindowEndGrace(),
        )
        gate = OnboardingGate(settings, trackingRepo, permissions, clock)
        viewModel = newViewModel()
    }

    private fun newViewModel() = OnboardingViewModel(
        permissions, ScheduleSaver(controller, stateRepo, FakeReviewReminderScheduler()), gate, scheduleRepo, windows, clock, config, captureScope,
    )

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
    }

    private fun awaitState(predicate: (OnboardingUiState) -> Boolean): OnboardingUiState = runBlocking {
        withTimeout(10_000) { viewModel.state.first(predicate) }
    }

    private fun goToSchedulePage() {
        while (viewModel.state.value.page != OnboardingPage.Schedule) viewModel.next(none)
    }

    @Test
    fun grantedPermissionsAreLeftOutOfThePages() {
        permissions.snapshot = PermissionSnapshot(fineLocation = true, coarseLocation = true, activityRecognition = true)
        val steps = newViewModel().state.value.pages.filterIsInstance<OnboardingPage.Permission>().map { it.step }
        assertThat(steps).containsExactly(
            PermissionStep.BACKGROUND_LOCATION, PermissionStep.NOTIFICATIONS, PermissionStep.EXACT_ALARMS, PermissionStep.BATTERY_OPTIMISATION,
        ).inOrder()
    }

    @Test
    fun nextAndBackMoveOnePageAndBackStopsAtTheFirst() {
        viewModel.back(none)
        assertThat(viewModel.state.value.index).isEqualTo(0)
        viewModel.next(none)
        assertThat(viewModel.state.value.index).isEqualTo(1)
        viewModel.back(none)
        assertThat(viewModel.state.value.index).isEqualTo(0)
    }

    @Test
    fun savingTheUntouchedPresetStillWritesItAndInsideAWindowSaysTrackingNow() = runBlocking<Unit> {
        assertThat(scheduleRepo.template()).isNull()
        goToSchedulePage()

        viewModel.saveHours()
        val s = awaitState { it.page == OnboardingPage.Done }

        assertThat(scheduleRepo.template()).isEqualTo(Schedule.MON_FRI_7_TO_17)
        assertThat(s.outcome).isEqualTo(DoneOutcome.TrackingNow)
        assertThat(settings.onboardingCompletedAtMs()).isEqualTo(monday10am)
        assertThat(launcher.started).isNotEmpty()
    }

    @Test
    fun outsideAWindowTheDonePageNamesTheNextOne() = runBlocking<Unit> {
        clock.now = saturday10am
        goToSchedulePage()

        viewModel.saveHours()
        val s = awaitState { it.page == OnboardingPage.Done }

        val monday7am = ZonedDateTime.of(2026, 10, 26, 7, 0, 0, 0, sydney).toInstant().toEpochMilli()
        assertThat(s.outcome).isEqualTo(DoneOutcome.Next(monday7am, "Australia/Sydney"))
        assertThat(launcher.started).isEmpty()
    }

    @Test
    fun editedHoursAreTheOnesSaved() = runBlocking<Unit> {
        goToSchedulePage()
        viewModel.setSchedule(Schedule.EMPTY)
        viewModel.toggleCell(DayOfWeek.TUESDAY, 20)

        viewModel.saveHours()
        awaitState { it.page == OnboardingPage.Done }

        assertThat(scheduleRepo.template()).isEqualTo(Schedule.EMPTY.withSlots(DayOfWeek.TUESDAY, 20..20, true))
    }

    @Test
    fun aStoredTemplateSeedsTheGridInsteadOfThePreset() = runBlocking<Unit> {
        val stored = Schedule.EMPTY.withSlots(DayOfWeek.SATURDAY, 20..24, true)
        scheduleRepo.saveTemplate(stored, 1L)
        viewModel = newViewModel()

        assertThat(awaitState { it.schedule == stored }.schedule).isEqualTo(stored)
        goToSchedulePage()
        viewModel.saveHours()
        awaitState { it.page == OnboardingPage.Done }

        assertThat(scheduleRepo.template()).isEqualTo(stored)
    }

    @Test
    fun aFailedSaveStaysOnThePageKeepsTheEditsAndDoesNotStampTheFlag() = runBlocking<Unit> {
        goToSchedulePage()
        stateRepo.failWith = IllegalStateException("boom")

        viewModel.saveHours()
        val s = awaitState { it.saveFailed }

        assertThat(s.page).isEqualTo(OnboardingPage.Schedule)
        assertThat(s.saving).isFalse()
        assertThat(settings.onboardingCompletedAtMs()).isNull()
    }


    @Test
    fun backAfterAGrantSkipsTheGrantedPageToThePreviousOneThenWelcome() {
        // Welcome, LOCATION, BACKGROUND_LOCATION, ACTIVITY_RECOGNITION, ...: grant on page 3, go to 4, Back -> 2, then 1, then Welcome.
        repeat(3) { viewModel.next(none) }
        val granted = buildPermissionsUiState(PermissionSnapshot(activityRecognition = true), emptySet())
        viewModel.next(none)
        assertThat(viewModel.state.value.index).isEqualTo(4)
        viewModel.back(granted)
        assertThat(viewModel.state.value.index).isEqualTo(2)
        viewModel.back(granted)
        assertThat(viewModel.state.value.index).isEqualTo(1)
        viewModel.back(granted)
        assertThat(viewModel.state.value.index).isEqualTo(0)
    }

    @Test
    fun notNowSkipsTheRemainingPermissionPagesToTheSchedule() = runBlocking<Unit> {
        viewModel.next(none) // the location page
        assertThat(viewModel.state.value.page).isInstanceOf(OnboardingPage.Permission::class.java)
        viewModel.skipPermissions()
        assertThat(viewModel.state.value.page).isEqualTo(OnboardingPage.Schedule)
        assertThat(settings.onboardingCompletedAtMs()).isNull()
    }

    @Test
    fun nextSkipsAPermissionGrantedSinceTheFlowStarted() {
        // Welcome, LOCATION, BACKGROUND_LOCATION, ACTIVITY_RECOGNITION, ...: background granted outside the flow.
        val background = buildPermissionsUiState(PermissionSnapshot(backgroundLocation = true), emptySet())
        viewModel.next(background)
        assertThat(viewModel.state.value.index).isEqualTo(1)
        viewModel.next(background)
        assertThat(viewModel.state.value.page).isEqualTo(OnboardingPage.Permission(PermissionStep.ACTIVITY_RECOGNITION))
    }
}
