package me.tarry.app.access

/** The one in-app product: a non-consumable, bought once. */
const val LIFETIME_PRODUCT_ID = "lifetime"

/** The state of one Play purchase, without the library's types. */
enum class PlayPurchaseState { PURCHASED, PENDING, UNSPECIFIED }

/** One purchase as the store reported it: its product IDs, state and whether it was acknowledged. */
data class PurchaseSnapshot(
    val products: List<String>,
    val state: PlayPurchaseState,
    val acknowledged: Boolean,
    /** Only used to acknowledge; never stored or sent anywhere else. */
    val token: String = "",
)

/** What a purchase query means for the cache: the new state (the current one when nothing changes) and the tokens to acknowledge. */
data class PurchaseDecision(val newState: PurchaseState, val acknowledge: List<String>)

/**
 * Pure mapping from a store query to the cached state. Only purchases of [LIFETIME_PRODUCT_ID] count.
 * A failed query ([queryOk] false) never changes anything. A successful one with no lifetime purchase revokes an
 * owned or pending cache (refund, cancel) only when it is a [foreground] refresh; a background (process start) one may
 * grant OWNED or PENDING but never writes REVOKED. NONE and an already REVOKED stay as they are.
 */
fun mapPurchases(
    queryOk: Boolean,
    purchases: List<PurchaseSnapshot>,
    current: PurchaseState,
    foreground: Boolean = true,
): PurchaseDecision {
    if (!queryOk) return PurchaseDecision(current, emptyList())
    val ours = purchases.filter { LIFETIME_PRODUCT_ID in it.products }
    val bought = ours.filter { it.state == PlayPurchaseState.PURCHASED }
    if (bought.isNotEmpty()) {
        return PurchaseDecision(PurchaseState.OWNED, bought.filter { !it.acknowledged }.map { it.token })
    }
    if (ours.any { it.state == PlayPurchaseState.PENDING }) return PurchaseDecision(PurchaseState.PENDING, emptyList())
    val revoked = foreground && (current == PurchaseState.OWNED || current == PurchaseState.PENDING)
    return PurchaseDecision(if (revoked) PurchaseState.REVOKED else current, emptyList())
}

/**
 * What to do with one query or listener update: whether to [write] the cache (only after an OK query), whether to
 * [reconcile] tracking (only on a change to or from OWNED), which tokens to [acknowledge] (after the write), and whether
 * a lifetime purchase was [found] (bought or pending).
 */
data class PurchasePlan(
    val newState: PurchaseState,
    val write: Boolean,
    val reconcile: Boolean,
    val acknowledge: List<String>,
    val found: Boolean,
)

/**
 * The whole decision for [PlayPurchaseClient]. A [listenerBatch] holds only the new purchases, so an empty one proves
 * nothing and is ignored.
 */
fun planPurchases(
    queryOk: Boolean,
    purchases: List<PurchaseSnapshot>,
    before: PurchaseState,
    foreground: Boolean,
    listenerBatch: Boolean = false,
): PurchasePlan {
    if (listenerBatch && purchases.isEmpty()) return PurchasePlan(before, false, false, emptyList(), false)
    val d = mapPurchases(queryOk, purchases, before, foreground)
    val found = queryOk && purchases.any {
        LIFETIME_PRODUCT_ID in it.products && (it.state == PlayPurchaseState.PURCHASED || it.state == PlayPurchaseState.PENDING)
    }
    return PurchasePlan(
        newState = d.newState,
        write = queryOk,
        reconcile = queryOk && (before == PurchaseState.OWNED) != (d.newState == PurchaseState.OWNED),
        acknowledge = d.acknowledge,
        found = found,
    )
}
