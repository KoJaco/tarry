package me.tarry.app.access

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PurchaseMappingTest {
    private fun p(state: PlayPurchaseState, acked: Boolean = false, vararg products: String = arrayOf(LIFETIME_PRODUCT_ID), token: String = "t") =
        PurchaseSnapshot(products.toList(), state, acked, token)

    private fun map(ok: Boolean, current: PurchaseState, vararg ps: PurchaseSnapshot) = mapPurchases(ok, ps.toList(), current)

    @Test
    fun purchasedAndNotAcknowledgedIsOwnedAndAsksForTheAcknowledge() {
        val d = map(true, PurchaseState.NONE, p(PlayPurchaseState.PURCHASED, acked = false, token = "abc"))
        assertThat(d.newState).isEqualTo(PurchaseState.OWNED)
        assertThat(d.acknowledge).containsExactly("abc")
    }

    @Test
    fun purchasedAndAcknowledgedIsOwnedWithNothingToAcknowledge() {
        val d = map(true, PurchaseState.NONE, p(PlayPurchaseState.PURCHASED, acked = true))
        assertThat(d.newState).isEqualTo(PurchaseState.OWNED)
        assertThat(d.acknowledge).isEmpty()
    }

    @Test
    fun pendingIsPending() {
        assertThat(map(true, PurchaseState.NONE, p(PlayPurchaseState.PENDING)).newState).isEqualTo(PurchaseState.PENDING)
    }

    @Test
    fun pendingThenPurchasedIsOwned() {
        val d = map(true, PurchaseState.PENDING, p(PlayPurchaseState.PURCHASED))
        assertThat(d.newState).isEqualTo(PurchaseState.OWNED)
        assertThat(d.acknowledge).containsExactly("t")
    }

    @Test
    fun aSuccessfulEmptyQueryAfterOwnedIsRevoked() {
        assertThat(map(true, PurchaseState.OWNED).newState).isEqualTo(PurchaseState.REVOKED)
    }

    @Test
    fun aSuccessfulEmptyQueryAfterPendingIsRevoked() {
        assertThat(map(true, PurchaseState.PENDING).newState).isEqualTo(PurchaseState.REVOKED)
    }

    @Test
    fun aSuccessfulEmptyQueryAfterNoneStaysNone() {
        assertThat(map(true, PurchaseState.NONE).newState).isEqualTo(PurchaseState.NONE)
    }

    @Test
    fun aSuccessfulEmptyQueryKeepsRevoked() {
        assertThat(map(true, PurchaseState.REVOKED).newState).isEqualTo(PurchaseState.REVOKED)
    }

    @Test
    fun aFailedQueryKeepsEveryState() {
        for (s in PurchaseState.entries) {
            assertThat(map(false, s).newState).isEqualTo(s)
            // Even a purchase in the list is not trusted when the query itself failed.
            val d = map(false, s, p(PlayPurchaseState.PURCHASED))
            assertThat(d.newState).isEqualTo(s)
            assertThat(d.acknowledge).isEmpty()
        }
    }

    @Test
    fun otherProductIdsAreIgnored() {
        val other = p(PlayPurchaseState.PURCHASED, acked = false, "something_else")
        assertThat(map(true, PurchaseState.NONE, other).newState).isEqualTo(PurchaseState.NONE)
        val d = map(true, PurchaseState.OWNED, other)
        assertThat(d.newState).isEqualTo(PurchaseState.REVOKED)
        assertThat(d.acknowledge).isEmpty()
    }

    @Test
    fun revokedThenPurchasedAgainIsOwned() {
        assertThat(map(true, PurchaseState.REVOKED, p(PlayPurchaseState.PURCHASED, acked = true)).newState)
            .isEqualTo(PurchaseState.OWNED)
    }

    @Test
    fun anUnspecifiedStateIsNeitherOwnedNorPending() {
        assertThat(map(true, PurchaseState.NONE, p(PlayPurchaseState.UNSPECIFIED)).newState).isEqualTo(PurchaseState.NONE)
    }
}
