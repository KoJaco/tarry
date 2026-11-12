package me.tarry.app.access

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import me.tarry.app.tracking.ReconcileSource

/** A [PurchaseClient] a test drives: the offer, what refresh does, and what launch returns. */
class FakePurchaseClient(
    initial: OfferState = OfferState.Available("A$29.00"),
    var launchResult: LaunchResult = LaunchResult.Started,
) : PurchaseClient {
    override val offer = MutableStateFlow(initial)
    var refreshes = 0
    var launches = 0

    /** Runs inside every refresh, e.g. to flip the entitlement the way a found purchase would. */
    var onRefresh: suspend () -> Unit = {}

    override suspend fun refresh(source: ReconcileSource) {
        refreshes++
        onRefresh()
    }

    override suspend fun launch(activity: Activity): LaunchResult {
        launches++
        return launchResult
    }
}
