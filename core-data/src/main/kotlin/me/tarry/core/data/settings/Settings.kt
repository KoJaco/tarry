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
) {
    companion object {
        const val DEFAULT_REVIEW_REMINDER_MINUTE = 1050
        const val DEFAULT_STAY_RADIUS_M = 100

        /** 300 m was an option before dogfood 1; a stored 300 now reads as [MAX_STAY_RADIUS_M]. */
        const val LEGACY_STAY_RADIUS_M = 300
        const val MAX_STAY_RADIUS_M = 200
        val ALLOWED_ROUNDING_INCREMENTS = setOf(0, 6, 15, 30)
        val ALLOWED_RETENTION_DAYS = setOf(30, 90, 365)
        val ALLOWED_STAY_RADII_M = setOf(50, 75, 100, 150, 200)
    }
}
