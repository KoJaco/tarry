package me.tarry.app.tracking

enum class LocationPriority { PASSIVE, LOW_POWER, BALANCED, HIGH_ACCURACY }

/**
 * Every tracking tunable (AGENTS rule 5). Defaults come from docs/specs/tarry-mvp.md
 * and are expected to be tuned during dogfooding.
 */
data class TrackingConfig(
    // STATIONARY entry rule: no movement beyond [stationaryEntryRadiusM] for [stationaryEntryDurationMs].
    val stationaryEntryRadiusM: Float = 50f,
    val stationaryEntryDurationMs: Long = 3 * 60_000L,
    /**
     * Safety net for a lost geofence exit: STATIONARY is left by a fix only after [stationaryExitFixes] consecutive
     * fixes clearly outside the stay radius (distance minus accuracy), spanning at least [stationaryExitMinMs].
     */
    val stationaryExitFixes: Int = 2,
    val stationaryExitMinMs: Long = 60_000L,
    // STATIONARY location request. The stay geofence radius is a user setting (Settings.stayGeofenceRadiusM).
    // Accuracy first, battery later (dogfood-2): both modes use GPS, the mode only sets the interval.
    val stationaryPriority: LocationPriority = LocationPriority.HIGH_ACCURACY,
    val stationaryFixIntervalMs: Long = 60_000L,
    /**
     * Demotion of MOVING without displacement (a berthed boat or a parked car can keep reporting a moving activity).
     * After [noProgressFixes] consecutive accepted fixes that all stay within the stay radius times
     * [noProgressRadiusFactor] of the run's first fix (accuracy-aware: distance minus accuracy), spanning at least
     * [noProgressMinDurationMs], capture goes back to STATIONARY even if the activity still says moving.
     * The minimum duration keeps a traffic stop of a few minutes in MOVING.
     */
    val noProgressFixes: Int = 6,
    val noProgressRadiusFactor: Float = 1f,
    val noProgressMinDurationMs: Long = 5 * 60_000L,
    // MOVING location request.
    val movingPriority: LocationPriority = LocationPriority.HIGH_ACCURACY,
    val movingIntervalMs: Long = 10_000L,
    /** No batching: fixes are delivered as they arrive. */
    val movingMaxUpdateDelayMs: Long = 0L,
    /** Wait for an accurate fix instead of delivering a cached coarse one as new. */
    val waitForAccurateLocation: Boolean = true,
    /** Max age of a delivered fix; 0 means no stale cached fix is delivered as an update. */
    val maxUpdateAgeMs: Long = 0L,
    // Ping filters.
    val maxAccuracyM: Float = 100f,
    val maxSpeedMps: Float = 70f,
    /**
     * How many recent offered positions [PingFilter] remembers to recognise a repeated position. The fused provider
     * re-emits its last known position with a fresh timestamp and an inflated accuracy when it has no new reading.
     */
    val staleRepeatMemory: Int = 8,
    /**
     * Capture keeps every fix inside the accuracy and speed caps. A kept fix more than [spikeMinM] from the one before
     * it, followed by a fix back within [spikeReturnM] of that earlier one, is a spike: the spike stops being the
     * comparison point (it is still saved; segmentation decides what is noise).
     */
    val spikeMinM: Float = 500f,
    val spikeReturnM: Float = 150f,
    // Activity Recognition diagnostics.
    /** A transition event older than this when received (by its elapsed-realtime stamp) is logged but ignored. */
    val arStaleMs: Long = 5 * 60_000L,
    /** A fix at or above this speed while the last activity is STILL or unknown marks the ping's activity as inferred. */
    val movingSpeedMps: Float = 1.0f,
    /** Inferred activity is IN_VEHICLE from this speed; between [movingSpeedMps] and here the activity is left as is. */
    val vehicleSpeedMps: Float = 4.0f,
    // Window-end grace: keep a session open past the schedule end until a stay this long is confirmed.
    val windowEndSettleMs: Long = 10 * 60_000L,
    val windowEndGraceMaxMs: Long = 2 * 60 * 60_000L,
    // Health.
    val noHeartbeatThresholdMs: Long = 20 * 60_000L,
    val healthWorkerPeriodMin: Long = 15L,
    /** How long a due session may take to appear before "service not running" is reported. */
    val serviceStartGraceMs: Long = 2 * 60_000L,
    /** Health Gaps of the same cause closer together than this are merged into one. */
    val gapMergeToleranceMs: Long = 60_000L,
    // Segmentation scheduling (the algorithm's own tunables are in SegmentationParams).
    val segmentationPeriodMin: Long = 15L,
    val segmentationDailyPeriodHours: Long = 24L,
    /** Max places looked up per geocode worker run, and the base retry backoff after a failed lookup. */
    val geocodeBatchSize: Int = 20,
    val geocodeBackoffSeconds: Long = 30L,
    /** An orphan place (no name, defaults or live visits, default radius) is pruned only once it and its visits have been untouched this long. */
    val placePruneGraceMs: Long = 60 * 60_000L,
    /** Label by distance from the place to the geocoder's address point: street address within this many metres. */
    val addressExactMaxM: Double = 75.0,
    /** Within this many metres (and past [addressExactMaxM]) the label is "Near <address>"; further out the suburb only. */
    val addressNearMaxM: Double = 300.0,
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
