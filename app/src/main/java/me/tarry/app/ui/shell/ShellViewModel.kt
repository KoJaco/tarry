package me.tarry.app.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.PauseKind
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingDetails
import me.tarry.app.tracking.TrackingState
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.core.data.model.ManualOverrideEndMode
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    repository: TrackingStateRepository,
    private val controller: TrackingController,
    config: TrackingConfig,
    // Controller calls must finish even if the user leaves the screen right after tapping.
    @param:CaptureScope private val captureScope: CoroutineScope,
) : ViewModel() {
    val trackingState: StateFlow<TrackingState> = repository.state

    val trackingDetails: StateFlow<TrackingDetails> = controller.observeDetails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TrackingDetails())

    val startDurationsMin: List<Int> = config.manualStartDurationsMin

    fun start(endMode: ManualOverrideEndMode, durationMin: Int?) {
        captureScope.launch { controller.start(endMode, durationMin) }
    }

    fun pause(kind: PauseKind) {
        captureScope.launch { controller.pause(kind) }
    }

    fun resume() {
        captureScope.launch { controller.resume() }
    }

    fun stop() {
        captureScope.launch { controller.stop() }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
