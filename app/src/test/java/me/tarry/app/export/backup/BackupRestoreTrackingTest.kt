package me.tarry.app.export.backup

import me.tarry.segmentation.SegmentationParams
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.CaptureCoordinator
import me.tarry.app.tracking.EffectiveScheduleCalculator
import me.tarry.app.tracking.EffectiveScheduleWindowChecker
import me.tarry.app.tracking.FakeActivityClient
import me.tarry.app.tracking.FakeAlarmScheduler
import me.tarry.app.tracking.FakeAlertNotifier
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeEntitlement
import me.tarry.app.tracking.FakeGeofenceClient
import me.tarry.app.tracking.FakeLauncher
import me.tarry.app.tracking.FakeLocationClient
import me.tarry.app.tracking.FakePermissions
import me.tarry.app.tracking.FakeSegmentationTrigger
import me.tarry.app.tracking.FakeStayRadius
import me.tarry.app.tracking.FakeWindowEndGrace
import me.tarry.app.tracking.HealthGapRecorder
import me.tarry.app.tracking.PauseKind
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.ScheduleAlarmScheduler
import me.tarry.app.tracking.ScheduleInputsProvider
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.app.tracking.createTrackingStatusDataStore
import me.tarry.core.data.db.BackupSnapshot
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.repository.TimelineRepository
import me.tarry.core.data.repository.TrackingRepository
import me.tarry.core.data.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A restore against the real controller, coordinator, health recorder and Room, with the tracking fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupRestoreTrackingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val config = TrackingConfig()
    private val sydney = ZoneId.of("Australia/Sydney")
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var db: TarryDatabase
    private lateinit var trackingRepo: TrackingRepository
    private lateinit var store: TrackingStatusStore
    private val launcher = FakeLauncher()
    private val clock = FakeClock(now = at(19, 10).toEpochMilli(), zone = sydney)
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var scheduleRepo: ScheduleRepository
    private lateinit var inputs: ScheduleInputsProvider
    private lateinit var health: HealthGapRecorder
    private val alarms = ToggleAlarms()
    private lateinit var controller: TrackingController

    /** 2026-10-19 is a Monday; the preset schedule is active Mon-Fri 07:00-17:00. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, sydney).toInstant()

    private class ToggleAlarms : ScheduleAlarmScheduler {
        var fail = false
        private val inner = FakeAlarmScheduler()
        override fun canScheduleExact() = true
        override fun arm(at: Instant?): Boolean {
            if (fail) throw IllegalStateException("alarm manager unavailable")
            return inner.arm(at)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackingRepo = TrackingRepository(db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao())
        scheduleRepo = ScheduleRepository(db.scheduleDao())
        store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        inputs = ScheduleInputsProvider(scheduleRepo, trackingRepo, store, config, clock)
        val calculator = EffectiveScheduleCalculator(config)
        val timelineRepo = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        val alerts = FakeAlertNotifier()
        health = HealthGapRecorder(store, timelineRepo, alerts, clock, config, EffectiveScheduleWindowChecker(inputs, calculator, clock))
        coordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, FakeLocationClient(), FakeActivityClient(), FakeGeofenceClient(),
            FakeStayRadius(), FakePermissions(), EffectiveScheduleWindowChecker(inputs, calculator, clock), health, clock, captureScope,
        )
        controller = TrackingController(
            coordinator, launcher, store, trackingRepo, scheduleRepo, inputs, calculator, alarms, FakeSegmentationTrigger(), health, clock, config, FakeWindowEndGrace(), FakeEntitlement(clock),
        )
    }

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
    }

    private suspend fun activeOverrides() = trackingRepo.observeActiveOverrides().first()

    @Test
    fun aFailedBlockInsideAWindowEndsTheStopItWroteAndTrackingStartsAgain() = runBlocking<Unit> {
        controller.reconcile(ReconcileSource.ALARM)
        coordinator.begin(SessionTrigger.SCHEDULE)
        launcher.started.clear()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { controller.withCaptureSuspended<Unit> { throw IllegalStateException("insert failed") } }
        }

        assertThat(activeOverrides()).isEmpty()
        assertThat(launcher.started).containsExactly(SessionTrigger.SCHEDULE)
    }

    @Test
    fun aFailedBlockPutsAnEndedManualStartBack() = runBlocking<Unit> {
        clock.now = at(19, 20).toEpochMilli() // outside the window
        controller.start(ManualOverrideEndMode.UNTIL_STOPPED)
        coordinator.begin(SessionTrigger.MANUAL)
        val before = activeOverrides().single()
        launcher.started.clear()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { controller.withCaptureSuspended<Unit> { throw IllegalStateException("insert failed") } }
        }

        assertThat(activeOverrides().single()).isEqualTo(before)
        assertThat(launcher.started).containsExactly(SessionTrigger.MANUAL)
    }

    @Test
    fun aSuccessfulBlockReconcilesAgainstWhatTheDataNowSays() = runBlocking<Unit> {
        controller.reconcile(ReconcileSource.ALARM)
        coordinator.begin(SessionTrigger.SCHEDULE)
        launcher.started.clear()

        val result = controller.withCaptureSuspended { 42 }

        assertThat(result).isEqualTo(42)
        assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE).single().endReason).isNotNull()
        // The stop's in-window PAUSE is part of the data the block would replace; here nothing replaced it.
        assertThat(launcher.started).isEmpty()
    }

    @Test
    fun aReconcileFailureAfterAGoodBlockIsReportedAsAnOutageNotAsAFailedRestore() = runBlocking<Unit> {
        controller.reconcile(ReconcileSource.ALARM)
        alarms.fail = true

        val result = controller.withCaptureSuspended { "restored" }

        assertThat(result).isEqualTo("restored")
        assertThat(store.current().openHealthGapId).isNotNull()
    }

    @Test
    fun aSessionCannotBeginWhileTheBlockRunsAndNothingLandsInTheOldData() = runBlocking<Unit> {
        controller.reconcile(ReconcileSource.ALARM)
        val late = CoroutineScope(Dispatchers.Default)
        try {
            controller.withCaptureSuspended {
                // A service start arrives mid-restore: it has to wait for the controller lock.
                val start = late.async { controller.onServiceStarted(SessionTrigger.SCHEDULE) }
                delay(300)
                assertThat(start.isCompleted).isFalse()
                assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE)).isEmpty()
                start
            }.await()
        } finally {
            late.cancel()
        }
    }

    @Test
    fun aBackupTakenMidSessionDaysAgoWritesNoGapWhereAnOrdinaryCrashRecoveryWould() = runBlocking<Unit> {
        val friday = at(16, 10)
        val open = TrackingSession(1, friday.toEpochMilli(), null, "Australia/Sydney", SessionTrigger.SCHEDULE, null)
        val ping = LocationPing(1, friday.toEpochMilli() + 60_000, "Australia/Sydney", -33.0, 151.0, 5f, null, null, null, PingSource.FUSED, 1)

        // Control: the open row on a live DB is turned into Gaps by crash recovery (schedule active Friday to now).
        trackingRepo.saveSession(open)
        trackingRepo.savePing(ping)
        controller.reconcile(ReconcileSource.ALARM)
        assertThat(BackupStore(db).snapshot().gaps).isNotEmpty()

        // The same open session arriving through a restore: no Gap for backup-to-restore time.
        val snapshot = BackupSnapshot(sessions = listOf(open), pings = listOf(ping))
        val bytes = encodeFile(fileOf(snapshot).copy(createdAt = at(16, 12).toEpochMilli()))
        val settingsStore = PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("settings.preferences_pb") }
        val manager = BackupManager(
            ApplicationProvider.getApplicationContext(), BackupStore(db),
            settingsStore,
            PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("declined.preferences_pb") },
            SettingsRepository(settingsStore),
            object : RestoreHooks {
                override suspend fun <T> captureSuspended(block: suspend () -> T): T = controller.withCaptureSuspended(block)
                override suspend fun <T> captureSuspendedKeepingOverrides(block: suspend (activeBefore: List<ManualOverride>) -> T): T =
                    controller.withCaptureSuspendedKeeping(block)
                override suspend fun afterRestore() = Unit
            },
            clock,
            SegmentationParams(),
        )

        manager.restore(manager.preview { ByteArrayInputStream(bytes) })
        controller.reconcile(ReconcileSource.ALARM) // and again, as the next alarm would

        val after = BackupStore(db).snapshot()
        assertThat(after.gaps).isEmpty()
        assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE).first { it.id == 1L }.endReason).isNotNull()
        assertThat(after.sessions.first { it.id == 1L }.end).isEqualTo(ping.timestamp)
    }

    @Test
    fun restoredOverridesStillActiveAtBackupTimeEndThereAndOthersAreUntouched() = runBlocking<Unit> {
        val createdAt = at(19, 9).toEpochMilli()
        val s = everythingSnapshot()
        val snapshot = s.copy(
            manualOverrides = listOf(
                s.manualOverrides[0].copy(id = 1, type = ManualOverrideType.START, start = createdAt - 5_000, end = null, endMode = ManualOverrideEndMode.UNTIL_STOPPED, active = true),
                s.manualOverrides[0].copy(id = 2, type = ManualOverrideType.PAUSE, start = createdAt - 4_000, end = createdAt + 3_600_000, endMode = ManualOverrideEndMode.DURATION, active = true),
                s.manualOverrides[0].copy(id = 3, type = ManualOverrideType.PAUSE, start = createdAt - 9_000, end = createdAt - 8_000, endMode = ManualOverrideEndMode.DURATION, active = false),
            ),
        )
        BackupStore(db).replaceAll(snapshot, createdAt)

        val rows = BackupStore(db).snapshot().manualOverrides.associateBy { it.id }
        assertThat(rows.getValue(1).end).isEqualTo(createdAt)
        assertThat(rows.getValue(1).active).isFalse()
        assertThat(rows.getValue(1).endMode).isEqualTo(ManualOverrideEndMode.UNTIL_STOPPED)
        assertThat(rows.getValue(1).start).isEqualTo(createdAt - 5_000)
        assertThat(rows.getValue(2).end).isEqualTo(createdAt)
        assertThat(rows.getValue(2).active).isFalse()
        assertThat(rows.getValue(3)).isEqualTo(snapshot.manualOverrides[2])
    }

    private fun deleteAllManager() = BackupManager(
        ApplicationProvider.getApplicationContext(), BackupStore(db),
        PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("settings.preferences_pb") },
        PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("declined.preferences_pb") },
        SettingsRepository(PreferenceDataStoreFactory.create(scope = ioScope) { folder.root.resolve("settings.preferences_pb") }),
        object : RestoreHooks {
            override suspend fun <T> captureSuspended(block: suspend () -> T): T = controller.withCaptureSuspended(block)
            override suspend fun <T> captureSuspendedKeepingOverrides(block: suspend (activeBefore: List<ManualOverride>) -> T): T =
                controller.withCaptureSuspendedKeeping(block)
            override suspend fun afterRestore() = Unit
        },
        clock,
        SegmentationParams(),
    )

    @Test
    fun deleteAllKeepsAUserPauseInsideAWindow() = runBlocking<Unit> {
        controller.reconcile(ReconcileSource.ALARM)
        coordinator.begin(SessionTrigger.SCHEDULE)
        controller.pause(PauseKind.UNTIL_NEXT_WINDOW)
        val pause = activeOverrides().single()
        launcher.started.clear()

        deleteAllManager().deleteAll()

        assertThat(activeOverrides()).containsExactly(pause)
        assertThat(launcher.started).isEmpty()
        assertThat(BackupStore(db).snapshot().sessions).isEmpty()
    }

    @Test
    fun deleteAllLeavesNoStrayPauseWhenTheUserHadNone() = runBlocking<Unit> {
        controller.reconcile(ReconcileSource.ALARM)
        coordinator.begin(SessionTrigger.SCHEDULE)
        assertThat(activeOverrides()).isEmpty()
        launcher.started.clear()

        deleteAllManager().deleteAll()

        assertThat(activeOverrides()).isEmpty()
        assertThat(BackupStore(db).snapshot().manualOverrides).isEmpty()
        // Still inside the window and nothing suppresses it, so tracking starts again.
        assertThat(launcher.started).containsExactly(SessionTrigger.SCHEDULE)
    }

    @Test
    fun deleteAllKeepsAnActiveManualStart() = runBlocking<Unit> {
        clock.now = at(19, 20).toEpochMilli() // outside the window
        controller.start(ManualOverrideEndMode.UNTIL_STOPPED)
        coordinator.begin(SessionTrigger.MANUAL)
        val start = activeOverrides().single()
        launcher.started.clear()

        deleteAllManager().deleteAll()

        assertThat(activeOverrides()).containsExactly(start)
        assertThat(launcher.started).containsExactly(SessionTrigger.MANUAL)
    }
}
