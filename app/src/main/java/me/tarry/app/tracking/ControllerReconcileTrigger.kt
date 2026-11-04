package me.tarry.app.tracking

import javax.inject.Inject

class ControllerReconcileTrigger @Inject constructor(
    private val controller: TrackingController,
) : ReconcileTrigger {
    override suspend fun reconcileNow() = controller.reconcile(ReconcileSource.UI)
}
