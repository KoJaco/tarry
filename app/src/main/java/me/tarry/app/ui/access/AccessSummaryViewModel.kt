package me.tarry.app.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.access.AccessState
import me.tarry.app.access.Entitlement
import me.tarry.app.access.TrialClock
import me.tarry.app.tracking.TrackingClock
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

/** The access row's text, resolved from [AccessState] and the clock; null while the trial has not started. */
sealed interface AccessRow {
    data class Trial(val daysLeft: Int) : AccessRow

    data object TrialEnded : AccessRow

    data object Pending : AccessRow

    data object Owned : AccessRow

    data object Revoked : AccessRow
}

fun accessRow(state: AccessState, nowMs: Long): AccessRow? = when (state) {
    AccessState.NotStarted -> null
    is AccessState.Trial -> TrialClock.daysLeft(state.endsAtMs, nowMs).let { if (it > 0) AccessRow.Trial(it) else AccessRow.TrialEnded }
    AccessState.TrialEnded -> AccessRow.TrialEnded
    AccessState.Owned -> AccessRow.Owned
    is AccessState.Pending -> AccessRow.Pending
    is AccessState.Revoked -> AccessRow.Revoked
}

/** True for a running trial with at most 24 hours left, when the card has not been shown or dismissed. Never when owned. */
fun trialCardVisible(state: AccessState, alreadyShown: Boolean, nowMs: Long): Boolean {
    if (alreadyShown || state !is AccessState.Trial) return false
    val left = state.endsAtMs - nowMs
    return left in 1..TrialClock.MS_PER_DAY
}

/** Feeds the Settings access row and the Timeline's near-end trial card. */
@HiltViewModel
class AccessSummaryViewModel @Inject constructor(
    entitlement: Entitlement,
    private val settings: SettingsRepository,
    private val clock: TrackingClock,
) : ViewModel() {
    val row: StateFlow<AccessRow?> = entitlement.state
        .combine(settings.accessPrefs) { state, _ -> accessRow(state, clock.nowMs()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    val trialCard: StateFlow<Boolean> = entitlement.state
        .combine(settings.accessPrefs) { state, prefs -> trialCardVisible(state, prefs.trialCardShown, TrialClock.effectiveNowMs(clock.nowMs(), prefs.lastSeenMs)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

    /** Dismiss and Unlock both end the card for good. */
    fun dismissTrialCard() {
        viewModelScope.launch { settings.setAccessTrialCardShown() }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
