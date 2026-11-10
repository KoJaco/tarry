package me.tarry.core.data.settings

enum class RoundingMode { NEAREST, UP }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
