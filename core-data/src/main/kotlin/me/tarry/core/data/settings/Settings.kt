package me.tarry.core.data.settings

enum class RoundingMode { NEAREST, UP }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The device-local access values (8b) and the trial anchor; [purchaseState] is a plain string, the app maps it. */
data class AccessPrefs(
    /** When onboarding finished (UTC epoch ms): the trial anchor. Null before then. */
    val anchorMs: Long? = null,
    val purchaseState: String? = null,
    val purchaseCheckedMs: Long? = null,
    val lastSeenMs: Long? = null,
    val lockedSinceMs: Long? = null,
    val lastAllowedMs: Long? = null,
    /** Closed lock spans, encoded; see [lockedSpans]. */
    val lockIntervals: String? = null,
    val debugState: String? = null,
    val debugTrialEndMs: Long? = null,
) {
    /** Every span tracking was locked: the closed ones, then the open one (which ends at [Long.MAX_VALUE]). Half-open. */
    fun lockedSpans(): List<LockSpan> {
        val closed = lockIntervals.orEmpty().split(',').mapNotNull { part ->
            val (s, e) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val start = s.toLongOrNull() ?: return@mapNotNull null
            val end = e.toLongOrNull() ?: return@mapNotNull null
            LockSpan(start, end)
        }
        return closed + listOfNotNull(lockedSinceMs?.let { LockSpan(it, Long.MAX_VALUE) })
    }
}

/** A span [startMs, endMs) (UTC epoch ms) during which tracking was locked. */
data class LockSpan(val startMs: Long, val endMs: Long)

/** What one access decision asks the settings store to remember; see [SettingsRepository.updateAccess]. */
data class AccessBookkeeping(
    /** The time the decision was made at (the clock, or the high-water mark if later). */
    val nowMs: Long,
    val allowed: Boolean,
    /** Where a new lock would begin (the trial end, if known); never earlier than the last time tracking was allowed. */
    val lockBeganMs: Long?,
)

/** User-facing settings, persisted in Preferences DataStore. */
data class Settings(
    /** One of [ALLOWED_ROUNDING_INCREMENTS]; 0 = no rounding. */
    val roundingIncrementMin: Int = 0,
    val roundingMode: RoundingMode = RoundingMode.NEAREST,
    val minBillableMinutes: Int = 0,
    val roundDailyTotalPerJob: Boolean = false,
    /** One of [ALLOWED_RETENTION_DAYS]; null = keep forever. */
    val rawPingRetentionDays: Int? = 90,
    val reviewReminderEnabled: Boolean = false,
    /** Minutes after local midnight, 0..1439. Default 17:30. */
    val reviewReminderMinuteOfDay: Int = DEFAULT_REVIEW_REMINDER_MINUTE,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** One of [ALLOWED_STAY_RADII_M]. Radius of the stay geofence created at the next stop. */
    val stayGeofenceRadiusM: Int = DEFAULT_STAY_RADIUS_M,
    /** One of [ALLOWED_WINDOW_END_GRACE_MIN]. Longest the session may stay open past the schedule end; 0 = stop on time. */
    val windowEndGraceMaxMin: Int = DEFAULT_WINDOW_END_GRACE_MIN,
    /** One of [ALLOWED_TIMELINE_ZOOM_MINUTES]: the timeline's tick and job-snap step in minutes (15, 10 or 5). */
    val timelineZoomMinutes: Int = DEFAULT_TIMELINE_ZOOM_MINUTES,
    /** Whether the Map tab's overlay is minimised to the time pill and strip (no card). Device-local UI state. */
    val mapOverlayMinimised: Boolean = false,
    /** When the last user-initiated backup finished (UTC epoch ms); null if none yet. */
    val lastBackupMs: Long? = null,
) {
    companion object {
        const val DEFAULT_REVIEW_REMINDER_MINUTE = 1050
        /** DataStore key of [lastBackupMs]; device-local, so a backup file neither carries nor overwrites it. */
        const val LAST_BACKUP_KEY = "last_backup_ms"
        /** DataStore key of the pruned-before watermark; derived from the stored data, so a backup file neither carries nor sets it. */
        const val PRUNED_BEFORE_KEY = "pings_pruned_before_ms"
        /**
         * DataStore key of the first-run onboarding completion time (UTC epoch ms). Device-local, like [LAST_BACKUP_KEY]:
         * a backup file neither carries nor overwrites it, and delete-all keeps it. The 8b trial clock starts from it.
         */
        const val ONBOARDING_COMPLETED_AT_KEY = "onboarding_completed_at_ms"
        /** DataStore key of when onboarding was first shown (UTC epoch ms); device-local and kept out of backups like the completed stamp, so a half-finished onboarding resumes. */
        const val ONBOARDING_STARTED_AT_KEY = "onboarding_started_at_ms"
        /** 8b access state, all device-local: the cached purchase state (NONE, PENDING, OWNED, REVOKED). */
        const val ACCESS_PURCHASE_STATE_KEY = "access_purchase_state"
        /** When the purchase state was last confirmed with the store (UTC epoch ms). */
        const val ACCESS_PURCHASE_CHECKED_KEY = "access_purchase_checked_ms"
        /** The latest time the trial clock has seen (UTC epoch ms); stops a clock set back from extending the trial. */
        const val ACCESS_LAST_SEEN_KEY = "access_last_seen_ms"
        /** When the tracking lock began (UTC epoch ms); windows after it are not segmented. Cleared once tracking is allowed again. */
        const val ACCESS_LOCKED_SINCE_KEY = "access_locked_since_ms"
        /** The last time tracking was allowed (UTC epoch ms), so a lock never starts before it. */
        const val ACCESS_LAST_ALLOWED_KEY = "access_last_allowed_ms"
        /** Closed lock spans as "start:end,start:end" (UTC epoch ms), oldest first, capped; the open one is [ACCESS_LOCKED_SINCE_KEY]. */
        const val ACCESS_LOCK_INTERVALS_KEY = "access_lock_intervals"
        /** Debug builds only: the forced access state (see the Sessions screen switch). */
        const val ACCESS_DEBUG_STATE_KEY = "access_debug_state"
        /** Debug builds only: the trial end the forced state reports (UTC epoch ms). */
        const val ACCESS_DEBUG_TRIAL_END_KEY = "access_debug_trial_end_ms"
        /** Set once the near-end trial card has been shown or dismissed (8b.2). */
        const val ACCESS_TRIAL_CARD_KEY = "access_trial_card_shown"

        /**
         * Keys that belong to this phone only. A backup never carries them and a restore never takes them from the file:
         * the phone's own values (or their absence) are put back, so a hand-edited backup cannot move the trial anchor.
         */
        val DEVICE_LOCAL_KEYS: Set<String> = setOf(
            LAST_BACKUP_KEY,
            PRUNED_BEFORE_KEY,
            ONBOARDING_COMPLETED_AT_KEY,
            ONBOARDING_STARTED_AT_KEY,
            ACCESS_PURCHASE_STATE_KEY,
            ACCESS_PURCHASE_CHECKED_KEY,
            ACCESS_LAST_SEEN_KEY,
            ACCESS_LOCKED_SINCE_KEY,
            ACCESS_LAST_ALLOWED_KEY,
            ACCESS_LOCK_INTERVALS_KEY,
            ACCESS_DEBUG_STATE_KEY,
            ACCESS_DEBUG_TRIAL_END_KEY,
            ACCESS_TRIAL_CARD_KEY,
        )
        const val DEFAULT_STAY_RADIUS_M = 100
        const val DEFAULT_WINDOW_END_GRACE_MIN = 60
        const val DEFAULT_TIMELINE_ZOOM_MINUTES = 15

        /** 300 m was an option before dogfood 1; a stored 300 now reads as [MAX_STAY_RADIUS_M]. */
        const val LEGACY_STAY_RADIUS_M = 300
        const val MAX_STAY_RADIUS_M = 200
        val ALLOWED_ROUNDING_INCREMENTS = setOf(0, 6, 15, 30)
        /** 0 = no minimum. */
        val ALLOWED_MIN_BILLABLE_MINUTES = setOf(0, 15, 30, 60)
        val ALLOWED_RETENTION_DAYS = setOf(30, 90, 365)
        val ALLOWED_STAY_RADII_M = setOf(50, 75, 100, 150, 200)
        val ALLOWED_WINDOW_END_GRACE_MIN = listOf(0, 15, 30, 60)
        val ALLOWED_TIMELINE_ZOOM_MINUTES = setOf(15, 10, 5)
    }
}
