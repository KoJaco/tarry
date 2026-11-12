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
 * A failed query ([queryOk] false) never changes anything; a successful one with no lifetime purchase revokes an
 * owned or pending cache (refund, cancel) and leaves NONE (and an already REVOKED) as it is.
 */
fun mapPurchases(queryOk: Boolean, purchases: List<PurchaseSnapshot>, current: PurchaseState): PurchaseDecision {
    if (!queryOk) return PurchaseDecision(current, emptyList())
    val ours = purchases.filter { LIFETIME_PRODUCT_ID in it.products }
    val bought = ours.filter { it.state == PlayPurchaseState.PURCHASED }
    if (bought.isNotEmpty()) {
        return PurchaseDecision(PurchaseState.OWNED, bought.filter { !it.acknowledged }.map { it.token })
    }
    if (ours.any { it.state == PlayPurchaseState.PENDING }) return PurchaseDecision(PurchaseState.PENDING, emptyList())
    val revoked = current == PurchaseState.OWNED || current == PurchaseState.PENDING
    return PurchaseDecision(if (revoked) PurchaseState.REVOKED else current, emptyList())
}
