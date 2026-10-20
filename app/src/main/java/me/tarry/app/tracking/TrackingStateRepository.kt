package me.tarry.app.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface TrackingStateRepository {
    val state: StateFlow<TrackingState>
}

/** Phase 1 placeholder: tracking does not exist yet, so the state is always IDLE. Phase 2a replaces it. */
@Singleton
class IdleTrackingStateRepository @Inject constructor() : TrackingStateRepository {
    override val state: StateFlow<TrackingState> = MutableStateFlow(TrackingState.IDLE).asStateFlow()
}
