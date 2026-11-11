package me.tarry.app.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.TrackingClock
import me.tarry.core.data.settings.AccessPrefs
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

/** The only gate for starting tracking. Nothing else in the app decides access. */
interface Entitlement {
    val state: StateFlow<AccessState>

    suspend fun current(): AccessState

    suspend fun canTrack(): Boolean
}

/**
 * Shared plumbing: evaluates [AccessState] from the device-local prefs and the clock, and keeps the rollback high-water
 * mark and the lock-since stamp up to date. Subclasses decide the state in [evaluate].
 */
abstract class PrefsEntitlement(
    private val settings: SettingsRepository,
    private val clock: TrackingClock,
    scope: CoroutineScope,
) : Entitlement {
    internal abstract fun evaluate(prefs: AccessPrefs, nowMs: Long): AccessState

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<AccessState> = settings.accessPrefs
        .transformLatest { prefs ->
            val now = TrialClock.effectiveNowMs(clock.nowMs(), prefs.lastSeenMs)
            val s = evaluate(prefs, now)
            emit(s)
            // A running trial ends while a screen is open: re-evaluate then (in-process, not a tracking timer).
            val end = s.runningTrialEndMs(now)
            if (end != null) {
                delay(end - now)
                emit(evaluate(prefs, maxOf(clock.nowMs(), end)))
            }
        }
        .stateIn(scope, SharingStarted.Lazily, AccessState.NotStarted)

    override suspend fun current(): AccessState = refresh().first

    override suspend fun canTrack(): Boolean {
        val (s, now) = refresh()
        return s.canTrack(now)
    }

    private suspend fun refresh(): Pair<AccessState, Long> {
        val prefs = settings.currentAccessPrefs()
        val now = TrialClock.effectiveNowMs(clock.nowMs(), prefs.lastSeenMs)
        // At most one write a minute, so the stamp cannot feed back into the state flow in a loop.
        val seen = prefs.lastSeenMs
        if (seen == null || now - seen >= HIGH_WATER_STEP_MS) settings.raiseAccessLastSeenMs(now)
        val s = evaluate(prefs, now)
        val locked = prefs.lockedSinceMs
        if (s.canTrack(now)) {
            if (locked != null) settings.setAccessLockedSinceMs(null)
        } else if (locked == null) {
            // The lock began when the trial ended, if that is known, else now.
            val began = when (s) {
                is AccessState.Pending -> s.trialEndsAtMs
                is AccessState.Revoked -> s.trialEndsAtMs
                else -> prefs.anchorMs?.let { TrialClock.endsAtMs(it) }
            }
            settings.setAccessLockedSinceMs(minOf(began ?: now, now))
        }
        return s to now
    }

    private companion object {
        const val HIGH_WATER_STEP_MS = 60_000L
    }
}

/** Trial clock plus the cached purchase state. */
class RealEntitlement @Inject constructor(
    settings: SettingsRepository,
    clock: TrackingClock,
    @CaptureScope scope: CoroutineScope,
) : PrefsEntitlement(settings, clock, scope) {
    override fun evaluate(prefs: AccessPrefs, nowMs: Long): AccessState {
        val trial = TrialClock.status(prefs.anchorMs, nowMs)
        val trialEnd = when (trial) {
            TrialStatus.NotStarted -> null
            is TrialStatus.Running -> trial.endsAtMs
            is TrialStatus.Ended -> trial.endsAtMs
        }
        return when (PurchaseStore.parse(prefs.purchaseState)) {
            PurchaseState.OWNED -> AccessState.Owned
            PurchaseState.PENDING -> AccessState.Pending(trialEnd)
            PurchaseState.REVOKED -> AccessState.Revoked(trialEnd)
            PurchaseState.NONE -> when (trial) {
                TrialStatus.NotStarted -> AccessState.NotStarted
                is TrialStatus.Running -> AccessState.Trial(trial.endsAtMs)
                is TrialStatus.Ended -> AccessState.TrialEnded
            }
        }
    }
}

/** What the debug switch can force; REAL hands over to [RealEntitlement]. */
enum class DebugAccess { OWNED, TRIAL_6_DAYS, TRIAL_1_DAY, TRIAL_ENDED, PENDING, REVOKED, REAL }

/** Debug builds: a forced state (default [DebugAccess.OWNED]); [DebugAccess.REAL] delegates to the real entitlement. */
class DebugEntitlement @Inject constructor(
    private val real: RealEntitlement,
    settings: SettingsRepository,
    clock: TrackingClock,
    @CaptureScope scope: CoroutineScope,
) : PrefsEntitlement(settings, clock, scope) {
    override fun evaluate(prefs: AccessPrefs, nowMs: Long): AccessState {
        val forced = DebugAccess.entries.firstOrNull { it.name == prefs.debugState } ?: DebugAccess.OWNED
        // Each variant carries its own trial end, set when it was chosen; a missing one reads as already ended.
        val end = prefs.debugTrialEndMs
        return when (forced) {
            DebugAccess.OWNED -> AccessState.Owned
            DebugAccess.TRIAL_6_DAYS, DebugAccess.TRIAL_1_DAY ->
                if (end != null && nowMs < end) AccessState.Trial(end) else AccessState.TrialEnded
            DebugAccess.TRIAL_ENDED -> AccessState.TrialEnded
            DebugAccess.PENDING -> AccessState.Pending(end ?: nowMs)
            DebugAccess.REVOKED -> AccessState.Revoked(end ?: nowMs)
            DebugAccess.REAL -> real.evaluate(prefs, nowMs)
        }
    }
}
