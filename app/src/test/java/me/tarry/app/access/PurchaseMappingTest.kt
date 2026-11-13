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
    fun aBackgroundEmptyQueryNeverRevokes() {
        for (s in listOf(PurchaseState.OWNED, PurchaseState.PENDING)) {
            assertThat(mapPurchases(true, emptyList(), s, foreground = false).newState).isEqualTo(s)
        }
    }

    @Test
    fun aBackgroundQueryStillGrantsOwnedAndPending() {
        val bought = mapPurchases(true, listOf(p(PlayPurchaseState.PURCHASED)), PurchaseState.REVOKED, foreground = false)
        assertThat(bought.newState).isEqualTo(PurchaseState.OWNED)
        assertThat(bought.acknowledge).containsExactly("t")
        assertThat(mapPurchases(true, listOf(p(PlayPurchaseState.PENDING)), PurchaseState.NONE, foreground = false).newState)
            .isEqualTo(PurchaseState.PENDING)
    }

    private fun plan(
        ok: Boolean,
        before: PurchaseState,
        vararg ps: PurchaseSnapshot,
        foreground: Boolean = true,
        listener: Boolean = false,
    ) = planPurchases(ok, ps.toList(), before, foreground, listener)

    @Test
    fun theCacheIsWrittenOnlyAfterAnOkQuery() {
        assertThat(plan(true, PurchaseState.NONE).write).isTrue()
        assertThat(plan(false, PurchaseState.NONE, p(PlayPurchaseState.PURCHASED)).write).isFalse()
    }

    @Test
    fun reconcileOnlyOnAChangeToOrFromOwned() {
        assertThat(plan(true, PurchaseState.NONE, p(PlayPurchaseState.PURCHASED)).reconcile).isTrue()
        assertThat(plan(true, PurchaseState.OWNED).reconcile).isTrue() // foreground refund
        assertThat(plan(true, PurchaseState.OWNED, p(PlayPurchaseState.PURCHASED, acked = true)).reconcile).isFalse()
        assertThat(plan(true, PurchaseState.NONE, p(PlayPurchaseState.PENDING)).reconcile).isFalse()
        assertThat(plan(true, PurchaseState.PENDING).reconcile).isFalse() // PENDING to REVOKED
        assertThat(plan(false, PurchaseState.OWNED).reconcile).isFalse()
        assertThat(plan(true, PurchaseState.OWNED, foreground = false).reconcile).isFalse()
    }

    @Test
    fun theAcknowledgeTokensAreListedInThePlan() {
        val d = plan(true, PurchaseState.NONE, p(PlayPurchaseState.PURCHASED, acked = false, token = "x"))
        assertThat(d.acknowledge).containsExactly("x")
        assertThat(d.found).isTrue()
    }

    @Test
    fun anEmptyListenerBatchIsIgnored() {
        val d = plan(true, PurchaseState.OWNED, listener = true)
        assertThat(d.write).isFalse()
        assertThat(d.reconcile).isFalse()
        assertThat(d.newState).isEqualTo(PurchaseState.OWNED)
    }

    @Test
    fun foundIsFalseForAnEmptyOrFailedQuery() {
        assertThat(plan(true, PurchaseState.OWNED, foreground = false).found).isFalse()
        assertThat(plan(false, PurchaseState.NONE, p(PlayPurchaseState.PURCHASED)).found).isFalse()
    }

    @Test
    fun anUnspecifiedStateIsNeitherOwnedNorPending() {
        assertThat(map(true, PurchaseState.NONE, p(PlayPurchaseState.UNSPECIFIED)).newState).isEqualTo(PurchaseState.NONE)
    }
}
