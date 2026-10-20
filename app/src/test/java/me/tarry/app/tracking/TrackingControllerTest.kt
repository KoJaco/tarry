package me.tarry.app.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.model.ActivityType
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
class TrackingControllerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var db: TarryDatabase
    private lateinit var trackingRepo: TrackingRepository
    private lateinit var store: TrackingStatusStore
    private val permissions = FakePermissions()
    private val launcher = FakeLauncher()
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var controller: TrackingController

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        store = TrackingStatusStore(createTrackingStatusDataStore(ioScope) { folder.root.resolve("status.preferences_pb") })
        coordinator = CaptureCoordinator(
            TrackingConfig(), trackingRepo, TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao()),
            store, FakeLocationClient(), FakeActivityClient(), FakeGeofenceClient(), permissions, FakeWindowChecker(),
            FakeClock(), captureScope,
        )
        controller = TrackingController(coordinator, launcher, store)
    }

    @After
    fun tearDown() {
        captureScope.cancel()
        ioScope.cancel()
        db.close()
    }

    @Test
    fun startCaptureLaunchesServiceWithTrigger() = runBlocking {
        controller.startCapture(SessionTrigger.MANUAL)

        assertThat(launcher.started).containsExactly(SessionTrigger.MANUAL)
        assertThat(store.current().attentionCause).isNull()
    }

    @Test
    fun deniedBackgroundStartRecordsExactAlarmDenied() = runBlocking {
        launcher.denyStart = true
        controller.startCapture(SessionTrigger.SCHEDULE)

        assertThat(store.current().attentionCause).isEqualTo(AttentionCause.EXACT_ALARM_DENIED)
        assertThat(launcher.stopCalls).isEqualTo(1)
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
    fun stopCaptureClosesSessionAndStopsService() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        controller.stopCapture(SessionEndReason.USER_STOP)

        assertThat(trackingRepo.openSession()).isNull()
        assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE).single().endReason)
            .isEqualTo(SessionEndReason.USER_STOP)
        assertThat(launcher.stopCalls).isEqualTo(1)
    }

    @Test
    fun pause1hCurrentlyEndsSessionAsUserPause() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        controller.pause1h()

        assertThat(trackingRepo.sessionsInRange(0, Long.MAX_VALUE).single().endReason)
            .isEqualTo(SessionEndReason.USER_PAUSE)
    }

    @Test
    fun activityEventsReachTheMachineAndGetSavedOnPings() = runBlocking {
        coordinator.begin(SessionTrigger.SCHEDULE)
        controller.onActivity(ActivityType.IN_VEHICLE)
        coordinator.onFixes(listOf(Fix(-33.0, 151.0, 10f, 1_000_000_001L)))

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
}
