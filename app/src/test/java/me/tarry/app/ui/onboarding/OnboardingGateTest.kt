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
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.app.ui.permissions.FakePermissionChecker
import me.tarry.app.ui.permissions.PermissionSnapshot
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.SessionTrigger
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnboardingGateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: TarryDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var trackingRepo: TrackingRepository
    private val permissions = FakePermissionChecker()
    private val clock = FakeClock(now = 1_700_000_000_000L)
    private lateinit var gate: OnboardingGate

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries().build()
        trackingRepo = TrackingRepository(
            db.trackingSessionDao(), db.locationPingDao(), db.manualOverrideDao(), db.segmentationCheckpointDao(),
        )
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") })
        gate = OnboardingGate(settings, trackingRepo, permissions, clock)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun aFreshInstallNeedsOnboardingAndIsNotStamped() = runBlocking {
        assertThat(gate.needsOnboarding()).isTrue()
        assertThat(settings.onboardingCompletedAtMs()).isNull()
    }

    @Test
    fun fineLocationAlreadyGrantedSkipsSilentlyAndStampsNow() = runBlocking {
        permissions.snapshot = PermissionSnapshot(fineLocation = true)
        assertThat(gate.needsOnboarding()).isFalse()
        assertThat(settings.onboardingCompletedAtMs()).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun anExistingSessionSkipsSilentlyAndStampsNow() = runBlocking {
        trackingRepo.saveSession(TrackingSession(start = 1, zoneId = "UTC", trigger = SessionTrigger.MANUAL))
        assertThat(gate.needsOnboarding()).isFalse()
        assertThat(settings.onboardingCompletedAtMs()).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun aFreshInstallStampsTheStartAndAHalfFinishedOnboardingResumes() = runBlocking {
        assertThat(settings.onboardingStartedAtMs()).isNull()
        assertThat(gate.needsOnboarding()).isTrue()
        assertThat(settings.onboardingStartedAtMs()).isEqualTo(1_700_000_000_000L)

        // Location granted on page 2, then the app is killed: still a first run, not an existing install.
        permissions.snapshot = PermissionSnapshot(fineLocation = true)
        clock.now += 60_000
        assertThat(gate.needsOnboarding()).isTrue()
        assertThat(settings.onboardingCompletedAtMs()).isNull()
        assertThat(settings.onboardingStartedAtMs()).isEqualTo(1_700_000_000_000L)

        gate.markCompleted()
        assertThat(gate.needsOnboarding()).isFalse()
    }

    @Test
    fun theSilentExistingInstallSkipLeavesTheStartUnset() = runBlocking {
        permissions.snapshot = PermissionSnapshot(fineLocation = true)
        assertThat(gate.needsOnboarding()).isFalse()
        assertThat(settings.onboardingStartedAtMs()).isNull()
    }

    @Test
    fun theFlagWinsEvenWhenTheOtherSignalsAreGone() = runBlocking {
        settings.setOnboardingCompletedAtMs(5L)
        clock.now = 9_000_000_000_000L
        assertThat(gate.needsOnboarding()).isFalse()
        assertThat(settings.onboardingCompletedAtMs()).isEqualTo(5L)
    }

    @Test
    fun markCompletedStampsOnceAndKeepsTheFirstTime() = runBlocking {
        gate.markCompleted()
        clock.now += 1_000
        gate.markCompleted()
        assertThat(settings.onboardingCompletedAtMs()).isEqualTo(1_700_000_000_000L)
        assertThat(gate.needsOnboarding()).isFalse()
    }
}
