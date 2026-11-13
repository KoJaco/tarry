package me.tarry.app.access

import android.app.Activity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.tarry.app.tracking.ReconcileSource

/** What the paywall knows about the product on offer. */
sealed interface OfferState {
    data object Loading : OfferState

    data class Available(val formattedPrice: String) : OfferState

    data object Unavailable : OfferState
}

/** What starting the purchase did. */
enum class LaunchResult { Started, AlreadyOwned, Unavailable, Error }

/** What a refresh learned: [Ok] after a successful query ([found] = a lifetime purchase, bought or pending), else [Unavailable]. */
sealed interface RefreshResult {
    data class Ok(val found: Boolean) : RefreshResult

    data object Unavailable : RefreshResult
}

/**
 * The Google Play purchase of the one lifetime product. The cache it feeds is [PurchaseStore]; the rest of the app only
 * reads [Entitlement]. No method throws: a failure leaves the cached state as it was.
 */
interface PurchaseClient {
    val offer: StateFlow<OfferState>

    /** One event each time a started purchase fails afterwards (not when the user cancels). */
    val failures: SharedFlow<Unit>

    /**
     * Asks Play what this account owns and what the product costs. After a change to or from owned, reconciles
     * tracking with [source] ([ReconcileSource.SYSTEM] when the process started in the background).
     * Only a [ReconcileSource.UI] refresh may revoke a purchase that Play no longer lists.
     */
    suspend fun refresh(source: ReconcileSource = ReconcileSource.UI): RefreshResult

    suspend fun launch(activity: Activity): LaunchResult
}
