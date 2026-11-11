package me.tarry.app.ui.health

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.CurrentFixResult
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeLocationClient
import me.tarry.app.tracking.FakePreconditions
import me.tarry.app.tracking.Fix
import me.tarry.app.tracking.LocationPriority
import me.tarry.app.tracking.Preconditions
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.ui.permissions.FakePermissionChecker
import me.tarry.app.ui.permissions.PermissionSnapshot
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingHealthViewModelTest {
    private class FakeStateRepository : TrackingStateRepository {
        override val state = MutableStateFlow(TrackingState.IDLE)
        override val details = MutableStateFlow(TrackingDetails())
        var refreshes = 0

        override fun refresh() {
            refreshes++
        }
    }

    private val allGranted = PermissionSnapshot(
        fineLocation = true, coarseLocation = true, backgroundLocation = true, activityRecognition = true,
        notifications = true, exactAlarms = true, batteryExempt = true,
    )
    private val permissions = FakePermissionChecker(allGranted)
    private val preconditions = FakePreconditions()
    private val repository = FakeStateRepository()
    private val location = FakeLocationClient()
    private val config = TrackingConfig()
    private val clock = FakeClock(now = 1_000_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TrackingHealthViewModel(permissions, preconditions, repository, location, config, clock)

    /** Subscribes (the state only runs while observed) on an unconfined dispatcher so updates land at once. */
    private fun TrackingHealthViewModel.states(scope: TestScope): StateFlow<TrackingHealthUiState> {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { uiState.collect {} }
        return uiState
    }

    private fun TrackingHealthUiState.ok(id: HealthCheckId) = checks.first { it.id == id }.ok

    @Test
    fun everyCheckIsOkWhenEverythingIsGranted() = runTest {
        val s = viewModel().states(this).value
        assertThat(s.checks.map { it.id }).containsExactly(
            HealthCheckId.PRECISE_LOCATION, HealthCheckId.BACKGROUND_LOCATION, HealthCheckId.LOCATION_SERVICES,
            HealthCheckId.PHYSICAL_ACTIVITY, HealthCheckId.NOTIFICATIONS, HealthCheckId.EXACT_ALARMS, HealthCheckId.BATTERY,
        ).inOrder()
        assertThat(s.checks.all { it.ok }).isTrue()
    }

    @Test
    fun checksAreMappedFromThePermissionSnapshotAndThePreconditions() = runTest {
        permissions.snapshot = allGranted.copy(backgroundLocation = false, exactAlarms = false, batteryExempt = false)
        preconditions.value = Preconditions(locationEnabled = false)
        val s = viewModel().states(this).value
        assertThat(s.ok(HealthCheckId.PRECISE_LOCATION)).isTrue()
        assertThat(s.ok(HealthCheckId.BACKGROUND_LOCATION)).isFalse()
        assertThat(s.ok(HealthCheckId.LOCATION_SERVICES)).isFalse()
        assertThat(s.ok(HealthCheckId.PHYSICAL_ACTIVITY)).isTrue()
        assertThat(s.ok(HealthCheckId.EXACT_ALARMS)).isFalse()
        assertThat(s.ok(HealthCheckId.BATTERY)).isFalse()
    }

    @Test
    fun theNotificationsCheckIsLeftOutWhereThereIsNothingToAsk() = runTest {
        permissions.snapshot = allGranted.copy(notificationsApplicable = false)
        val s = viewModel().states(this).value
        assertThat(s.checks.map { it.id }).doesNotContain(HealthCheckId.NOTIFICATIONS)
    }

    @Test
    fun refreshReadsThePlatformAgainAndPokesTheTrackingState() = runTest {
        val vm = viewModel()
        val states = vm.states(this)
        assertThat(states.value.ok(HealthCheckId.BATTERY)).isTrue()

        permissions.snapshot = allGranted.copy(batteryExempt = false)
        vm.refresh()

        assertThat(states.value.ok(HealthCheckId.BATTERY)).isFalse()
        assertThat(repository.refreshes).isEqualTo(1)
    }

    @Test
    fun theStatusAndCauseFollowTheTrackingState() = runTest {
        val states = viewModel().states(this)
        repository.state.value = TrackingState.ATTENTION
        repository.details.value = TrackingDetails(attentionCause = AttentionCause.LOCATION_OFF)
        assertThat(states.value.trackingState).isEqualTo(TrackingState.ATTENTION)
        assertThat(states.value.attentionCause).isEqualTo(AttentionCause.LOCATION_OFF)
    }

    @Test
    fun blockingCausesFollowTheHealthMonitorsOrder() {
        assertThat(firstBlockingCause(Preconditions())).isNull()
        assertThat(firstBlockingCause(Preconditions(fineLocation = false, backgroundLocation = false)))
            .isEqualTo(AttentionCause.NO_FINE_LOCATION)
        assertThat(firstBlockingCause(Preconditions(backgroundLocation = false, locationEnabled = false)))
            .isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(firstBlockingCause(Preconditions(locationEnabled = false, exactAlarms = false)))
            .isEqualTo(AttentionCause.LOCATION_OFF)
        assertThat(firstBlockingCause(Preconditions(exactAlarms = false))).isEqualTo(AttentionCause.EXACT_ALARM_DENIED)
    }

    private fun fix(accuracyM: Float = 11.6f, ageMs: Long = 3_200L) =
        Fix(lat = -33.0, lng = 151.0, accuracyM = accuracyM, timeMs = clock.now - ageMs)

    @Test
    fun aFixAsksOnceWithHighAccuracyAndTheConfiguredTimeoutAndReportsAccuracyAndAge() = runTest {
        location.currentFixResult = CurrentFixResult.Success(fix())
        val vm = viewModel()
        val states = vm.states(this)

        vm.testTracking()

        assertThat(location.currentFixRequests).containsExactly(LocationPriority.HIGH_ACCURACY to 30_000L)
        assertThat(states.value.test).isEqualTo(TestFixState.Success(accuracyM = 12, ageSeconds = 3, blockedBy = null))
    }

    @Test
    fun aFixWhileAPreconditionFailsNamesTheFirstFailingOne() = runTest {
        location.currentFixResult = CurrentFixResult.Success(fix())
        preconditions.value = Preconditions(backgroundLocation = false, exactAlarms = false)
        val vm = viewModel()
        val states = vm.states(this)

        vm.testTracking()

        assertThat((states.value.test as TestFixState.Success).blockedBy).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
    }

    @Test
    fun aFixWhileHealthShowsACauseNamesThatCause() = runTest {
        location.currentFixResult = CurrentFixResult.Success(fix())
        repository.details.value = TrackingDetails(attentionCause = AttentionCause.SERVICE_NOT_RUNNING)
        val vm = viewModel()
        val states = vm.states(this)

        vm.testTracking()

        assertThat((states.value.test as TestFixState.Success).blockedBy).isEqualTo(AttentionCause.SERVICE_NOT_RUNNING)
    }

    @Test
    fun aTimeoutReportsTheConfiguredSeconds() = runTest {
        location.currentFixResult = CurrentFixResult.Timeout
        val vm = viewModel()
        val states = vm.states(this)

        vm.testTracking()

        assertThat(states.value.test).isEqualTo(TestFixState.Timeout(30))
    }

    @Test
    fun aDeniedPermissionIsReportedNotThrown() = runTest {
        location.currentFixResult = CurrentFixResult.Denied
        val vm = viewModel()
        val states = vm.states(this)

        vm.testTracking()

        assertThat(states.value.test).isEqualTo(TestFixState.Denied)
    }

    @Test
    fun theButtonStateIsRunningUntilTheFixArrivesAndASecondTapIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        location.currentFixGate = gate
        location.currentFixResult = CurrentFixResult.Timeout
        val vm = viewModel()
        val states = vm.states(this)

        vm.testTracking()
        vm.testTracking()

        assertThat(states.value.testRunning).isTrue()
        assertThat(location.currentFixRequests).hasSize(1)

        gate.complete(Unit)

        assertThat(states.value.testRunning).isFalse()
        assertThat(states.value.test).isEqualTo(TestFixState.Timeout(30))
    }

    @Test
    fun theTestNeverTouchesTheTrackingStateOrStartsCapture() = runTest {
        location.currentFixResult = CurrentFixResult.Success(fix())
        val vm = viewModel()
        vm.states(this)

        vm.testTracking()

        // Only the one-off request: no updates collector was registered, and the state repository was not poked.
        assertThat(location.requests).isEmpty()
        assertThat(location.activeCollectors).isEqualTo(0)
        assertThat(repository.refreshes).isEqualTo(0)
    }
}
