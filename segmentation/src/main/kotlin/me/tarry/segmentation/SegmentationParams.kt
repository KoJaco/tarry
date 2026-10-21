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
) {
    init {
        require(stayRadiusM > 0 && placeMatchRadiusM > 0) { "radii must be positive" }
        require(minStayDurationMs > 0 && signalGapMs in 0..maxGapToBridgeMs && shortTripMergeMs >= 0 && overlapMs >= 0)
        require(bridgedConfidence in 0f..confidence && confidence <= 1f)
    }
}
