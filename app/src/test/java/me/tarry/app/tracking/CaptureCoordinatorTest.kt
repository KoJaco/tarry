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
@Config(sdk = [35])
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
        coordinator = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, location, activityClient, geofence, permissions, windows,
            clock, captureScope,
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
    fun noActivityRecognitionFallsBackToDistanceGating() = runBlocking {
        val noAr = CaptureCoordinator(
            config, trackingRepo, timelineRepo, store, location, FakeActivityClient(available = false), geofence,
            permissions, windows, clock, captureScope,
        )
        noAr.begin(SessionTrigger.SCHEDULE)
        noAr.onFixes(listOf(fix(0)))
        noAr.onFixes(listOf(fix(config.stationaryEntryDurationMs + 1_000, lat = -33.00001)))

        assertThat(store.current().captureMode).isEqualTo(CaptureMode.STATIONARY)
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
}
