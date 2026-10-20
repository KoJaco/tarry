package me.tarry.app.tracking

enum class LocationPriority { PASSIVE, LOW_POWER, BALANCED }

/**
 * Every tracking tunable (AGENTS rule 5). Defaults come from docs/specs/tarry-mvp.md
 * and are expected to be tuned during dogfooding.
 */
data class TrackingConfig(
    // STATIONARY entry rule: no movement beyond [stationaryEntryRadiusM] for [stationaryEntryDurationMs].
    val stationaryEntryRadiusM: Float = 50f,
    val stationaryEntryDurationMs: Long = 3 * 60_000L,
    // STATIONARY location request.
    val stationaryPriority: LocationPriority = LocationPriority.LOW_POWER,
    val stationaryFixIntervalMs: Long = 5 * 60_000L,
    val stayGeofenceRadiusM: Float = 150f,
    // MOVING location request.
    val movingPriority: LocationPriority = LocationPriority.BALANCED,
    val movingIntervalMs: Long = 20_000L,
    val movingMinDisplacementM: Float = 25f,
    val movingMaxUpdateDelayMs: Long = 2 * 60_000L,
    // Ping filters.
    val maxAccuracyM: Float = 100f,
    val maxSpeedMps: Float = 70f,
    // Health.
    val noHeartbeatThresholdMs: Long = 20 * 60_000L,
    val healthWorkerPeriodMin: Long = 15L,
)
