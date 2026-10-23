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
    /** A vehicle-to-stay tail only becomes a WALK leg when its average speed is at most this (m/s). */
    val walkMaxSpeedMps: Double = 2.5,
    /**
     * Trip distance counts a hop at least this fast (m/s) when it is longer than the worse of the two fixes'
     * accuracies, so curves are not cut into chords. Other hops count only when longer than the combined accuracy.
     */
    val movingSpeedMps: Double = 5.0,
    /**
     * Trip distance counts a hop only where the path is going somewhere: over a window of a few fixes around it, the
     * straight-line displacement must be at least this fraction of the path length. Jitter at one spot (a stop with
     * poor fixes) fails it however fast or accurate the hops look; driving, curves included, passes.
     */
    val movingMinEfficiency: Double = 0.35,
    /**
     * Two known places whose centroids are closer than this many [stayRadiusM] (or within each other's radius) are
     * indistinguishable by GPS: a stay is never split between them and stays matched to them merge. Tight places
     * (radius below [stayRadiusM]) are exempt: the user made them small to separate neighbours.
     */
    val indistinguishablePlaceFactor: Double = 2.0,
    /** A poor fix further than this from both neighbours, which agree with each other, is a spike and is dropped. */
    val spikeMinM: Double = 500.0,
    /** A spike (one fix or a few in a row) lasts at most this long before the phone is back. */
    val spikeMaxRunMs: Long = 3 * 60_000L,
    /** Slack added to the neighbours' combined accuracy when deciding they agree (the phone came back). */
    val spikeAgreeSlackM: Double = 30.0,
    /** A hop faster than this (m/s) is not physically plausible; a fix reached and left that fast is a spike. */
    val maxPlausibleSpeedMps: Double = 45.0,
    /** Fixes within [anchorToleranceM] of each other, this many or more inside [anchorWindowMs], are an anchor. */
    val anchorMinRepeats: Int = 4,
    /** Distance within which fixes count as the same anchor (0 would be exact coordinates only). */
    val anchorToleranceM: Double = 15.0,
    /** Window (centred on the fix) over which anchors are counted and compared. */
    val anchorWindowMs: Long = 30 * 60_000L,
    /** The phone is flipping between anchors only when the anchors in the window change place this often. */
    val anchorMinSwitches: Int = 4,
    /** Anchors further apart than this are different sites, not one jumping field, and do not raise accuracy. */
    val anchorSpreadMaxM: Double = 600.0,
    /** Poor fixes (worse than [goodAccuracyM]) are replaced by the weighted geometric median of fixes within this. */
    val smoothWindowMs: Long = 3 * 60_000L,
    /** A poor fix is smoothed only with at least this many poor fixes around it (reaching up to twice the window). */
    val smoothMinFixes: Int = 5,
    /**
     * A departure's closing fixes either agree with each other or each moves further from the centroid than the one
     * before by at least this (metres of slack for noise). Used by stay detection.
     */
    val departureProgressM: Double = 10.0,
    /** A stay's noise scale is this times its robust spread; it sets new place radii and matching. */
    val placeNoiseFactor: Double = 4.0,
    /** Upper bound on any noise-scaled radius. */
    val placeMaxRadiusM: Double = 300.0,
    /** Net displacement between stays uses this quantile of the fixes in between, not the single furthest fix. */
    val robustDisplacementQuantile: Double = 0.8,
) {
    init {
        require(stayRadiusM > 0 && placeMatchRadiusM > 0) { "radii must be positive" }
        require(minStayDurationMs > 0 && signalGapMs in 0..maxGapToBridgeMs && shortTripMergeMs >= 0 && overlapMs >= 0)
        require(departureMinFixes >= 1 && departureMinMs >= 0 && goodAccuracyM > 0 && arrivalRadiusM > 0 && indistinguishablePlaceFactor >= 0 && walkMaxSpeedMps > 0 && movingSpeedMps > 0)
        require(spikeMinM > 0 && spikeMaxRunMs >= 0 && maxPlausibleSpeedMps > 0 && anchorMinRepeats >= 1 && anchorWindowMs > 0)
        require(smoothWindowMs >= 0 && smoothMinFixes >= 1 && placeNoiseFactor >= 0 && placeMaxRadiusM > 0)
        require(robustDisplacementQuantile in 0.0..1.0)
        require(bridgedConfidence in 0f..confidence && confidence <= 1f)
    }
}
