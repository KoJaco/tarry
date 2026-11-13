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
import me.tarry.app.access.Entitlement
import me.tarry.core.data.repository.TrackingRepository
import me.tarry.core.data.settings.PowerMode
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
 * The real tracking state. Re-evaluated when the status store, the active overrides or the access state change, and on [refresh]
 * (app foreground), only while observed. There is no timer (rule 5): window edges and pause ends reach us through the alarm, which
 * reconciles and so changes the stores.
 */
@Singleton
class DefaultTrackingStateRepository @Inject constructor(
    private val snapshots: TrackingSnapshotProvider,
    private val reducer: TrackingStateReducer,
    private val statusStore: TrackingStatusStore,
    private val trackingRepo: TrackingRepository,
    private val entitlement: Entitlement,
    private val powerSettings: PowerSettingsSource,
    @param:CaptureScope private val scope: CoroutineScope,
) : TrackingStateRepository {
    private val refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reduced: StateFlow<ReducedTrackingState> =
        // The access state is only a re-reduce trigger (trial ending, purchase); the lock itself is read by capture() via canTrack().
        combine(statusStore.status, trackingRepo.observeActiveOverrides(), refreshTick, entitlement.state) { _, _, _, _ -> }
            .mapLatest { reducer.reduce(snapshots.capture()) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ReducedTrackingState(TrackingState.IDLE))

    override val state: StateFlow<TrackingState> =
        reduced.map { it.state }.stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), reduced.value.state)

    override val details: StateFlow<TrackingDetails> =
        combine(
            reduced,
            statusStore.status.map { it.lowBatterySaverActive }.distinctUntilChanged(),
            powerSettings.settings.map { it.mode }.distinctUntilChanged(),
        ) { r, lowBattery, mode -> detailsOf(r, lowBattery, mode) }
            .stateIn(scope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TrackingDetails())

    override fun refresh() {
        refreshTick.value++
    }

    internal companion object {
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /** The stored low-battery flag is only shown while TRACKING: a flag left behind by a killed session must not show. */
        fun detailsOf(r: ReducedTrackingState, lowBatteryFlag: Boolean, mode: PowerMode) = TrackingDetails(
            r.attentionCause, r.pausedUntil, r.finishingUp, mode, lowBatteryFlag && r.state == TrackingState.TRACKING,
        )
    }
}
