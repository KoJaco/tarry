package me.tarry.app.access

/** Where the trial stands. */
sealed interface TrialStatus {
    data object NotStarted : TrialStatus

    data class Running(val endsAtMs: Long) : TrialStatus

    data class Ended(val endsAtMs: Long) : TrialStatus
}

/**
 * The 7-day local trial. The anchor is the onboarding completion stamp; the end is exactly 7 x 24 h later, so a time-zone
 * change cannot move it. Time is `max(now, high-water)`: a clock set back does not extend the trial.
 */
object TrialClock {
    const val MS_PER_DAY = 24 * 60 * 60 * 1000L
    const val TRIAL_DURATION_MS = 7 * MS_PER_DAY

    fun endsAtMs(anchorMs: Long): Long = anchorMs + TRIAL_DURATION_MS

    /** [lastSeenMs] is the persisted high-water mark, if any. */
    fun effectiveNowMs(nowMs: Long, lastSeenMs: Long?): Long = maxOf(nowMs, lastSeenMs ?: Long.MIN_VALUE)

    fun status(anchorMs: Long?, effectiveNowMs: Long): TrialStatus {
        if (anchorMs == null) return TrialStatus.NotStarted
        val end = endsAtMs(anchorMs)
        return if (effectiveNowMs < end) TrialStatus.Running(end) else TrialStatus.Ended(end)
    }

    /** Whole days left, rounded up (display only); 0 once the end has passed. */
    fun daysLeft(endsAtMs: Long, nowMs: Long): Int {
        val left = endsAtMs - nowMs
        if (left <= 0) return 0
        return ((left + MS_PER_DAY - 1) / MS_PER_DAY).toInt()
    }
}
