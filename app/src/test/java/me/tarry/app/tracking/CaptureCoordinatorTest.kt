package me.tarry.app.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CaptureCoordinatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val config = TrackingConfig()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var db: TarryDatabase
    private lateinit var trackingRepo: TrackingRepository
    private lateinit var timelineRepo: TimelineRepository
    private lateinit var store: TrackingStatusStore
    private val location = FakeLocationClient()
    private val activityClient = FakeActivityClient()
    private val geofence = FakeGeofenceClient()
    private val permissions = FakePermissions()
    private val windows = FakeWindowChecker()
    private val clock = FakeClock()
    private val alerts = FakeAlertNotifier()
    private lateinit var health: HealthGapRecorder
    private lateinit var coordinator: CaptureCoordinator

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        timelineRepo = TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao())
        store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        health = HealthGapRecorder(store, timelineRepo, alerts, clock, config, windows)
        coordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, location, activityClient, geofence, permissions, windows,
            health, clock, captureScope,
        )
    }

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
    }

    private fun fix(offsetMs: Long, lat: Double = -33.0, lng: Double = 151.0, accuracy: Float = 10f) =
        Fix(lat, lng, accuracy, clock.now + offsetMs)

    @Test
    fun beginOpensSessionInMovingAndRequestsMovingLocation() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)

        val open = trackingRepo.openSession()!!
        assertThat(open.trigger).isEqualTo(SessionTrigger.SCHEDULE)
        assertThat(open.zoneId).isEqualTo("Australia/Sydney")
        assertThat(open.start).isEqualTo(clock.now)
        val status = store.current()
        assertThat(status.activeSessionId).isEqualTo(open.id)
        assertThat(status.captureMode).isEqualTo(CaptureMode.MOVING)
        assertThat(status.lastHeartbeatMs).isEqualTo(clock.now)
        assertThat(location.requests.single().intervalMs).isEqualTo(config.movingIntervalMs)
        assertThat(activityClient.registered).isTrue()
        assertThat(geofence.stay).isNull()
    }

    @Test
    fun beginTwiceKeepsTheSameSession() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        coordinator.begin(SessionTrigger.MANUAL)

        assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE)).hasSize(1)
        assertThat(location.requests).hasSize(1)
    }

    @Test
    fun manualStartWritesManualStartPingThenFused() = runBlocking {
        coordinator.begin(SessionTrigger.MANUAL)
        coordinator.onFixes(listOf(fix(1_000, lat = -33.000), fix(40_000, lat = -33.001)))

        val pings = trackingRepo.pingsForSession(trackingRepo.openSession()!!.id)
        assertThat(pings.map { it.source }).containsExactly(PingSource.MANUAL_START, PingSource.FUSED).inOrder()
    }

    @Test
    fun scheduledStartPingsAreFusedAndCarryZoneAndActivity() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        coordinator.onActivity(ActivityType.WALKING)
        coordinator.onFixes(listOf(fix(1_000)))

        val ping = trackingRepo.pingsForSession(trackingRepo.openSession()!!.id).single()
        assertThat(ping.source).isEqualTo(PingSource.FUSED)
        assertThat(ping.zoneId).isEqualTo("Australia/Sydney")
        assertThat(ping.activity).isEqualTo(ActivityType.WALKING)
    }

    @Test
    fun filteredFixIsNotSavedButStillCountsAsHeartbeat() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        val poor = fix(5_000, accuracy = config.maxAccuracyM + 1)
        coordinator.onFixes(listOf(poor))

        assertThat(trackingRepo.pingsForSession(trackingRepo.openSession()!!.id)).isEmpty()
        assertThat(store.current().lastHeartbeatMs).isEqualTo(poor.timeMs)
    }

    @Test
    fun stillThenGeofenceExitMarksNextPingGeofenceExit() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        coordinator.onFixes(listOf(fix(1_000)))
        coordinator.onActivity(ActivityType.STILL)

        assertThat(store.current().captureMode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(geofence.stay).isEqualTo(FakeGeofenceClient.Stay(-33.0, 151.0, config.stayGeofenceRadiusM))
        assertThat(location.requests.last().intervalMs).isEqualTo(config.stationaryFixIntervalMs)

        coordinator.onGeofenceExit()
        assertThat(store.current().captureMode).isEqualTo(CaptureMode.MOVING)
        assertThat(geofence.stay).isNull()
        coordinator.onFixes(listOf(fix(60_000, lat = -33.01)))

        val pings = trackingRepo.pingsForSession(trackingRepo.openSession()!!.id)
        assertThat(pings.map { it.source }).containsExactly(PingSource.FUSED, PingSource.GEOFENCE_EXIT).inOrder()
    }

    @Test
    fun locationUpdatesFailingWithGenericExceptionRecordsOutageGapAndAlert() = runBlocking {
        val failing = object : LocationClient {
            override fun updates(params: LocationRequestParams) =
                kotlinx.coroutines.flow.flow<List<Fix>> { throw IllegalStateException("boom") }
        }
        val c = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, failing, activityClient, geofence, permissions, windows,
            health, clock, captureScope,
        )
        c.begin(SessionTrigger.SCHEDULE)
        // The outage is reported from the capture scope, so wait for it.
        kotlinx.coroutines.withTimeout(5_000) {
            while (alerts.posted.isEmpty()) kotlinx.coroutines.delay(10)
        }

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.SERVICE_NOT_RUNNING)
        assertThat(alerts.posted).containsExactly(AttentionCause.SERVICE_NOT_RUNNING)
        val gap = timelineRepo.gapsInRange(0, Long.MAX_VALUE).single()
        assertThat(gap.cause).isEqualTo(GapCause.SERVICE_KILLED)
        assertThat(store.current().openHealthGapId).isEqualTo(gap.id)
    }

    @Test
    fun endClosesSessionAndTearsDown() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        clock.now += 60_000
        coordinator.end(SessionEndReason.USER_STOP)

        assertThat(trackingRepo.openSession()).isNull()
        val closed = trackingRepo.sessionsInRange(0, Long.MAX_VALUE).single()
        assertThat(closed.endReason).isEqualTo(SessionEndReason.USER_STOP)
        assertThat(closed.end).isEqualTo(clock.now)
        assertThat(store.current().activeSessionId).isNull()
        assertThat(store.current().lastHeartbeatMs).isNull()
        assertThat(geofence.stay).isNull()
        assertThat(activityClient.registered).isFalse()
        assertThat(location.activeCollectors).isEqualTo(0)
        assertThat(coordinator.isActive).isFalse()

        // Fixes after the end are ignored.
        coordinator.onFixes(listOf(fix(0)))
        assertThat(trackingRepo.pingsInRange(0, Long.MAX_VALUE)).isEmpty()
    }

    private suspend fun seedOpenSession(withPingAt: Long?): Long {
        val id = trackingRepo.saveSession(
            TrackingSession(start = clock.now - 3_600_000, zoneId = "Australia/Sydney", trigger = SessionTrigger.SCHEDULE),
        )
        if (withPingAt != null) {
            trackingRepo.savePing(
                LocationPing(
                    timestamp = withPingAt, zoneId = "Australia/Sydney", lat = -33.0, lng = 151.0, accuracyM = 10f,
                    source = PingSource.FUSED, sessionId = id,
                ),
            )
        }
        store.setActiveSessionId(id)
        return id
    }

    @Test
    fun recoveryClosesAtLastPingWithKilledAndWritesGap() = runBlocking {
        val lastPing = clock.now - 1_800_000
        val id = seedOpenSession(lastPing)

        coordinator.recoverCrashedSession()

        val closed = trackingRepo.session(id)!!
        assertThat(closed.end).isEqualTo(lastPing)
        assertThat(closed.endReason).isEqualTo(SessionEndReason.KILLED)
        val gap = timelineRepo.gapsInRange(0, Long.MAX_VALUE).single()
        assertThat(gap.cause).isEqualTo(GapCause.SERVICE_KILLED)
        assertThat(gap.start).isEqualTo(lastPing)
        assertThat(gap.end).isEqualTo(clock.now)
        assertThat(gap.zoneId).isEqualTo("Australia/Sydney")
        assertThat(gap.sessionId).isEqualTo(id)
        assertThat(store.current().activeSessionId).isNull()
        assertThat(geofence.removeCalls).isGreaterThan(0)
    }

    @Test
    fun recoveryOutsideWindowClosesSessionWithoutGap() = runBlocking {
        windows.inside = false
        val id = seedOpenSession(withPingAt = null)

        coordinator.recoverCrashedSession()

        val closed = trackingRepo.session(id)!!
        assertThat(closed.endReason).isEqualTo(SessionEndReason.KILLED)
        assertThat(closed.end).isEqualTo(closed.start)
        assertThat(timelineRepo.gapsInRange(0, Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun beginRecoversStaleSessionBeforeOpeningNewOne() = runBlocking {
        val staleId = seedOpenSession(clock.now - 1_800_000)

        coordinator.begin(SessionTrigger.SCHEDULE)

        assertThat(trackingRepo.session(staleId)!!.endReason).isEqualTo(SessionEndReason.KILLED)
        assertThat(trackingRepo.openSession()!!.id).isNotEqualTo(staleId)
        assertThat(timelineRepo.gapsInRange(0, Long.MAX_VALUE)).hasSize(1)
    }

    @Test
    fun recoveryDoesNothingWhileSessionIsLive() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        coordinator.recoverCrashedSession()

        assertThat(trackingRepo.openSession()).isNotNull()
        assertThat(timelineRepo.gapsInRange(0, Long.MAX_VALUE)).isEmpty()
    }

    @Test
    fun noActivityRecognitionSubDisplacementFixesStillDriveHeartbeatAndStationaryRule() = runBlocking {
        // Fused now delivers every fix (no request-level distance filter), so a stationary phone is still heard.
        val noAr = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, location, FakeActivityClient(available = false), geofence,
            permissions, windows, health, clock, captureScope,
        )
        noAr.begin(SessionTrigger.SCHEDULE)
        var offset = 0L
        var lastTime = 0L
        while (offset <= config.stationaryEntryDurationMs + config.movingIntervalMs) {
            // About half a metre of drift per fix.
            noAr.onFixes(listOf(fix(offset, lat = -33.0 + offset / 20_000 * 0.000005)))
            lastTime = clock.now + offset
            offset += config.movingIntervalMs
        }

        assertThat(store.current().lastHeartbeatMs).isEqualTo(lastTime)
        assertThat(store.current().captureMode).isEqualTo(CaptureMode.STATIONARY)
        assertThat(trackingRepo.pingsForSession(trackingRepo.openSession()!!.id)).hasSize(1)
        assertThat(location.requests.last().intervalMs).isEqualTo(config.stationaryFixIntervalMs)
    }

    @Test
    fun movingFixBeyondTheDisplacementIsSavedFromTheLastSavedPing() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        // 0.0001 deg of latitude is about 11 m; 0.0003 is about 33 m from the first.
        coordinator.onFixes(listOf(fix(0), fix(20_000, lat = -33.0001), fix(40_000, lat = -33.0003)))

        val pings = trackingRepo.pingsForSession(trackingRepo.openSession()!!.id)
        assertThat(pings.map { it.timestamp }).containsExactly(clock.now, clock.now + 40_000).inOrder()
    }

    @Test
    fun beginWithActivityRecognitionFailingFallsBackToDistanceGating() = runBlocking {
        val throwing = object : ActivityTransitionClient {
            override suspend fun register(): Boolean = throw IllegalStateException("task cancelled")
            override suspend fun unregister() = Unit
        }
        val c = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, location, throwing, geofence, permissions, windows, health, clock,
            captureScope,
        )
        c.begin(SessionTrigger.SCHEDULE)

        assertThat(c.isActive).isTrue()
        assertThat(location.requests).hasSize(1)
    }

    @Test
    fun beginFailingAfterTheSessionOpenedRollsBackInsteadOfLeavingASessionWithoutAMachine() = runBlocking {
        var removeAttempts = 0
        val failingGeofence = object : GeofenceClient {
            override suspend fun addStayGeofence(lat: Double, lng: Double, radiusM: Float) = Unit
            override suspend fun remove() {
                removeAttempts++
                throw IllegalStateException("geofence api down")
            }
        }
        val c = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, location, activityClient, failingGeofence, permissions, windows,
            health, clock, captureScope,
        )

        val thrown = runCatching { c.begin(SessionTrigger.SCHEDULE) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(c.isActive).isFalse()
        assertThat(trackingRepo.openSession()).isNull()
        assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE).single().endReason).isEqualTo(SessionEndReason.KILLED)
        // Once from the start step that failed, once from the rollback.
        assertThat(removeAttempts).isEqualTo(2)
        assertThat(activityClient.unregisterCalls).isEqualTo(1)
        assertThat(store.current().activeSessionId).isNull()
    }

    @Test
    fun locationUpdatesDeadAfterAGenericFailureAreRestartedByEnsureLocationUpdates() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val flaky = object : LocationClient {
            override fun updates(params: LocationRequestParams) =
                if (calls.getAndIncrement() == 0) {
                    kotlinx.coroutines.flow.flow<List<Fix>> { throw IllegalStateException("boom") }
                } else {
                    location.updates(params)
                }
        }
        val c = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, flaky, activityClient, geofence, permissions, windows,
            health, clock, captureScope,
        )
        c.begin(SessionTrigger.SCHEDULE)
        kotlinx.coroutines.withTimeout(5_000) {
            while (calls.get() < 2) {
                c.ensureLocationUpdates()
                kotlinx.coroutines.delay(10)
            }
        }

        assertThat(location.requests).hasSize(1)
        assertThat(location.requests.single().intervalMs).isEqualTo(config.movingIntervalMs)
        // A live request is left alone.
        c.ensureLocationUpdates()
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun ensureLocationUpdatesWithoutASessionDoesNothing() = runBlocking {
        coordinator.ensureLocationUpdates()

        assertThat(location.requests).isEmpty()
    }

    @Test
    fun permissionLostMidSessionGoesThroughTheFailureListenerWhenSet() = runBlocking {
        val seen = java.util.concurrent.CopyOnWriteArrayList<HealthIssue>()
        val denied = object : LocationClient {
            override fun updates(params: LocationRequestParams) =
                kotlinx.coroutines.flow.flow<List<Fix>> { throw SecurityException("revoked") }
        }
        val c = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, denied, activityClient, geofence, permissions, windows,
            health, clock, captureScope,
        )
        c.failureListener = { _, issue -> seen += issue }
        c.begin(SessionTrigger.SCHEDULE)
        kotlinx.coroutines.withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }

        assertThat(seen.single().gapCause).isEqualTo(GapCause.PERMISSION_LOST)
        // Ending the session and stopping the service is the listener's job (the controller).
        assertThat(c.isActive).isTrue()
    }

    @Test
    fun recoveryClipsTheGapToTheActiveIntervals() = runBlocking {
        val lastPing = clock.now - 1_800_000
        windows.intervals = listOf(
            WindowBounds(java.time.Instant.ofEpochMilli(lastPing), java.time.Instant.ofEpochMilli(lastPing + 600_000)),
            WindowBounds(java.time.Instant.ofEpochMilli(clock.now - 300_000), java.time.Instant.ofEpochMilli(clock.now)),
        )
        seedOpenSession(lastPing)

        coordinator.recoverCrashedSession()

        val gaps = timelineRepo.gapsInRange(0, Long.MAX_VALUE).sortedBy { it.start }
        assertThat(gaps.map { it.start to it.end }).containsExactly(
            lastPing to lastPing + 600_000, clock.now - 300_000 to clock.now,
        ).inOrder()
    }
}
