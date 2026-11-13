package me.tarry.app.access

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.FakeEntitlement
import me.tarry.app.tracking.ReconcileSource
import org.junit.Test

class ForegroundRefreshTest {
    private val clock = FakeClock(now = 1_700_000_000_000L)
    private val client = FakePurchaseClient()

    private suspend fun run(entitlement: FakeEntitlement): Int {
        var reconciles = 0
        refreshPurchaseAndReconcile(entitlement, client) { reconciles++ }
        return reconciles
    }

    @Test
    fun reconcilesWhenTheRefreshUnlocksTracking() = runTest {
        val e = FakeEntitlement(clock, AccessState.TrialEnded)
        client.onRefresh = { e.access = AccessState.Owned }
        assertThat(run(e)).isEqualTo(1)
        assertThat(client.sources).containsExactly(ReconcileSource.UI)
    }

    @Test
    fun reconcilesWhenTheRefreshLocksTrackingAgain() = runTest {
        val e = FakeEntitlement(clock, AccessState.Owned)
        client.onRefresh = { e.access = AccessState.Revoked(1L) }
        assertThat(run(e)).isEqualTo(1)
    }

    @Test
    fun doesNothingWhenNothingChanged() = runTest {
        assertThat(run(FakeEntitlement(clock, AccessState.Owned))).isEqualTo(0)
        assertThat(run(FakeEntitlement(clock, AccessState.TrialEnded))).isEqualTo(0)
    }

    @Test
    fun catchesAChangeWrittenByAnotherRefreshWhileThisOneRan() = runTest {
        // The process-start (system) refresh writes OWNED while the app-opened refresh is in flight.
        val e = FakeEntitlement(clock, AccessState.TrialEnded)
        client.onRefresh = { e.access = AccessState.Owned }
        client.refreshResult = RefreshResult.Ok(found = false)
        assertThat(run(e)).isEqualTo(1)
    }
}
