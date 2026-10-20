package me.tarry.app.ui.shell

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import me.tarry.app.tracking.TrackingState
import me.tarry.app.tracking.TrackingStateRepository
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    repository: TrackingStateRepository,
) : ViewModel() {
    val trackingState: StateFlow<TrackingState> = repository.state
}
