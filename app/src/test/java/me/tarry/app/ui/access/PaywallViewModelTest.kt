package me.tarry.app.ui.access

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.tarry.app.access.AccessState
import me.tarry.app.access.FakePurchaseClient
import me.tarry.app.access.LaunchResult
import me.tarry.app.access.OfferState
import me.tarry.app.access.RefreshResult
import me.tarry.app.access.TrialClock
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeEntitlement
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PaywallViewModelTest {
    private val clock = FakeClock(now = 1_700_000_000_000L)
    private val entitlement = FakeEntitlement(clock, AccessState.Trial(clock.now + 3 * TrialClock.MS_PER_DAY - 1))
    private val client = FakePurchaseClient()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): PaywallViewModel = PaywallViewModel(entitlement, client, clock).also { it.ui.launchIn(kotlinx.coroutines.CoroutineScope(Dispatchers.Main)) }

    private fun setAccess(s: AccessState) {
        entitlement.access = s
        entitlement.state.value = s
    }

    @Test
    fun aTrialShowsTheDaysLeftAndThePrice() {
        val ui = vm().ui.value
        assertThat(ui.access).isInstanceOf(AccessState.Trial::class.java)
        assertThat(ui.daysLeft).isEqualTo(3)
        assertThat(ui.offer).isEqualTo(OfferState.Available("A$29.00"))
    }

    @Test
    fun openingTheScreenRefreshesOnce() {
        vm()
        assertThat(client.refreshes).isEqualTo(1)
    }

    @Test
    fun eachAccessStateReachesTheUiModel() {
        val v = vm()
        for (s in listOf(AccessState.TrialEnded, AccessState.Owned, AccessState.Pending(null), AccessState.Revoked(null))) {
            setAccess(s)
            assertThat(v.ui.value.access).isEqualTo(s)
            assertThat(v.ui.value.daysLeft).isNull()
        }
    }

    @Test
    fun theOfferStatesFollowTheClient() {
        val v = vm()
        client.offer.value = OfferState.Loading
        assertThat(v.ui.value.offer).isEqualTo(OfferState.Loading)
        client.offer.value = OfferState.Unavailable
        assertThat(v.ui.value.offer).isEqualTo(OfferState.Unavailable)
    }

    @Test
    fun restoreFoundWhenTheRefreshMakesTheAccountOwner() {
        val v = vm()
        client.onRefresh = { setAccess(AccessState.Owned) }
        v.restore()
        assertThat(v.ui.value.restore).isEqualTo(RestoreResult.Found)
        assertThat(client.refreshes).isEqualTo(2)
    }

    @Test
    fun restoreNotFoundWhenNothingChanges() {
        setAccess(AccessState.TrialEnded)
        val v = vm()
        v.restore()
        assertThat(v.ui.value.restore).isEqualTo(RestoreResult.NotFound)
    }

    @Test
    fun launchAlreadyOwnedLeavesTheRefreshToTheClientAndShowsNoError() {
        client.launchResult = LaunchResult.AlreadyOwned
        val v = vm()
        v.unlock(Robolectric.buildActivity(Activity::class.java).get())
        assertThat(client.launches).isEqualTo(1)
        assertThat(v.ui.value.launchFailed).isFalse()
    }

    @Test
    fun aFailedLaunchShowsTheError() {
        client.launchResult = LaunchResult.Error
        val v = vm()
        v.unlock(Robolectric.buildActivity(Activity::class.java).get())
        assertThat(v.ui.value.launchFailed).isTrue()
        v.retry()
        assertThat(v.ui.value.launchFailed).isFalse()
    }

    @Test
    fun restoreOfflineSaysUnavailableNotNoPurchase() {
        setAccess(AccessState.TrialEnded)
        val v = vm()
        client.refreshResult = RefreshResult.Unavailable
        v.restore()
        assertThat(v.ui.value.restore).isEqualTo(RestoreResult.Unavailable)
    }

    @Test
    fun restoreWithAPendingPurchaseShowsNeitherFoundNorNotFound() {
        setAccess(AccessState.TrialEnded)
        val v = vm()
        client.refreshResult = RefreshResult.Ok(found = true)
        client.onRefresh = { setAccess(AccessState.Pending(null)) }
        v.restore()
        assertThat(v.ui.value.restore).isNull()
    }

    @Test
    fun aSecondTapWhileLaunchingIsIgnored() {
        val gate = CompletableDeferred<Unit>()
        client.onLaunch = { gate.await() }
        val v = vm()
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        v.unlock(activity)
        assertThat(v.ui.value.launching).isTrue()
        v.unlock(activity)
        assertThat(client.launches).isEqualTo(1)
        gate.complete(Unit)
        assertThat(v.ui.value.launching).isFalse()
        v.unlock(activity)
        assertThat(client.launches).isEqualTo(2)
    }

    @Test
    fun aListenerFailureAfterAStartedLaunchShowsTheError() {
        val v = vm()
        v.unlock(Robolectric.buildActivity(Activity::class.java).get())
        assertThat(v.ui.value.launchFailed).isFalse()
        client.failures.tryEmit(Unit)
        assertThat(v.ui.value.launchFailed).isTrue()
    }

    @Test
    fun aStaleErrorGoesWhenAccessBecomesOwnedOrPending() {
        client.launchResult = LaunchResult.Error
        val v = vm()
        v.unlock(Robolectric.buildActivity(Activity::class.java).get())
        assertThat(v.ui.value.launchFailed).isTrue()
        setAccess(AccessState.Pending(null))
        assertThat(v.ui.value.launchFailed).isFalse()
        setAccess(AccessState.Owned)
        assertThat(v.ui.value.launchFailed).isFalse()
    }

    @Test
    fun tryAgainShowsLoadingUntilTheRefreshReturns() {
        client.offer.value = OfferState.Unavailable
        val v = vm()
        val gate = CompletableDeferred<Unit>()
        client.onRefresh = { gate.await() }
        v.retry()
        assertThat(v.ui.value.offer).isEqualTo(OfferState.Loading)
        gate.complete(Unit)
        assertThat(v.ui.value.offer).isEqualTo(OfferState.Unavailable)
    }

    @Test
    fun tryAgainRefreshes() {
        client.offer.value = OfferState.Unavailable
        val v = vm()
        val before = client.refreshes
        v.retry()
        assertThat(client.refreshes).isEqualTo(before + 1)
    }
}
