package me.tarry.app.access

import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The cached purchase state. Written only through [PurchaseStore]. */
enum class PurchaseState { NONE, PENDING, OWNED, REVOKED }

/** The one writer of the cached purchase state in the settings DataStore (8b.2's Play client feeds it). */
@Singleton
class PurchaseStore @Inject constructor(private val settings: SettingsRepository) {
    suspend fun state(): PurchaseState = parse(settings.currentAccessPrefs().purchaseState)

    suspend fun record(state: PurchaseState, checkedMs: Long) {
        settings.setAccessPurchase(state.name, checkedMs)
    }

    internal companion object {
        fun parse(raw: String?): PurchaseState = PurchaseState.entries.firstOrNull { it.name == raw } ?: PurchaseState.NONE
    }
}
