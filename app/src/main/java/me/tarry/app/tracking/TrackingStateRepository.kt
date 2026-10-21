package me.tarry.app.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import me.tarry.core.data.repository.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

interface TrackingStateRepository {
    val state: StateFlow<TrackingState>

    /** Cause and pause end for the toggle sheet. */
    val details: StateFlow<TrackingDetails>

    /** Recomputes now; call when something outside our stores may have changed (app foreground). */
    fun refresh()
}

/**
 * The real tracking state. Re-evaluated when the status store or the active overrides change, and on [refresh]
 * (app foreground), only while observed. There is no timer (rule 5): window edges and pause ends reach us through the alarm, which
 * reconciles and so changes the stores.
 */
@Singleton
class DefaultTrackingStateRepository @Inject constructor(
    private val snapshots: TrackingSnapshotProvider,
    private val reducer: TrackingStateReducer,
    private val statusStore: TrackingStatusStore,
    private val trackingRepo: TrackingRepository,
    @param:CaptureScope private val scope: CoroutineScope,
) : TrackingStateRepository {
    private val refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reduced: StateFlow<ReducedTrackingState> =
        combine(statusStore.status, trackingRepo.observeActiveOverrides(), refreshTick) { _, _, _ -> }
            .mapLatest { reducer.reduce(snapshots.capture()) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ReducedTrackingState(TrackingState.IDLE))

    override val state: StateFlow<TrackingState> =
        reduced.map { it.state }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), reduced.value.state)

    override val details: StateFlow<TrackingDetails> =
        reduced.map { TrackingDetails(it.attentionCause, it.pausedUntil) }
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TrackingDetails())

    override fun refresh() {
        refreshTick.value++
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
