package me.tarry.app.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CurrentFixResult
import me.tarry.app.tracking.LocationClient
import me.tarry.app.tracking.PreconditionChecker
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.ui.permissions.PermissionChecker
import javax.inject.Inject

/**
 * Backs Tracking health. The checks are read from the platform on [refresh] (the screen calls it on every resume), never
 * on a timer (rule 5). Test tracking is one user-initiated location request: no session, no ping, no repeat. Leaving the
 * screen cancels it (and the platform request with it).
 */
@HiltViewModel
class TrackingHealthViewModel @Inject constructor(
    private val permissions: PermissionChecker,
    private val preconditions: PreconditionChecker,
    private val repository: TrackingStateRepository,
    private val locationClient: LocationClient,
    private val config: TrackingConfig,
    private val clock: TrackingClock,
) : ViewModel() {
    private val checks = MutableStateFlow(readChecks())
    private val test = MutableStateFlow<TestFixState>(TestFixState.Idle)
    private var testJob: Job? = null

    val uiState: StateFlow<TrackingHealthUiState> =
        combine(repository.state, repository.details, checks, test) { state, details, checks, test ->
            TrackingHealthUiState(state, details.attentionCause, checks, test)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            TrackingHealthUiState(repository.state.value, repository.details.value.attentionCause, checks.value, test.value),
        )

    private fun readChecks() = buildHealthChecks(permissions.check(), preconditions.check())

    /** Re-reads the platform; call when the screen resumes (the user may be back from a system settings page). */
    fun refresh() {
        checks.value = readChecks()
        repository.refresh()
    }

    /** Asks for one high-accuracy fix. Ignored while one is already running. */
    fun testTracking() {
        if (testJob?.isActive == true) return
        test.value = TestFixState.Running
        testJob = viewModelScope.launch {
            val result = locationClient.currentFix(config.movingPriority, config.testFixTimeoutMs)
            test.update {
                when (result) {
                    is CurrentFixResult.Success -> successOf(result.fix, clock.nowMs(), blockedBy())
                    CurrentFixResult.Timeout -> TestFixState.Timeout((config.testFixTimeoutMs / MS_PER_SECOND).toInt())
                    CurrentFixResult.Denied -> TestFixState.Denied
                }
            }
            // The request may have changed what the checks say (a permission just revoked, say).
            checks.value = readChecks()
        }
    }

    private fun blockedBy() = firstBlockingCause(preconditions.check()) ?: repository.details.value.attentionCause

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val MS_PER_SECOND = 1_000L
    }
}
