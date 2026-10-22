package me.tarry.segmentation

/**
 * Every segmentation tunable (AGENTS rule 5). Defaults are the MVP spec table (docs/specs/tarry-mvp.md
 * §Segmentation); changing one needs a golden-case update (rule 7).
 */
data class SegmentationParams(
    /** Max distance of a ping from the stay's running accuracy-weighted centroid. */
    val stayRadiusM: Double = 60.0,
    /** Shorter stops are part of the trip. */
    val minStayDurationMs: Long = 5 * 60_000L,
    /** A ping gap inside one stay is bridged when the position on both sides is within [stayRadiusM]. */
    val maxGapToBridgeMs: Long = 45 * 60_000L,
    /**
     * A silence inside a stay longer than this counts as a signal gap that was bridged (lower confidence). Capture
     * saves a stationary keepalive every 10 min plus a 3 min fix interval, so normal stays stay under it.
     */
    val signalGapMs: Long = 15 * 60_000L,
    /** Radius of a newly created place; existing places match within their own radius. */
    val placeMatchRadiusM: Double = 75.0,
    /** A move shorter than this between two stays merges them. */
    val minTripDistanceM: Double = 150.0,
    /** Stays at the same place separated by a shorter blip are merged. */
    val shortTripMergeMs: Long = 3 * 60_000L,
    /** How far before the checkpoint each run reloads. Used by the `:app` glue, not by [Segmenter]. */
    val overlapMs: Long = 30 * 60_000L,
    /** Confidence of a stay with no bridged gap, and of one bridged across a signal gap. */
    val confidence: Float = 1.0f,
    val bridgedConfidence: Float = 0.6f,
    /** Accuracy floor when weighting pings, so one very confident fix cannot dominate a centroid. */
    val minWeightAccuracyM: Double = 5.0,
    /** Gap pieces shorter than this are display noise and dropped. */
    val minGapMs: Long = 60_000L,
    /** A matched place's centroid is only rewritten when it moves further than this. Used by the `:app` glue. */
    val placeMoveThresholdM: Double = 1.0,
    /**
     * A stay closes only after at least this many consecutive pings clearly outside it
     * (`distance - accuracy > stayRadiusM`) that also span [departureMinMs]. Fewer is an outlier and is ignored.
     */
    val departureMinFixes: Int = 2,
    /** The consecutive outside pings that close a stay must span at least this long. */
    val departureMinMs: Long = 2 * 60_000L,
    /** Pings at least this accurate (metres, smaller is better) are trusted for displacement and distance. */
    val goodAccuracyM: Double = 50.0,
    /**
     * Movement within this distance of a stay's centroid, between the vehicle stopping (or starting) and the stay,
     * belongs to the visit. On foot movement beyond it becomes its own WALK trip leg.
     */
    val arrivalRadiusM: Double = 200.0,
    /**
     * A ping with the same lat/lng as an earlier ping and accuracy at or above this (metres) is a stale repeat: it
     * carries no position, so it never starts, extends or moves a stay. It still counts as tracking (no gap).
     * Matches the capture filter's maximum accepted accuracy.
     */
    val maxAccuracyM: Float = 100f,
) {
    init {
        require(stayRadiusM > 0 && placeMatchRadiusM > 0) { "radii must be positive" }
        require(minStayDurationMs > 0 && signalGapMs in 0..maxGapToBridgeMs && shortTripMergeMs >= 0 && overlapMs >= 0)
        require(departureMinFixes >= 1 && departureMinMs >= 0 && goodAccuracyM > 0 && arrivalRadiusM > 0)
        require(bridgedConfidence in 0f..confidence && confidence <= 1f)
    }
}
