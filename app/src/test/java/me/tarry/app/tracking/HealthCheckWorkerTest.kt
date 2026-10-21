package me.tarry.app.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
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

/** The worker with fakes, plus the health wiring it drives (gaps, alerts, crash recovery) on a real in-memory DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HealthCheckWorkerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val config = TrackingConfig()
    private val sydney = ZoneId.of("Australia/Sydney")
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var db: TarryDatabase
    private lateinit var trackingRepo: TrackingRepository
    private lateinit var timeline: TimelineRepository
    private lateinit var store: TrackingStatusStore
    private val preconditions = FakePreconditions()
    private val permissions = FakePermissions()
    private val launcher = FakeLauncher()
    private val alarms = FakeAlarmScheduler()
    private val alerts = FakeAlertNotifier()
    private val clock = FakeClock(now = mondayAt(10).toEpochMilli(), zone = sydney)
    private lateinit var health: HealthGapRecorder
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var controller: TrackingController
    private lateinit var checker: HealthChecker

    /** 2026-10-19 is a Monday; the preset is active 07:00-17:00. */
    private fun mondayAt(hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(2026, 10, 19, hour, minute, 0, 0, sydney).toInstant()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        timeline = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        health = HealthGapRecorder(
            store, timeline, alerts, clock, config,
            EffectiveScheduleWindowChecker(
                ScheduleInputsProvider(ScheduleRepository(db.scheduleDao()), trackingRepo, store, config, clock),
                EffectiveScheduleCalculator(config), clock,
            ),
        )
        wire(object : LocationClient {
            override fun updates(params: LocationRequestParams): Flow<List<Fix>> = FakeLocationClient().updates(params)
        })
    }

    private fun wire(locationClient: LocationClient) {
        val inputs = ScheduleInputsProvider(ScheduleRepository(db.scheduleDao()), trackingRepo, store, config, clock)
        val calculator = EffectiveScheduleCalculator(config)
        coordinator = CaptureCoordinator(
            config, trackingRepo, timeline, store, locationClient, FakeActivityClient(), FakeGeofenceClient(), permissions,
            EffectiveScheduleWindowChecker(inputs, calculator, clock), health, clock, captureScope,
        )
        controller = TrackingController(
            coordinator, launcher, store, trackingRepo, inputs, calculator, alarms, FakeSegmentationTrigger(), health,
            clock, config,
        )
        val snapshots = TrackingSnapshotProvider(inputs, calculator, store, preconditions, coordinator, clock)
        checker = HealthChecker(snapshots, HealthEvaluator(config), health, controller)
    }

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
    }

    private suspend fun gaps() = timeline.gapsInRange(0, Long.MAX_VALUE)

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<HealthCheckWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    HealthCheckWorker(appContext, workerParameters, checker)
            })
            .build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun brokenPreconditionInsideTheWindowSetsAttentionOpensGapAlertsAndReconciles() = runBlocking<Unit> {
        preconditions.value = Preconditions(backgroundLocation = false)

        assertThat(runWorker()).isEqualTo(ListenableWorker.Result.success())

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        val gap = gaps().single()
        assertThat(gap.cause).isEqualTo(GapCause.PERMISSION_LOST)
        assertThat(gap.zoneId).isEqualTo(sydney.id)
        assertThat(store.current().openHealthGapId).isEqualTo(gap.id)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_BACKGROUND_LOCATION)
        // reconcile ran as SYSTEM: no direct start, a short alarm instead.
        assertThat(launcher.started).isEmpty()
        assertThat(alarms.armed.last()).isEqualTo(Instant.ofEpochMilli(clock.now + config.deferredStartDelayMs))
    }

    @Test
    fun healthyRunningSessionDoesNothing() = runBlocking<Unit> {
        coordinator.begin(SessionTrigger.SCHEDULE)

        runWorker()

        assertThat(gaps()).isEmpty()
        assertThat(alerts.posted).isEmpty()
        assertThat(store.current().attentionCause).isNull()
    }

    @Test
    fun noSessionAfterTheStartGraceIsServiceNotRunning() = runBlocking<Unit> {
        runWorker()

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.SERVICE_NOT_RUNNING)
        assertThat(gaps().single().cause).isEqualTo(GapCause.SERVICE_KILLED)
    }

    @Test
    fun staleHeartbeatInsideTheWindowOpensANoFixGapFromTheLastHeartbeat() = runBlocking<Unit> {
        coordinator.begin(SessionTrigger.SCHEDULE)
        val heartbeat = clock.now
        clock.now += config.noHeartbeatThresholdMs + 60_000

        runWorker()

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_FIX_WHILE_MOVING)
        val gap = gaps().single()
        assertThat(gap.cause).isEqualTo(GapCause.NO_FIX)
        assertThat(gap.start).isEqualTo(heartbeat)
        assertThat(gap.end).isEqualTo(clock.now)
    }

    @Test
    fun laterChecksExtendTheGapAndRecoveryClosesIt() = runBlocking<Unit> {
        preconditions.value = Preconditions(locationEnabled = false)
        runWorker()
        val id = store.current().openHealthGapId
        clock.now += 15 * 60_000L
        runWorker()

        assertThat(gaps().single().end).isEqualTo(clock.now)
        assertThat(store.current().openHealthGapId).isEqualTo(id)
        assertThat(alerts.posted).hasSize(1)

        preconditions.value = Preconditions()
        clock.now += 15 * 60_000L
        coordinator.begin(SessionTrigger.SCHEDULE)

        val gap = gaps().single()
        assertThat(gap.cause).isEqualTo(GapCause.LOCATION_OFF)
        assertThat(gap.end).isEqualTo(clock.now)
        assertThat(store.current().openHealthGapId).isNull()
        assertThat(store.current().attentionCause).isNull()
        assertThat(alerts.clearCalls).isEqualTo(1)
    }

    @Test
    fun crashRecoveryExtendsTheOpenHealthGapInsteadOfDuplicatingIt() = runBlocking<Unit> {
        val sessionStart = mondayAt(9, 30).toEpochMilli()
        val lastPing = mondayAt(9, 40).toEpochMilli()
        val sessionId = trackingRepo.saveSession(TrackingSession(start = sessionStart, zoneId = sydney.id, trigger = SessionTrigger.SCHEDULE))
        trackingRepo.savePing(
            LocationPing(
                timestamp = lastPing, zoneId = sydney.id, lat = -33.0, lng = 151.0, accuracyM = 10f, speedMps = null,
                bearing = null, activity = null, source = PingSource.FUSED, sessionId = sessionId,
            ),
        )
        store.setLastHeartbeat(lastPing)

        runWorker()

        val gap = gaps().single()
        assertThat(gap.cause).isEqualTo(GapCause.SERVICE_KILLED)
        assertThat(gap.start).isEqualTo(lastPing)
        assertThat(gap.end).isEqualTo(clock.now)
        assertThat(trackingRepo.session(sessionId)!!.endReason).isEqualTo(SessionEndReason.KILLED)
    }

    @Test
    fun crashRecoveryWithoutAHealthGapStillWritesItsOwn() = runBlocking<Unit> {
        val sessionStart = mondayAt(9, 30).toEpochMilli()
        trackingRepo.saveSession(TrackingSession(start = sessionStart, zoneId = sydney.id, trigger = SessionTrigger.SCHEDULE))

        controller.recoverCrashedSession()

        assertThat(gaps().single().cause).isEqualTo(GapCause.SERVICE_KILLED)
    }

    @Test
    fun securityStartFailureInsideTheWindowOpensAPermissionGapAndAlerts() = runBlocking<Unit> {
        controller.onStartFailure(StartFailure.SECURITY)

        val gap = gaps().single()
        assertThat(gap.cause).isEqualTo(GapCause.PERMISSION_LOST)
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_BACKGROUND_LOCATION)
    }

    @Test
    fun deniedForegroundServiceStartOpensAKilledGapAndAlerts() = runBlocking<Unit> {
        launcher.denyStart = true

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(gaps().single().cause).isEqualTo(GapCause.SERVICE_KILLED)
        assertThat(alerts.posted).containsExactly(AttentionCause.EXACT_ALARM_DENIED)
    }

    @Test
    fun startFailureOutsideTheWindowIsNotAnOutage() = runBlocking<Unit> {
        clock.now = mondayAt(20).toEpochMilli()

        controller.onStartFailure(StartFailure.SECURITY)

        assertThat(gaps()).isEmpty()
        assertThat(alerts.posted).isEmpty()
    }

    @Test
    fun reconcileOutsideTheWindowClosesAnOpenGap() = runBlocking<Unit> {
        preconditions.value = Preconditions(locationEnabled = false)
        runWorker()
        clock.now = mondayAt(17).toEpochMilli()

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(gaps().single().end).isEqualTo(clock.now)
        assertThat(store.current().openHealthGapId).isNull()
    }

    @Test
    fun permissionLostMidSessionOpensAGapAndEndsTheSession() = runBlocking<Unit> {
        wire(object : LocationClient {
            override fun updates(params: LocationRequestParams): Flow<List<Fix>> = flow { throw SecurityException("revoked") }
        })
        permissions.fine = false

        coordinator.begin(SessionTrigger.SCHEDULE)
        // The failure is handled on the capture scope: outage, then session end, then the launcher stop.
        // Wait for the last step so no assertion races it (slow CI runners lose that race).
        withTimeout(5_000) { while (launcher.stopCalls == 0) delay(10) }

        val gap = gaps().single()
        assertThat(gap.cause).isEqualTo(GapCause.PERMISSION_LOST)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_FINE_LOCATION)
        assertThat(trackingRepo.openSession()).isNull()
        assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE).single().endReason).isEqualTo(SessionEndReason.PERMISSION_LOST)
        // The service is stopped through the launcher, not left running without a session.
        assertThat(launcher.stopCalls).isGreaterThan(0)
    }

    private fun tuesdayAt(hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(2026, 10, 20, hour, minute, 0, 0, sydney).toInstant()

    private suspend fun seedCrashedSession(lastPing: Instant): Long {
        val id = trackingRepo.saveSession(
            TrackingSession(start = lastPing.toEpochMilli() - 3_600_000, zoneId = sydney.id, trigger = SessionTrigger.SCHEDULE),
        )
        trackingRepo.savePing(
            LocationPing(
                timestamp = lastPing.toEpochMilli(), zoneId = sydney.id, lat = -33.0, lng = 151.0, accuracyM = 10f,
                source = PingSource.FUSED, sessionId = id,
            ),
        )
        return id
    }

    @Test
    fun batteryDyingAt1500AndBootingAt0650NextDayLosesOnlyTheRemainingWindow() = runBlocking<Unit> {
        seedCrashedSession(mondayAt(15))
        clock.now = tuesdayAt(6, 50).toEpochMilli()

        controller.recoverCrashedSession()

        val gap = gaps().single()
        assertThat(gap.cause).isEqualTo(GapCause.SERVICE_KILLED)
        assertThat(gap.start).isEqualTo(mondayAt(15).toEpochMilli())
        assertThat(gap.end).isEqualTo(mondayAt(17).toEpochMilli())
    }

    @Test
    fun bootingInsideALaterWindowLosesEachActiveStretchOnly() = runBlocking<Unit> {
        seedCrashedSession(mondayAt(15))
        clock.now = tuesdayAt(10).toEpochMilli()

        controller.recoverCrashedSession()

        assertThat(gaps().sortedBy { it.start }.map { it.start to it.end }).containsExactly(
            mondayAt(15).toEpochMilli() to mondayAt(17).toEpochMilli(),
            tuesdayAt(7).toEpochMilli() to tuesdayAt(10).toEpochMilli(),
        ).inOrder()
    }

    @Test
    fun missingPreconditionWhileCapturingWithFreshFixesIsAttentionAndOneAlertWithNoGapOrChurn() = runBlocking<Unit> {
        coordinator.begin(SessionTrigger.SCHEDULE)
        preconditions.value = Preconditions(backgroundLocation = false)

        runWorker()
        coordinator.onFixes(listOf(Fix(-33.0, 151.0, 10f, clock.now + 1_000)))
        clock.now += 15 * 60_000L
        coordinator.onFixes(listOf(Fix(-33.001, 151.0, 10f, clock.now)))
        runWorker()

        assertThat(gaps()).isEmpty()
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.clearCalls).isEqualTo(0)
        // Capture is fine, so no restart is attempted.
        assertThat(launcher.started).isEmpty()
    }

    @Test
    fun resolvingAnAttentionOnlyPreconditionClearsItOnTheNextCheck() = runBlocking<Unit> {
        coordinator.begin(SessionTrigger.SCHEDULE)
        preconditions.value = Preconditions(exactAlarms = false)
        runWorker()
        preconditions.value = Preconditions()

        runWorker()

        assertThat(store.current().attentionCause).isNull()
        assertThat(alerts.clearCalls).isEqualTo(1)
    }

    @Test
    fun standingIssueOutsideTheWindowSetsAttentionAndAlertsOncePerCauseWithoutAGap() = runBlocking<Unit> {
        clock.now = mondayAt(20).toEpochMilli()
        preconditions.value = Preconditions(fineLocation = false)

        runWorker()
        clock.now += 15 * 60_000L
        runWorker()

        assertThat(gaps()).isEmpty()
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_FINE_LOCATION)
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_FINE_LOCATION)
        assertThat(launcher.started).isEmpty()

        preconditions.value = Preconditions(exactAlarms = false)
        runWorker()
        assertThat(alerts.posted).containsExactly(AttentionCause.NO_FINE_LOCATION, AttentionCause.EXACT_ALARM_DENIED)
        assertThat(gaps()).isEmpty()
    }

    @Test
    fun grantingExactAlarmsClosesTheGapAndClearsTheAlertThroughTheRecorder() = runBlocking<Unit> {
        launcher.denyStart = true
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(store.current().openHealthGapId).isNotNull()
        launcher.denyStart = false
        clock.now += 60_000

        controller.reconcile(ReconcileSource.ALARM)

        assertThat(store.current().attentionCause).isNull()
        assertThat(store.current().openHealthGapId).isNull()
        assertThat(gaps().single().end).isEqualTo(clock.now)
        assertThat(alerts.clearCalls).isEqualTo(1)
    }

    @Test
    fun locationUpdatesDeadAfterAGenericFailureAreRetriedByTheNextReconcile() = runBlocking<Unit> {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val fake = FakeLocationClient()
        wire(object : LocationClient {
            override fun updates(params: LocationRequestParams): Flow<List<Fix>> =
                if (calls.getAndIncrement() == 0) flow { throw IllegalStateException("boom") } else fake.updates(params)
        })
        coordinator.begin(SessionTrigger.SCHEDULE)
        withTimeout(5_000) { while (alerts.posted.isEmpty()) delay(10) }
        withTimeout(5_000) {
            while (calls.get() < 2) {
                controller.reconcile(ReconcileSource.SYSTEM)
                delay(10)
            }
        }

        assertThat(fake.requests).hasSize(1)
    }

    @Test
    fun openHealthGapIsExtendedOnlyThroughTheTouchingIntervalNotAcrossTheNight() = runBlocking<Unit> {
        clock.now = mondayAt(14, 30).toEpochMilli()
        store.setLastHeartbeat(clock.now)
        preconditions.value = Preconditions(locationEnabled = false)
        runWorker()
        clock.now = mondayAt(14, 45).toEpochMilli()
        runWorker()
        seedCrashedSession(mondayAt(14, 45))
        preconditions.value = Preconditions()
        clock.now = tuesdayAt(8).toEpochMilli()

        controller.recoverCrashedSession()

        val all = gaps().sortedBy { it.start }
        assertThat(all.map { Triple(it.cause, it.start, it.end) }).containsExactly(
            Triple(GapCause.LOCATION_OFF, mondayAt(14, 30).toEpochMilli(), mondayAt(17).toEpochMilli()),
            Triple(GapCause.SERVICE_KILLED, tuesdayAt(7).toEpochMilli(), tuesdayAt(8).toEpochMilli()),
        ).inOrder()
        assertThat(store.current().openHealthGapId).isNull()
    }

    @Test
    fun clearResolvedClearsAnAttentionOnlyCauseOnceTheConditionIsFixed() = runBlocking<Unit> {
        coordinator.begin(SessionTrigger.SCHEDULE)
        preconditions.value = Preconditions(exactAlarms = false)
        runWorker()
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.EXACT_ALARM_DENIED)

        checker.clearResolved()
        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.EXACT_ALARM_DENIED)

        preconditions.value = Preconditions()
        checker.clearResolved()

        assertThat(store.current().attentionCause).isNull()
        assertThat(alerts.clearCalls).isEqualTo(1)
    }

    @Test
    fun clearResolvedLeavesAGapBackedCauseToTheRecovery() = runBlocking<Unit> {
        preconditions.value = Preconditions(locationEnabled = false)
        runWorker()
        preconditions.value = Preconditions()

        checker.clearResolved()

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.LOCATION_OFF)
        assertThat(store.current().openHealthGapId).isNotNull()
    }

    private fun assertNoNightAndNoOverlap() = runBlocking<Unit> {
        val all = gaps().sortedBy { it.start }
        for (g in all) {
            val nightStart = mondayAt(17).toEpochMilli()
            val nightEnd = tuesdayAt(7).toEpochMilli()
            assertThat(g.end <= nightStart || g.start >= nightEnd).isTrue()
        }
        for ((a, b) in all.zipWithNext()) assertThat(b.start).isAtLeast(a.end)
    }

    @Test
    fun healthCheckRunningBeforeCrashRecoveryAfterRebootDoesNotDoubleCountOrCoverTheNight() = runBlocking<Unit> {
        clock.now = mondayAt(14, 30).toEpochMilli()
        store.setLastHeartbeat(clock.now)
        preconditions.value = Preconditions(locationEnabled = false)
        runWorker()
        clock.now = mondayAt(14, 45).toEpochMilli()
        runWorker()
        seedCrashedSession(mondayAt(14, 45))
        preconditions.value = Preconditions()
        clock.now = tuesdayAt(8).toEpochMilli()

        // The worker wins the race against BOOT_COMPLETED's recovery.
        checker.check()
        controller.recoverCrashedSession()

        assertNoNightAndNoOverlap()
        assertThat(gaps().sortedBy { it.start }.map { Triple(it.cause, it.start, it.end) }).containsExactly(
            Triple(GapCause.LOCATION_OFF, mondayAt(14, 30).toEpochMilli(), mondayAt(17).toEpochMilli()),
            Triple(GapCause.SERVICE_KILLED, tuesdayAt(7).toEpochMilli(), tuesdayAt(8).toEpochMilli()),
        ).inOrder()
    }

    @Test
    fun workerOutsideTheWindowAfterRebootDoesNotCloseMondaysGapAtThreeAm() = runBlocking<Unit> {
        clock.now = mondayAt(14, 30).toEpochMilli()
        store.setLastHeartbeat(clock.now)
        preconditions.value = Preconditions(locationEnabled = false)
        runWorker()
        clock.now = mondayAt(14, 45).toEpochMilli()
        runWorker()
        clock.now = tuesdayAt(3).toEpochMilli()

        runWorker()

        assertNoNightAndNoOverlap()
        assertThat(gaps().single().end).isEqualTo(mondayAt(17).toEpochMilli())
    }

    @Test
    fun clearResolvedReportsADifferentIssueInsteadOfJustClearing() = runBlocking<Unit> {
        coordinator.begin(SessionTrigger.SCHEDULE)
        preconditions.value = Preconditions(exactAlarms = false)
        runWorker()
        preconditions.value = Preconditions(backgroundLocation = false)

        checker.clearResolved()

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
        assertThat(alerts.posted.last()).isEqualTo(AttentionCause.NO_BACKGROUND_LOCATION)
    }
}
