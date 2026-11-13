package me.tarry.app.access

import android.app.Activity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import me.tarry.app.tracking.ReconcileSource

/** A [PurchaseClient] a test drives: the offer, what refresh does, and what launch returns. */
class FakePurchaseClient(
    initial: OfferState = OfferState.Available("A$29.00"),
    var launchResult: LaunchResult = LaunchResult.Started,
) : PurchaseClient {
    override val offer = MutableStateFlow(initial)
    override val failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var refreshes = 0
    var launches = 0
    var refreshResult: RefreshResult = RefreshResult.Ok(found = false)
    val sources = mutableListOf<ReconcileSource>()

    /** Runs inside every refresh, e.g. to flip the entitlement the way a found purchase would. */
    var onRefresh: suspend () -> Unit = {}

    /** Runs inside every launch, e.g. to suspend it so a test can tap twice. */
    var onLaunch: suspend () -> Unit = {}

    override suspend fun refresh(source: ReconcileSource): RefreshResult {
        refreshes++
        sources += source
        onRefresh()
        return refreshResult
    }

    override suspend fun launch(activity: Activity): LaunchResult {
        launches++
        onLaunch()
        return launchResult
    }
}
