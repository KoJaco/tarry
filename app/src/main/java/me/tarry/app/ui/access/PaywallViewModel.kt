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
import me.tarry.app.access.TrialClock
import me.tarry.app.tracking.TrackingClock
import javax.inject.Inject

/** The one-line result of "Restore purchase". */
enum class RestoreResult { Found, NotFound }

/** Everything the paywall draws. [daysLeft] is set only while a trial that began is running. */
data class PaywallUi(
    val access: AccessState,
    val offer: OfferState,
    val daysLeft: Int? = null,
    val restore: RestoreResult? = null,
    val launchFailed: Boolean = false,
)

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val entitlement: Entitlement,
    private val client: PurchaseClient,
    private val clock: TrackingClock,
) : ViewModel() {
    private data class Extras(val restore: RestoreResult? = null, val launchFailed: Boolean = false)

    private val extras = MutableStateFlow(Extras())

    val ui: StateFlow<PaywallUi> = combine(entitlement.state, client.offer, extras) { access, offer, x ->
        val end = (access as? AccessState.Trial)?.endsAtMs
        PaywallUi(
            access = access,
            offer = offer,
            daysLeft = end?.let { TrialClock.daysLeft(it, clock.nowMs()) }?.takeIf { it > 0 },
            restore = x.restore,
            launchFailed = x.launchFailed,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        PaywallUi(AccessState.NotStarted, OfferState.Loading),
    )

    init {
        // What the account owns and the price, each time the screen opens.
        viewModelScope.launch { client.refresh() }
    }

    fun unlock(activity: Activity) {
        extras.value = Extras()
        viewModelScope.launch {
            when (client.launch(activity)) {
                LaunchResult.Started -> Unit
                LaunchResult.AlreadyOwned -> Unit // The refresh inside has updated the state.
                LaunchResult.Unavailable -> Unit // The offer already reads Unavailable.
                LaunchResult.Error -> extras.update { it.copy(launchFailed = true) }
            }
        }
    }

    fun restore() {
        extras.value = Extras()
        viewModelScope.launch {
            client.refresh()
            val owned = entitlement.current() == AccessState.Owned
            extras.value = Extras(restore = if (owned) RestoreResult.Found else RestoreResult.NotFound)
        }
    }

    /** "Try again" after Google Play could not be reached. */
    fun retry() {
        extras.value = Extras()
        viewModelScope.launch { client.refresh() }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
