package me.tarry.app.ui.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import me.tarry.app.tracking.TrackingStateRepository
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val checker: PermissionChecker,
    private val trackingState: TrackingStateRepository,
) : ViewModel() {
    private val snapshot = MutableStateFlow(checker.check())
    private val skipped = MutableStateFlow<Set<PermissionStep>>(emptySet())
    private val disclosure = MutableStateFlow(false)

    val uiState: StateFlow<PermissionsUiState> =
        combine(snapshot, skipped, disclosure) { s, sk, d -> buildPermissionsUiState(s, sk, d) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                buildPermissionsUiState(snapshot.value, skipped.value, disclosure.value),
            )

    /** Re-reads the platform (on resume, after any result) and tells the toggle state to re-evaluate. */
    fun refresh() {
        snapshot.value = checker.check()
        trackingState.refresh()
    }

    fun skip(step: PermissionStep) {
        if (step.skippable) skipped.update { it + step }
    }

    fun showBackgroundDisclosure() {
        disclosure.value = true
    }

    fun hideBackgroundDisclosure() {
        disclosure.value = false
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
