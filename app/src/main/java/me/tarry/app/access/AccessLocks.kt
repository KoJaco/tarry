package me.tarry.app.access

import kotlinx.coroutines.CancellationException
import me.tarry.app.segmentation.AccessLockSource
import me.tarry.core.data.settings.AccessPrefs
import me.tarry.core.data.settings.SettingsRepository

/**
 * The lock spans for segmentation. It asks the entitlement first, so a run right after the trial ended sees the lock even
 * if nothing has evaluated access since (the evaluation opens the span). A failure there leaves the stored spans as they are.
 */
fun entitlementLockSource(entitlement: Entitlement, settings: SettingsRepository): AccessLockSource = AccessLockSource {
    try {
        entitlement.current()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Stored spans below are still right for everything before this moment.
    }
    try {
        settings.currentAccessPrefs().lockedSpans()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AccessPrefs().lockedSpans()
    }
}
