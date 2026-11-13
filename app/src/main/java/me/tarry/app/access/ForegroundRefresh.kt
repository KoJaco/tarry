package me.tarry.app.access

import me.tarry.app.tracking.ReconcileSource

/**
 * The app-opened refresh of the purchase cache. If whether tracking is allowed changed while it ran, whichever refresh
 * wrote it (this one, or the process-start one that was still in flight), [reconcileUi] runs so a scheduled window
 * starts or stops straight away.
 */
suspend fun refreshPurchaseAndReconcile(
    entitlement: Entitlement,
    client: PurchaseClient,
    reconcileUi: suspend () -> Unit,
) {
    val before = entitlement.canTrack()
    client.refresh(ReconcileSource.UI)
    if (entitlement.canTrack() != before) reconcileUi()
}
