package me.tarry.app.ui.access

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tarry.app.access.AccessState
import me.tarry.app.access.Entitlement
import me.tarry.app.access.LaunchResult
import me.tarry.app.access.OfferState
import me.tarry.app.access.PurchaseClient
import me.tarry.app.access.RefreshResult
import me.tarry.app.access.TrialClock
import me.tarry.app.tracking.TrackingClock
import javax.inject.Inject

/** The one-line result of "Restore purchase". [Unavailable] means Google Play could not be asked, so nothing is known. */
enum class RestoreResult { Found, NotFound, Unavailable }

/** Everything the paywall draws. [daysLeft] is set only while a trial that began is running. */
data class PaywallUi(
    val access: AccessState,
    val offer: OfferState,
    val daysLeft: Int? = null,
    val restore: RestoreResult? = null,
    val launchFailed: Boolean = false,
    /** A purchase is being started: the primary button is off so a second tap does nothing. */
    val launching: Boolean = false,
)

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val entitlement: Entitlement,
    private val client: PurchaseClient,
    private val clock: TrackingClock,
) : ViewModel() {
    private data class Extras(
        val restore: RestoreResult? = null,
        val launchFailed: Boolean = false,
        val launching: Boolean = false,
        val retrying: Boolean = false,
    )

    private val extras = MutableStateFlow(Extras())

    val ui: StateFlow<PaywallUi> = combine(entitlement.state, client.offer, extras) { access, offer, x ->
        val end = (access as? AccessState.Trial)?.endsAtMs
        // An error from an earlier tap means nothing once the purchase has gone through or is pending.
        val settled = access == AccessState.Owned || access is AccessState.Pending
        PaywallUi(
            access = access,
            offer = if (x.retrying) OfferState.Loading else offer,
            daysLeft = end?.let { TrialClock.daysLeft(it, clock.nowMs()) }?.takeIf { it > 0 },
            restore = x.restore,
            launchFailed = x.launchFailed && !settled,
            launching = x.launching,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        PaywallUi(AccessState.NotStarted, OfferState.Loading),
    )

    init {
        // What the account owns and the price, each time the screen opens.
        viewModelScope.launch { client.refresh() }
        // A purchase that Play started and then failed.
        viewModelScope.launch { client.failures.collect { extras.update { it.copy(launchFailed = true) } } }
    }

    fun unlock(activity: Activity) {
        if (extras.value.launching) return
        extras.update { it.copy(restore = null, launchFailed = false, launching = true) }
        viewModelScope.launch {
            try {
                when (client.launch(activity)) {
                    LaunchResult.Started -> Unit
                    LaunchResult.AlreadyOwned -> Unit // The refresh inside has updated the state.
                    LaunchResult.Unavailable -> Unit // The offer already reads Unavailable.
                    LaunchResult.Error -> extras.update { it.copy(launchFailed = true) }
                }
            } finally {
                extras.update { it.copy(launching = false) }
            }
        }
    }

    fun restore() {
        extras.update { it.copy(restore = null, launchFailed = false) }
        viewModelScope.launch {
            val result = client.refresh()
            val owned = entitlement.current() == AccessState.Owned
            val outcome = when {
                owned -> RestoreResult.Found
                result is RefreshResult.Unavailable -> RestoreResult.Unavailable
                // A pending purchase is neither restored nor missing: the Pending state says it.
                (result as? RefreshResult.Ok)?.found == true -> null
                else -> RestoreResult.NotFound
            }
            extras.update { it.copy(restore = outcome) }
        }
    }

    /** "Try again" after Google Play could not be reached: the price reads as loading until the answer comes. */
    fun retry() {
        extras.update { it.copy(restore = null, launchFailed = false, retrying = true) }
        viewModelScope.launch {
            try {
                client.refresh()
            } finally {
                extras.update { it.copy(retrying = false) }
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
