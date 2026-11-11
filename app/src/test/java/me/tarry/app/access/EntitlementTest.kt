package me.tarry.app.access

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.core.data.settings.LockSpan
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
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var real: RealEntitlement
    private lateinit var debug: DebugEntitlement

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("s.preferences_pb") }
        settings = SettingsRepository(store)
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

    // --- lock spans, back-dating, bookkeeping failures ---

    @Test
    fun unlockingKeepsTheLockedSpanOnRecordAndClearsTheOpenOne() = runBlocking<Unit> {
        val anchor = clock.now
        settings.setOnboardingCompletedAtMs(anchor)
        clock.now = anchor + 8 * day
        assertThat(real.canTrack()).isFalse()
        val lockStart = settings.currentAccessPrefs().lockedSinceMs
        assertThat(lockStart).isEqualTo(anchor + 7 * day)

        clock.now = anchor + 10 * day
        PurchaseStore(settings).record(PurchaseState.OWNED, clock.now)
        assertThat(real.canTrack()).isTrue()

        val p = settings.currentAccessPrefs()
        assertThat(p.lockedSinceMs).isNull()
        assertThat(p.lockedSpans()).containsExactly(LockSpan(anchor + 7 * day, anchor + 10 * day))
    }

    @Test
    fun onlyTheLastTwentyClosedSpansAreKept() = runBlocking<Unit> {
        val anchor = clock.now
        settings.setOnboardingCompletedAtMs(anchor)
        val store = PurchaseStore(settings)
        clock.now = anchor + 8 * day
        repeat(25) {
            store.record(PurchaseState.NONE, clock.now)
            real.canTrack() // locked (opens a span)
            clock.now += day
            store.record(PurchaseState.OWNED, clock.now)
            real.canTrack() // allowed (closes it)
            clock.now += day
        }
        assertThat(settings.currentAccessPrefs().lockedSpans()).hasSize(20)
    }

    @Test
    fun aRevocationLongAfterTheTrialStartsTheLockAtTheLastTimeTrackingWasAllowed() = runBlocking<Unit> {
        val anchor = clock.now
        settings.setOnboardingCompletedAtMs(anchor)
        val store = PurchaseStore(settings)
        clock.now = anchor + 3 * day
        store.record(PurchaseState.OWNED, clock.now)
        clock.now = anchor + 100 * day
        assertThat(real.canTrack()).isTrue() // still owned months later

        clock.now = anchor + 100 * day + 5 * 60_000L
        store.record(PurchaseState.REVOKED, clock.now)
        assertThat(real.canTrack()).isFalse()

        // Not back at the trial end (day 7), nor at the revocation's own record: when tracking was last allowed.
        assertThat(settings.currentAccessPrefs().lockedSinceMs).isEqualTo(anchor + 100 * day)
    }

    @Test
    fun theDebugTrialEndedSwitchDoesNotStampAnOldAnchorsTrialEnd() = runBlocking<Unit> {
        settings.setOnboardingCompletedAtMs(clock.now - 30 * day)
        val owned = clock.now
        assertThat(debug.canTrack()).isTrue() // default Owned

        clock.now += 60 * 60_000L
        settings.setAccessDebug(DebugAccess.TRIAL_ENDED.name, clock.now)
        assertThat(debug.canTrack()).isFalse()

        assertThat(settings.currentAccessPrefs().lockedSinceMs).isEqualTo(owned)
    }

    /** Reads work, every write fails: the decision must still come out right. */
    private fun failingWrites(): SettingsRepository {
        return SettingsRepository(object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = store.data
            override suspend fun updateData(
                transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
            ): androidx.datastore.preferences.core.Preferences = throw java.io.IOException("disk full")
        })
    }

    @Test
    fun aFailingBookkeepingWriteNeverChangesTheDecision() = runBlocking<Unit> {
        settings.setOnboardingCompletedAtMs(clock.now - 30 * day)
        val broken = RealEntitlement(failingWrites(), clock, scope)
        assertThat(broken.current()).isEqualTo(AccessState.TrialEnded)
        assertThat(broken.canTrack()).isFalse()

        val inTrial = RealEntitlement(failingWrites(), FakeClock(now = clock.now - 28 * day), scope)
        assertThat(inTrial.canTrack()).isTrue()
    }

    @Test
    fun theLockSourceAsksTheEntitlementSoARunRightAfterTheTrialSeesTheLock() = runBlocking<Unit> {
        val anchor = clock.now
        settings.setOnboardingCompletedAtMs(anchor)
        clock.now = anchor + 7 * day + 60_000L
        // Nothing has evaluated access since the trial ended.
        assertThat(settings.currentAccessPrefs().lockedSinceMs).isNull()

        val spans = entitlementLockSource(real, settings).lockedSpans()

        assertThat(spans).containsExactly(LockSpan(anchor + 7 * day, Long.MAX_VALUE))
    }

    @Test
    fun releaseBuildsBindTheRealEntitlementAndNeverBuildTheDebugOne() {
        val chosen = me.tarry.app.di.chooseEntitlement(
            debugBuild = false,
            real = javax.inject.Provider { real },
            debug = javax.inject.Provider { error("the debug entitlement must not be built in release") },
        )
        assertThat(chosen).isSameInstanceAs(real)
        assertThat(
            me.tarry.app.di.chooseEntitlement(true, javax.inject.Provider { real }, javax.inject.Provider { debug }),
        ).isSameInstanceAs(debug)
    }
}
