package me.tarry.app.tracking

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.model.ManualOverrideType
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
import org.robolectric.shadows.ShadowBroadcastPendingResult
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Receiver to controller dispatch. The Hilt-injected fields are public lateinit, so the receivers are built
 * directly and handed a real [TrackingController] wired to fakes and an in-memory DB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackingReceiversTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val config = TrackingConfig()
    private val sydney = ZoneId.of("Australia/Sydney")
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + kotlinx.coroutines.CoroutineExceptionHandler { _, t -> t.printStackTrace(System.out) })

    private lateinit var db: TarryDatabase
    private lateinit var trackingRepo: TrackingRepository
    private lateinit var controller: TrackingController
    private val launcher = FakeLauncher()
    private val alarms = FakeAlarmScheduler()
    private val clock = FakeClock(now = mondayAt(10).toEpochMilli(), zone = sydney)

    private fun mondayAt(hour: Int, day: Int = 19): Instant =
        ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, sydney).toInstant()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        val scheduleRepo = ScheduleRepository(db.scheduleDao())
        val store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        val inputs = ScheduleInputsProvider(scheduleRepo, trackingRepo, store, config, clock)
        val calculator = EffectiveScheduleCalculator(config)
        val timelineRepo = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        val health = HealthGapRecorder(store, timelineRepo, FakeAlertNotifier(), clock, config)
        val coordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo,
            store, FakeLocationClient(), FakeActivityClient(), FakeGeofenceClient(), FakePermissions(),
            EffectiveScheduleWindowChecker(inputs, calculator, clock), health, clock, captureScope,
        )
        controller = TrackingController(
            coordinator, launcher, store, trackingRepo, inputs, calculator, alarms, FakeSegmentationTrigger(), health, clock, config,
        )
    }

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
    }

    /** Delivers [intent] as the system would (with a pending result for goAsync) and waits for the async work. */
    private fun deliver(receiver: BroadcastReceiver, intent: Intent) {
        // Hilt_* base classes inject on first receive; mark them injected so the hand-set fakes stay in place.
        generateSequence<Class<*>>(receiver.javaClass) { it.superclass }
            .mapNotNull { c -> runCatching { c.getDeclaredField("injected") }.getOrNull() }
            .forEach { it.isAccessible = true; it.setBoolean(receiver, true) }
        val setPendingResult = BroadcastReceiver::class.java.getDeclaredMethod(
            "setPendingResult", BroadcastReceiver.PendingResult::class.java,
        )
        setPendingResult.isAccessible = true
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType,
        )
        create.isAccessible = true
        setPendingResult.invoke(receiver, create.invoke(null, 0, null, null, false))
        receiver.onReceive(ApplicationProvider.getApplicationContext(), intent)
        runBlocking { captureScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
    }

    private fun rearm() = ScheduleRearmReceiver().also {
        it.controller = controller
        it.scope = captureScope
    }

    private fun actionReceiver() = TrackingActionReceiver().also {
        it.controller = controller
        it.scope = captureScope
    }

    private suspend fun overrides() = trackingRepo.overridesInRange(0, Long.MAX_VALUE)

    @Test
    fun everyRearmActionReconcilesAsSystemAndReplacesTheAlarm() {
        val actions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        for (action in actions) {
            alarms.armed.clear()

            deliver(rearm(), Intent(action))

            // SYSTEM source inside the window defers the start to a short alarm instead of starting the service.
            assertThat(alarms.armed).isNotEmpty()
            assertThat(alarms.armed.last()).isEqualTo(Instant.ofEpochMilli(clock.now + config.deferredStartDelayMs))
            assertThat(launcher.started).isEmpty()
        }
    }

    @Test
    fun rearmOutsideTheWindowArmsTheNextWindowStart() {
        clock.now = mondayAt(18).toEpochMilli()

        deliver(rearm(), Intent(Intent.ACTION_TIMEZONE_CHANGED))

        assertThat(alarms.armed.last()).isEqualTo(mondayAt(7, day = 20))
    }

    @Test
    fun rearmIgnoresUnrelatedActions() {
        deliver(rearm(), Intent(Intent.ACTION_BATTERY_LOW))
        deliver(rearm(), Intent())

        assertThat(alarms.armed).isEmpty()
        assertThat(launcher.started).isEmpty()
    }

    @Test
    fun scheduleAlarmReceiverReconcilesAsAlarmAndStartsDirectly() {
        val receiver = ScheduleAlarmReceiver().also {
            it.controller = controller
            it.scope = captureScope
        }

        deliver(receiver, Intent())

        assertThat(launcher.started).containsExactly(SessionTrigger.SCHEDULE)
        assertThat(alarms.armed.last()).isEqualTo(mondayAt(17))
    }

    @Test
    fun pauseActionPausesForAnHour() {
        deliver(actionReceiver(), Intent(TrackingIntents.ACTION_PAUSE_1H))

        val row = runBlocking { overrides() }.single()
        assertThat(row.type).isEqualTo(ManualOverrideType.PAUSE)
        assertThat(row.end).isEqualTo(clock.now + 60 * 60_000L)
        assertThat(alarms.armed.last()).isEqualTo(Instant.ofEpochMilli(clock.now + 60 * 60_000L))
    }

    @Test
    fun stopActionStopsTheServiceAndSuppressesTheWindow() {
        deliver(actionReceiver(), Intent(TrackingIntents.ACTION_STOP))

        assertThat(launcher.stopCalls).isAtLeast(1)
        assertThat(runBlocking { overrides() }.single().type).isEqualTo(ManualOverrideType.PAUSE)
    }

    @Test
    fun unknownActionOnTheActionReceiverDoesNothing() {
        deliver(actionReceiver(), Intent("me.tarry.app.action.NOPE"))

        assertThat(runBlocking { overrides() }).isEmpty()
        assertThat(launcher.stopCalls).isEqualTo(0)
    }
}
