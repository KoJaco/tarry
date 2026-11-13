package me.tarry.app.access

/**
 * Whether this phone may start new tracking. Viewing, editing, summary, export, backup and delete never depend on it.
 * [Pending] and [Revoked] only change the wording: a refund or an unconfirmed purchase inside the trial keeps the trial.
 * The trial end in them is null when the trial never started.
 */
sealed interface AccessState {
    /** Onboarding is still open: no anchor yet, so no clock is running and nothing is locked. */
    data object NotStarted : AccessState

    data class Trial(val endsAtMs: Long) : AccessState

    data object TrialEnded : AccessState

    data object Owned : AccessState

    data class Pending(val trialEndsAtMs: Long?) : AccessState

    data class Revoked(val trialEndsAtMs: Long?) : AccessState
}

/** True for [AccessState.Owned], [AccessState.NotStarted], or while a trial that began is still running at [nowMs]. */
fun AccessState.canTrack(nowMs: Long): Boolean = when (this) {
    AccessState.Owned, AccessState.NotStarted -> true
    AccessState.TrialEnded -> false
    is AccessState.Trial -> nowMs < endsAtMs
    is AccessState.Pending -> trialEndsAtMs == null || nowMs < trialEndsAtMs
    is AccessState.Revoked -> trialEndsAtMs == null || nowMs < trialEndsAtMs
}

/** The end of a trial that is still running at [nowMs] and not owned, for arming the alarm; null otherwise. */
fun AccessState.runningTrialEndMs(nowMs: Long): Long? {
    val end = when (this) {
        is AccessState.Trial -> endsAtMs
        is AccessState.Pending -> trialEndsAtMs
        is AccessState.Revoked -> trialEndsAtMs
        else -> null
    }
    return end?.takeIf { nowMs < it }
}
