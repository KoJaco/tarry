package me.tarry.app.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.DetectedActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackingControllerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val config = TrackingConfig()
    private val sydney = ZoneId.of("Australia/Sydney")
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var db: TarryDatabase
    private lateinit var trackingRepo: TrackingRepository
    private lateinit var scheduleRepo: ScheduleRepository
    private lateinit var timelineRepo: TimelineRepository
    private lateinit var health: HealthGapRecorder
    private lateinit var store: TrackingStatusStore
    private val permissions = FakePermissions()
    private val launcher = FakeLauncher()
    private val alarms = FakeAlarmScheduler()
    private val segmentation = FakeSegmentationTrigger()
    private val alerts = FakeAlertNotifier()
    private val clock = FakeClock(now = mondayAt(10).toEpochMilli(), zone = sydney)
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var controller: TrackingController

    /** 2026-10-19 is a Monday; the preset is active 07:00-17:00. */
    private fun mondayAt(hour: Int, minute: Int = 0, day: Int = 19): Instant =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, sydney).toInstant()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        scheduleRepo = ScheduleRepository(db.scheduleDao())
        store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        val inputs = ScheduleInputsProvider(scheduleRepo, trackingRepo, store, config, clock)
        val calculator = EffectiveScheduleCalculator(config)
        timelineRepo = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        health = HealthGapRecorder(store, timelineRepo, alerts, clock, config, EffectiveScheduleWindowChecker(inputs, calculator, clock))
        coordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo,
            store, FakeLocationClient(), FakeActivityClient(), FakeGeofenceClient(), FakeStayRadius(), permissions,
            EffectiveScheduleWindowChecker(inputs, calculator, clock), health, clock, captureScope,
        )
        controller = TrackingController(
            coordinator, launcher, store, trackingRepo, inputs, calculator, alarms, segmentation, health, clock, config,
        )
    }

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
    }

    private suspend fun sessions() = trackingRepo.sessionsInRange(0, Long.MAX_VALUE)

    private suspend fun overrides() = trackingRepo.overridesInRange(0, Long.MAX_VALUE)

    @Test
    fun reconcileSeedsTheMonFriPreset() = runBlocking {
        controller.reconcile()

        assertThat(scheduleRepo.template()).isEqualTo(Schedule.MON_FRI_7_TO_17)
    }

    @Test
    fun reconcileInsideWindowStartsScheduledServiceAndArmsWindowEnd() = runBlocking {
        controller.reconcile(ReconcileSource.ALARM)

        assertThat(launcher.started).containsExactly(SessionTrigger.SCHEDULE)
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(17))
    }

    @Test
    fun reconcileOutsideWindowDoesNotStartAndArmsNextWindow() = runBlocking {
        clock.now = mondayAt(18).toEpochMilli()
        controller.reconcile(ReconcileSource.ALARM)

        assertThat(launcher.started).isEmpty()
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(7, day = 20))
    }

    @Test
    fun systemReconcileDefersTheStartToAShortAlarm() = runBlocking {
        controller.reconcile(ReconcileSource.SYSTEM)

        assertThat(launcher.started).isEmpty()
        assertThat(alarms.armed.last()).isEqualTo(Instant.ofEpochMilli(clock.now + config.deferredStartDelayMs))
    }

    @Test
    fun deniedBackgroundStartSurfacesAsExactAlarmDenied() = runBlocking {
        launcher.denyStart = true
        controller.reconcile(ReconcileSource.ALARM)

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.EXACT_ALARM_DENIED)
        assertThat(launcher.stopCalls).isEqualTo(1)
    }

    @Test
    fun inexactAlarmSetsDegradedFlagAndExactClearsIt() = runBlocking {
        alarms.exact = false
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(store.current().alarmDegraded).isTrue()

        store.setAttentionCause(AttentionCause.EXACT_ALARM_DENIED)
        alarms.exact = true
        controller.reconcile(ReconcileSource.SYSTEM)
        assertThat(store.current().alarmDegraded).isFalse()
        assertThat(store.current().attentionCause).isNull()
    }

    @Test
    fun windowEndClosesSessionAndCallsSegmentationTrigger() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        stayFrom(clock.now)
        clock.now = mondayAt(17).toEpochMilli()

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(trackingRepo.openSession()).isNull()
        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.SCHEDULE_END)
        assertThat(segmentation.windowEnds).isEqualTo(1)
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(7, day = 20))
    }

    @Test
    fun startedServiceNotifiesCaptureStartedAndWindowEndNotifiesCaptureStopped() = runBlocking {
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(segmentation.captureStarts).isEqualTo(1)
        assertThat(segmentation.captureStops).isEqualTo(0)

        coordinator.begin(SessionTrigger.SCHEDULE)
        stayFrom(clock.now)
        clock.now = mondayAt(17).toEpochMilli()
        controller.reconcile(ReconcileSource.ALARM)

        assertThat(segmentation.captureStops).isEqualTo(1)
    }

    @Test
    fun deniedStartDoesNotNotifyCaptureStarted() = runBlocking {
        launcher.denyStart = true
        controller.reconcile(ReconcileSource.ALARM)

        assertThat(segmentation.captureStarts).isEqualTo(0)
    }

    @Test
    fun reconcileRecoversASessionLeftOpenByADeadProcess() = runBlocking {
        val start = mondayAt(9).toEpochMilli()
        trackingRepo.saveSession(TrackingSession(start = start, zoneId = sydney.id, trigger = SessionTrigger.SCHEDULE))

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.KILLED)
    }

    @Test
    fun manualStartOutsideWindowLaunchesManualTriggerAndPersistsOverride() = runBlocking {
        clock.now = mondayAt(20).toEpochMilli()
        controller.start(ManualOverrideEndMode.UNTIL_STOPPED)

        assertThat(launcher.started).containsExactly(SessionTrigger.MANUAL)
        val row = overrides().single()
        assertThat(row.type).isEqualTo(ManualOverrideType.START)
        assertThat(row.active).isTrue()
    }

    @Test
    fun manualStartDurationStoresEndAndArmsAlarmThere() = runBlocking {
        clock.now = mondayAt(20).toEpochMilli()
        controller.start(ManualOverrideEndMode.DURATION, durationMin = 60)

        assertThat(overrides().single().end).isEqualTo(mondayAt(21).toEpochMilli())
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(21))
    }

    @Test
    fun pauseEndsSessionAsUserPauseAndRearmsForTheResume() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)

        controller.pause(PauseKind.PAUSE_1H)

        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.USER_PAUSE)
        val pause = overrides().single()
        assertThat(pause.type).isEqualTo(ManualOverrideType.PAUSE)
        assertThat(pause.end).isEqualTo(mondayAt(11).toEpochMilli())
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(11))
    }

    @Test
    fun pauseExpiryAtTheAlarmRestartsCaptureAndDeactivatesTheRow() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        controller.pause(PauseKind.PAUSE_30M)
        launcher.started.clear()

        clock.now = mondayAt(10, 30).toEpochMilli()
        controller.reconcile(ReconcileSource.ALARM)

        assertThat(launcher.started).containsExactly(SessionTrigger.SCHEDULE)
        assertThat(trackingRepo.observeActiveOverrides().first()).isEmpty()
    }

    @Test
    fun resumeEndsThePauseImmediately() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        controller.pause(PauseKind.UNTIL_NEXT_WINDOW)
        launcher.started.clear()

        controller.resume()

        assertThat(launcher.started).containsExactly(SessionTrigger.SCHEDULE)
        assertThat(trackingRepo.observeActiveOverrides().first()).isEmpty()
    }

    @Test
    fun stopEndsSessionAndSuppressesTheWindowUntilTheNextOne() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        launcher.started.clear()

        controller.stop()

        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.USER_STOP)
        val suppress = overrides().single()
        assertThat(suppress.type).isEqualTo(ManualOverrideType.PAUSE)
        assertThat(suppress.endMode).isEqualTo(ManualOverrideEndMode.UNTIL_STOPPED)
        assertThat(suppress.end).isEqualTo(mondayAt(7, day = 20).toEpochMilli())
        assertThat(launcher.started).isEmpty()
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(7, day = 20))

        clock.now = mondayAt(7, day = 20).toEpochMilli()
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(launcher.started).containsExactly(SessionTrigger.SCHEDULE)
        Unit
    }

    @Test
    fun stopOfAManualStartOutsideWindowAddsNoSuppression() = runBlocking {
        clock.now = mondayAt(20).toEpochMilli()
        controller.start(ManualOverrideEndMode.UNTIL_STOPPED)
        coordinator.begin(SessionTrigger.MANUAL)

        controller.stop()

        assertThat(trackingRepo.openSession()).isNull()
        assertThat(overrides().single().active).isFalse()
        assertThat(trackingRepo.observeActiveOverrides().first()).isEmpty()
    }

    @Test
    fun stopArrivingBeforeServiceBeginLeavesNoOpenSession() = runBlocking {
        controller.start(ManualOverrideEndMode.UNTIL_STOPPED)
        controller.stop()

        // The service's queued start now reaches the controller.
        val stopsBefore = launcher.stopCalls
        controller.onServiceStarted(SessionTrigger.MANUAL)

        assertThat(trackingRepo.openSession()).isNull()
        assertThat(launcher.stopCalls).isGreaterThan(stopsBefore)
    }

    @Test
    fun serviceStartInsideAnActiveStateBeginsTheSession() = runBlocking {
        controller.reconcile()
        controller.onServiceStarted(SessionTrigger.SCHEDULE)

        assertThat(trackingRepo.openSession()).isNotNull()
    }

    @Test
    fun securityFailureWithoutBackgroundLocationRecordsNoBackgroundLocation() = runBlocking {
        controller.onStartFailure(StartFailure.SECURITY)

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(launcher.stopCalls).isEqualTo(1)
    }

    @Test
    fun securityFailureWithoutFineLocationRecordsNoFineLocation() = runBlocking {
        permissions.fine = false
        controller.onStartFailure(StartFailure.SECURITY)

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_FINE_LOCATION)
    }

    @Test
    fun debugTestWindowStartsSoonAndArmsTheAlarmForIt() = runBlocking {
        clock.now = mondayAt(20).toEpochMilli()
        controller.startDebugTestWindow()

        val expected = Instant.ofEpochMilli(clock.now + config.debugTestWindowDelayMs)
        assertThat(store.current().debugTestWindowStartMs).isEqualTo(expected.toEpochMilli())
        assertThat(alarms.armed.last()).isEqualTo(expected)
    }

    @Test
    fun activityEventsReachTheMachineAndGetSavedOnPings() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        controller.onActivity(ActivityType.IN_VEHICLE)
        coordinator.onFixes(listOf(Fix(-33.0, 151.0, 10f, clock.now + 1)))

        val ping = trackingRepo.pingsForSession(trackingRepo.openSession()!!.id).single()
        assertThat(ping.activity).isEqualTo(ActivityType.IN_VEHICLE)
    }

    @Test
    fun detectedActivitiesMapToCoreTypes() {
        assertThat(detectedActivityToType(DetectedActivity.STILL)).isEqualTo(ActivityType.STILL)
        assertThat(detectedActivityToType(DetectedActivity.RUNNING)).isEqualTo(ActivityType.WALKING)
        assertThat(detectedActivityToType(DetectedActivity.WALKING)).isEqualTo(ActivityType.WALKING)
        assertThat(detectedActivityToType(DetectedActivity.ON_BICYCLE)).isEqualTo(ActivityType.ON_BICYCLE)
        assertThat(detectedActivityToType(DetectedActivity.IN_VEHICLE)).isEqualTo(ActivityType.IN_VEHICLE)
        assertThat(detectedActivityToType(DetectedActivity.TILTING)).isEqualTo(ActivityType.UNKNOWN)
    }

    @Test
    fun stopWithNoNextWindowWithinTheLookAheadIsBoundedNotPermanent() = runBlocking {
        val short = TrackingConfig(scheduleLookAheadDays = 3)
        val inputs = ScheduleInputsProvider(scheduleRepo, trackingRepo, store, short, clock)
        val shortController = TrackingController(
            coordinator, launcher, store, trackingRepo, inputs, EffectiveScheduleCalculator(short), alarms, segmentation,
            health, clock, short,
        )
        // Monday-only schedule: the next window is a week away, beyond the 3-day look-ahead.
        var mondayOnly = Schedule.EMPTY
        for (slot in 14..33) mondayOnly = mondayOnly.set(java.time.DayOfWeek.MONDAY, slot, true)
        scheduleRepo.saveTemplate(mondayOnly, clock.now)
        shortController.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)

        shortController.stop()

        val suppress = overrides().single()
        assertThat(suppress.end).isEqualTo(clock.now + 3 * 24 * 60 * 60_000L)

        // Once the bound passes, the suppression is lifted by the next reconcile.
        clock.now = mondayAt(10, day = 22).toEpochMilli() + 1
        shortController.reconcile(ReconcileSource.ALARM)
        assertThat(trackingRepo.observeActiveOverrides().first()).isEmpty()
    }

    @Test
    fun captureFailureReportsTheOutageThenEndsTheSessionAndStopsTheService() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        val stopsBefore = launcher.stopCalls

        controller.onCaptureFailed(coordinator.activeSessionId!!, HealthIssue(AttentionCause.NO_FINE_LOCATION, me.tarry.core.data.model.GapCause.PERMISSION_LOST))

        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.PERMISSION_LOST)
        assertThat(launcher.stopCalls).isGreaterThan(stopsBefore)
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_FINE_LOCATION)
        assertThat(timelineRepo.gapsInRange(0, Long.MAX_VALUE).single().cause)
            .isEqualTo(me.tarry.core.data.model.GapCause.PERMISSION_LOST)
    }

    @Test
    fun serviceStartWhoseBeginFailsStopsTheServiceAndRecordsTheOutage() = runBlocking {
        var removeAttempts = 0
        val activity = FakeActivityClient()
        val failingGeofence = object : GeofenceClient {
            override suspend fun addStayGeofence(lat: Double, lng: Double, radiusM: Float) = Unit
            override suspend fun remove() {
                removeAttempts++
                throw IllegalStateException("geofence api down")
            }
        }
        val failingCoordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, FakeLocationClient(), activity, failingGeofence, FakeStayRadius(),
            permissions, FakeWindowChecker(), health, clock, captureScope,
        )
        val inputs = ScheduleInputsProvider(scheduleRepo, trackingRepo, store, config, clock)
        val c = TrackingController(
            failingCoordinator, launcher, store, trackingRepo, inputs, EffectiveScheduleCalculator(config), alarms,
            segmentation, health, clock, config,
        )

        c.onServiceStarted(SessionTrigger.SCHEDULE)

        assertThat(launcher.stopCalls).isGreaterThan(0)
        assertThat(removeAttempts).isEqualTo(2)
        assertThat(activity.unregisterCalls).isEqualTo(1)
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.SERVICE_NOT_RUNNING)
        assertThat(timelineRepo.gapsInRange(0, Long.MAX_VALUE).single().cause)
            .isEqualTo(me.tarry.core.data.model.GapCause.SERVICE_KILLED)
    }

    @Test
    fun captureFailureOfAnEarlierSessionDoesNotStopANewOne() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        val staleId = coordinator.activeSessionId!!
        coordinator.end(SessionEndReason.SCHEDULE_END)
        coordinator.begin(SessionTrigger.SCHEDULE)
        val stopsBefore = launcher.stopCalls

        controller.onCaptureFailed(staleId, HealthIssue(AttentionCause.NO_FINE_LOCATION, me.tarry.core.data.model.GapCause.PERMISSION_LOST))

        assertThat(coordinator.isActive).isTrue()
        assertThat(launcher.stopCalls).isEqualTo(stopsBefore)
        assertThat(store.current().attentionCause).isNull()
    }

    @Test
    fun resumeFailingAfterAPauseStartsTheGapAtTheResumeNotOverCapturedOrPausedTime() = runBlocking {
        clock.now = mondayAt(7).toEpochMilli()
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        clock.now = mondayAt(10).toEpochMilli()
        controller.pause(PauseKind.PAUSE_1H)
        clock.now = mondayAt(11).toEpochMilli()
        launcher.denyStart = true

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(timelineRepo.gapsInRange(0, Long.MAX_VALUE).single().start).isEqualTo(mondayAt(11).toEpochMilli())
    }

    /** One fix and STILL at [atMs]: STATIONARY with the stay starting there. */
    private suspend fun stayFrom(atMs: Long) {
        coordinator.onFixes(listOf(Fix(-33.0, 151.0, 10f, atMs)))
        coordinator.onActivity(ActivityType.STILL)
    }

    /**
     * The settle reconcile runs on the coordinator's scope, and capture stops before it enqueues segmentation and re-arms
     * the alarm. So wait for that last step, not just for capture to stop (CI caught `windowEnds` still at 0).
     */
    private suspend fun awaitSessionEnd(nextAlarm: Instant) = kotlinx.coroutines.withTimeout(5_000) {
        while (coordinator.isActive || alarms.armed.lastOrNull() != nextAlarm) kotlinx.coroutines.delay(10)
    }

    @Test
    fun windowEndWhileMovingKeepsTheSessionOpenFinishingUpWithTheCapAsTheOnlyAlarm() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        clock.now = mondayAt(17).toEpochMilli()
        val stopsBefore = launcher.stopCalls

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(coordinator.isActive).isTrue()
        assertThat(launcher.stopCalls).isEqualTo(stopsBefore)
        assertThat(sessions().single().end).isNull()
        assertThat(store.current().finishingUp).isTrue()
        assertThat(segmentation.windowEnds).isEqualTo(0)
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(17).plusMillis(config.windowEndGraceMaxMs))
    }

    @Test
    fun windowEndWithAYoungStayKeepsTheSessionOpenUntilTheStayIsTenMinutesOld() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        // Arrived 5 minutes before the window end.
        clock.now = mondayAt(16, 55).toEpochMilli()
        stayFrom(clock.now)
        clock.now = mondayAt(17).toEpochMilli()

        controller.reconcile(ReconcileSource.ALARM)
        assertThat(coordinator.isActive).isTrue()
        assertThat(store.current().finishingUp).isTrue()

        // A fix once the stay is 11 minutes old confirms it: the session ends by itself, no timer involved.
        clock.now = mondayAt(17, 6).toEpochMilli()
        coordinator.onFixes(listOf(Fix(-33.0, 151.0, 10f, clock.now)))
        awaitSessionEnd(nextAlarm = mondayAt(7, day = 20))

        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.SCHEDULE_END)
        assertThat(segmentation.windowEnds).isEqualTo(1)
        assertThat(store.current().finishingUp).isFalse()
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(7, day = 20))
    }

    @Test
    fun windowEndWithAnEstablishedStayStopsAtOnce() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        clock.now = mondayAt(16, 30).toEpochMilli()
        stayFrom(clock.now)
        clock.now = mondayAt(17).toEpochMilli()

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(coordinator.isActive).isFalse()
        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.SCHEDULE_END)
        assertThat(store.current().finishingUp).isFalse()
    }

    @Test
    fun graceCapEndsTheSessionEvenIfCaptureIsStillMoving() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        clock.now = mondayAt(17).toEpochMilli()
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(coordinator.isActive).isTrue()

        clock.now = mondayAt(17).toEpochMilli() + config.windowEndGraceMaxMs
        controller.reconcile(ReconcileSource.ALARM)

        assertThat(coordinator.isActive).isFalse()
        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.SCHEDULE_END)
        assertThat(segmentation.windowEnds).isEqualTo(1)
        assertThat(store.current().finishingUp).isFalse()
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(7, day = 20))
    }

    @Test
    fun manualSessionAndPauseAreNeverGivenGrace() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.MANUAL)
        clock.now = mondayAt(17).toEpochMilli()

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(coordinator.isActive).isFalse()
        assertThat(store.current().finishingUp).isFalse()
    }

    @Test
    fun pauseDuringGraceStopsImmediately() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        clock.now = mondayAt(17).toEpochMilli()
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(coordinator.isActive).isTrue()

        controller.pause(PauseKind.PAUSE_1H)

        assertThat(coordinator.isActive).isFalse()
        assertThat(store.current().finishingUp).isFalse()
        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.USER_PAUSE)
    }

    @Test
    fun anActivityEventOlderThanTheLastAppliedOneFromTheReceiverIsIgnored() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        coordinator.onFixes(listOf(Fix(-33.0, 151.0, 10f, clock.now + 1)))

        controller.onActivity(ActivityType.STILL, eventAgeMs = 1_000)
        assertThat(store.current().captureMode).isEqualTo(CaptureMode.STATIONARY)

        controller.onActivity(ActivityType.WALKING, eventAgeMs = 10 * 60_000L)
        assertThat(store.current().captureMode).isEqualTo(CaptureMode.STATIONARY)
    }

    @Test
    fun aGraceSessionCanBeStoppedByTheUser() = runBlocking {
        controller.reconcile()
        coordinator.begin(SessionTrigger.SCHEDULE)
        clock.now = mondayAt(17).toEpochMilli()
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(coordinator.isActive).isTrue()

        controller.stop()

        assertThat(coordinator.isActive).isFalse()
        assertThat(store.current().finishingUp).isFalse()
        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.USER_STOP)
    }

    @Test
    fun graceThatLostItsProcessIsSettledAtTheNextReconcileWithSegmentationAndAGap() = runBlocking {
        controller.reconcile()
        val start = mondayAt(16, 30).toEpochMilli()
        val sessionId = trackingRepo.saveSession(TrackingSession(start = start, zoneId = sydney.id, trigger = SessionTrigger.SCHEDULE))
        val lastPing = mondayAt(17, 5).toEpochMilli()
        trackingRepo.savePing(
            me.tarry.core.data.db.entity.LocationPing(
                timestamp = lastPing, zoneId = sydney.id, lat = -33.0, lng = 151.0, accuracyM = 10f, speedMps = null,
                bearing = null, activity = null, source = me.tarry.core.data.model.PingSource.FUSED, sessionId = sessionId,
            ),
        )
        // The grace began at 17:00 and the process died at 17:05; the cap alarm fires at 19:00.
        store.setFinishingUp(true, mondayAt(17).toEpochMilli())
        clock.now = mondayAt(17).toEpochMilli() + config.windowEndGraceMaxMs

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(coordinator.isActive).isFalse()
        assertThat(store.current().finishingUp).isFalse()
        assertThat(segmentation.windowEnds).isEqualTo(1)
        assertThat(sessions().single().endReason).isEqualTo(SessionEndReason.KILLED)
        val gap = timelineRepo.gapsInRange(0, Long.MAX_VALUE).single()
        assertThat(gap.start).isEqualTo(lastPing)
        assertThat(gap.end).isEqualTo(clock.now)
    }
}
