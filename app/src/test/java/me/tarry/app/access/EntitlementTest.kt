package me.tarry.app.access

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.core.data.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EntitlementTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clock = FakeClock(now = 1_700_000_000_000L)
    private val day = TrialClock.MS_PER_DAY
    private lateinit var settings: SettingsRepository
    private lateinit var real: RealEntitlement
    private lateinit var debug: DebugEntitlement

    @Before
    fun setUp() {
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("s.preferences_pb") })
        real = RealEntitlement(settings, clock, scope)
        debug = DebugEntitlement(real, settings, clock, scope)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun realWithNoAnchorIsNotStartedAndCanTrack() = runBlocking {
        assertThat(real.current()).isEqualTo(AccessState.NotStarted)
        assertThat(real.canTrack()).isTrue()
    }

    @Test
    fun realRunsTheTrialFromTheOnboardingStamp() = runBlocking {
        settings.setOnboardingCompletedAtMs(clock.now)
        val anchor = clock.now
        assertThat(real.current()).isEqualTo(AccessState.Trial(anchor + 7 * day))
        clock.now = anchor + 7 * day - 1
        assertThat(real.canTrack()).isTrue()
        clock.now = anchor + 7 * day
        assertThat(real.current()).isEqualTo(AccessState.TrialEnded)
        assertThat(real.canTrack()).isFalse()
        // The lock is stamped at the trial end.
        assertThat(settings.currentAccessPrefs().lockedSinceMs).isEqualTo(anchor + 7 * day)
    }

    @Test
    fun aClockSetBackDoesNotReopenTheTrial() = runBlocking {
        val anchor = clock.now
        settings.setOnboardingCompletedAtMs(anchor)
        clock.now = anchor + 8 * day
        assertThat(real.canTrack()).isFalse()
        clock.now = anchor + day
        assertThat(real.canTrack()).isFalse()
    }

    @Test
    fun ownedClearsTheLockAndOverridesAnEndedTrial() = runBlocking {
        settings.setOnboardingCompletedAtMs(clock.now - 30 * day)
        assertThat(real.canTrack()).isFalse()
        assertThat(settings.currentAccessPrefs().lockedSinceMs).isNotNull()
        PurchaseStore(settings).record(PurchaseState.OWNED, clock.now)
        assertThat(real.current()).isEqualTo(AccessState.Owned)
        assertThat(settings.currentAccessPrefs().lockedSinceMs).isNull()
    }

    @Test
    fun pendingAndRevokedInsideTheTrialKeepTheTrial() = runBlocking {
        val anchor = clock.now
        settings.setOnboardingCompletedAtMs(anchor)
        val store = PurchaseStore(settings)
        store.record(PurchaseState.PENDING, clock.now)
        assertThat(real.current()).isEqualTo(AccessState.Pending(anchor + 7 * day))
        assertThat(real.canTrack()).isTrue()
        store.record(PurchaseState.REVOKED, clock.now)
        assertThat(real.current()).isEqualTo(AccessState.Revoked(anchor + 7 * day))
        assertThat(real.canTrack()).isTrue()
        clock.now = anchor + 8 * day
        assertThat(real.canTrack()).isFalse()
    }

    @Test
    fun debugDefaultsToOwned() = runBlocking {
        settings.setOnboardingCompletedAtMs(clock.now - 30 * day)
        assertThat(debug.current()).isEqualTo(AccessState.Owned)
        assertThat(debug.canTrack()).isTrue()
    }

    @Test
    fun debugOverrideValues() = runBlocking {
        val end6 = clock.now + 6 * day
        settings.setAccessDebug(DebugAccess.TRIAL_6_DAYS.name, end6)
        assertThat(debug.current()).isEqualTo(AccessState.Trial(end6))
        val end1 = clock.now + day
        settings.setAccessDebug(DebugAccess.TRIAL_1_DAY.name, end1)
        assertThat(debug.current()).isEqualTo(AccessState.Trial(end1))
        settings.setAccessDebug(DebugAccess.TRIAL_ENDED.name, clock.now)
        assertThat(debug.current()).isEqualTo(AccessState.TrialEnded)
        assertThat(debug.canTrack()).isFalse()
        settings.setAccessDebug(DebugAccess.PENDING.name, clock.now)
        assertThat(debug.current()).isEqualTo(AccessState.Pending(clock.now))
        assertThat(debug.canTrack()).isFalse()
        settings.setAccessDebug(DebugAccess.REVOKED.name, clock.now)
        assertThat(debug.current()).isEqualTo(AccessState.Revoked(clock.now))
        settings.setAccessDebug(DebugAccess.OWNED.name, null)
        assertThat(debug.current()).isEqualTo(AccessState.Owned)
    }

    @Test
    fun debugRealDelegatesToTheRealEntitlement() = runBlocking {
        settings.setAccessDebug(DebugAccess.REAL.name, null)
        assertThat(debug.current()).isEqualTo(AccessState.NotStarted)
        settings.setOnboardingCompletedAtMs(clock.now - 30 * day)
        assertThat(debug.current()).isEqualTo(AccessState.TrialEnded)
    }
}
