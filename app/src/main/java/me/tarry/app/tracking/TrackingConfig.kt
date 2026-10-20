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
    /** How long a due session may take to appear before "service not running" is reported. */
    val serviceStartGraceMs: Long = 2 * 60_000L,
    /** Health Gaps of the same cause closer together than this are merged into one. */
    val gapMergeToleranceMs: Long = 60_000L,
    // Schedule engine.
    /** How far back and ahead the calculator builds windows when looking for the next transition. */
    val scheduleLookBackDays: Long = 1L,
    val scheduleLookAheadDays: Long = 9L,
    /** Delay of the one-shot alarm that starts capture after boot or app start (ADR 0003: background FGS starts need an alarm). */
    val deferredStartDelayMs: Long = 5_000L,
    /** Upper bound for persisting a failed foreground start before the service stops itself (must stay well under the ANR limit). */
    val startFailurePersistTimeoutMs: Long = 3_000L,
    // Manual controls.
    val pauseShortMin: Long = 30L,
    val pauseLongMin: Long = 60L,
    val manualStartDurationsMin: List<Int> = listOf(60, 120, 240),
    // Debug-only test window: starts [debugTestWindowDelayMs] after the request and lasts [debugTestWindowLengthMs].
    val debugTestWindowDelayMs: Long = 2 * 60_000L,
    val debugTestWindowLengthMs: Long = 10 * 60_000L,
)
