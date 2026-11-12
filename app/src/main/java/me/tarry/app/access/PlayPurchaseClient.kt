package me.tarry.app.access

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.ReconcileSource
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingController
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The only class that talks to the Play Billing Library. Sends nothing but the product ID: no account or profile IDs,
 * no app data (ADR 0004). Connects on first use; a failed connection reads as "unavailable" and keeps the cache.
 */
@Singleton
class PlayPurchaseClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PurchaseStore,
    private val clock: TrackingClock,
    private val controller: Provider<TrackingController>,
    @CaptureScope private val scope: CoroutineScope,
) : PurchaseClient {
    private val _offer = MutableStateFlow<OfferState>(OfferState.Loading)
    override val offer: StateFlow<OfferState> = _offer.asStateFlow()

    private val connectLock = Mutex()
    private val applyLock = Mutex()
    private var details: ProductDetails? = null

    private val billing: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(PurchasesUpdatedListener { result, purchases -> onPurchasesUpdated(result, purchases) })
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                // A listener batch is only the new purchases, so an empty one proves nothing.
                if (!purchases.isNullOrEmpty()) scope.launch { safely { apply(ok = true, purchases, ReconcileSource.UI) } }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { refresh() }
            else -> Unit // Cancelled or failed: nothing changes.
        }
    }

    override suspend fun refresh(source: ReconcileSource) {
        safely {
            if (!connect()) {
                _offer.value = OfferState.Unavailable
                return@safely
            }
            val owned = billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            )
            apply(owned.billingResult.responseCode == BillingClient.BillingResponseCode.OK, owned.purchasesList, source)
            loadOffer()
        }
    }

    override suspend fun launch(activity: Activity): LaunchResult = try {
        if (details == null) refresh()
        val d = details
        if (d == null || !connect()) {
            LaunchResult.Unavailable
        } else {
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()),
                )
                .build()
            when (billing.launchBillingFlow(activity, params).responseCode) {
                BillingClient.BillingResponseCode.OK -> LaunchResult.Started
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    refresh()
                    LaunchResult.AlreadyOwned
                }
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
                -> LaunchResult.Unavailable
                else -> LaunchResult.Error
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LaunchResult.Error
    }

    private suspend fun loadOffer() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(LIFETIME_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val result = billing.queryProductDetails(params)
        val found = if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            result.productDetailsList?.firstOrNull { it.productId == LIFETIME_PRODUCT_ID }
        } else {
            null
        }
        details = found
        val price = found?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
        _offer.value = if (price != null) OfferState.Available(price) else OfferState.Unavailable
    }

    /** Applies a query or update to the cache, acknowledges what needs it, and reconciles after a change to or from owned. */
    private suspend fun apply(ok: Boolean, purchases: List<Purchase>, source: ReconcileSource) {
        val snapshots = purchases.map { p ->
            PurchaseSnapshot(
                products = p.products,
                state = when (p.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> PlayPurchaseState.PURCHASED
                    Purchase.PurchaseState.PENDING -> PlayPurchaseState.PENDING
                    else -> PlayPurchaseState.UNSPECIFIED
                },
                acknowledged = p.isAcknowledged,
                token = p.purchaseToken,
            )
        }
        val decision = applyLock.withLock {
            val before = store.state()
            val d = mapPurchases(ok, snapshots, before)
            // Recorded before acknowledging, so a failed acknowledge keeps OWNED and retries on the next refresh.
            if (ok) store.record(d.newState, clock.nowMs())
            if ((before == PurchaseState.OWNED) != (d.newState == PurchaseState.OWNED)) {
                safely { controller.get().reconcile(source) }
            }
            d
        }
        for (token in decision.acknowledge) {
            safely { billing.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) }
        }
    }

    private suspend fun connect(): Boolean = connectLock.withLock {
        if (billing.isReady) return true
        suspendCancellableCoroutine { cont ->
            billing.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    // Auto reconnection handles the next call; a connect still waiting reads as unavailable.
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never thrown to callers; the cache stays as it was.
        }
    }
}
