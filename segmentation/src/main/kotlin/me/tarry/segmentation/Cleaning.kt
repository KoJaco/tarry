package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The pre-pass before stay detection (docs/specs/fix-dogfood-2.md B2): turns the raw positional pings into the
 * stream the detectors see. Pings are only removed as rejected spikes; positions and accuracies of poor fixes may
 * change.
 *
 * 1. [anchorAccuracies] raises the accuracy of fixes that repeat at a few alternating places (network anchors), so
 *    the later steps know how far those fixes can be trusted.
 * 2. [rejectSpikes] drops out-and-back fixes.
 * 3. [smoothPoorFixes] replaces the position of fixes worse than [SegmentationParams.goodAccuracyM] by a windowed
 *    weighted geometric median. Good fixes pass through unchanged, so real GPS walks and trips survive.
 */
internal fun cleanPings(pings: List<Ping>, params: SegmentationParams): CleanedPings {
    if (pings.size < 3) return CleanedPings(pings, pings)
    val accuracy = anchorAccuracies(pings, params)
    val raised = pings.mapIndexed { i, p -> if (accuracy[i] != p.accuracyM) p.copy(accuracyM = accuracy[i]) else p }
    val kept = rejectSpikes(raised, params)
    return CleanedPings(kept, smoothPoorFixes(kept, params))
}

/**
 * The pre-pass result. [pings] keep their original positions (accuracies possibly raised, spikes removed): stay and
 * place centroids, merging, arrivals and trip distances use them. [detection] is the same fixes, same order, with poor
 * positions smoothed; it is only what [detectStays] reads to decide where stays start and end.
 */
internal class CleanedPings(val pings: List<Ping>, val detection: List<Ping>) {
    /** Puts the original positions back into stays detected on [detection] and re-derives their centroids. */
    fun restore(stays: List<StayCandidate>, params: SegmentationParams): List<StayCandidate> {
        if (detection === pings) return stays
        val original = java.util.IdentityHashMap<Ping, Ping>()
        for (i in pings.indices) original[detection[i]] = pings[i]
        return stays.map { s ->
            val own = s.pings.map { original[it] ?: it }
            val (lat, lng) = weightedCentroid(own, params)
            s.copy(lat = lat, lng = lng, pings = own)
        }
    }
}

private fun dist(a: Ping, b: Ping) = haversineMeters(a.lat, a.lng, b.lat, b.lng)

/**
 * True when [x] cannot be where [ref] says the phone was nearby: a poor fix further than
 * [SegmentationParams.spikeMinM], or one the phone could only reach faster than
 * [SegmentationParams.maxPlausibleSpeedMps].
 */
private fun far(x: Ping, ref: Ping, params: SegmentationParams): Boolean {
    val d = dist(x, ref)
    if (x.accuracyM > params.goodAccuracyM && d > params.spikeMinM) return true
    val dtS = max(abs(x.timeMs - ref.timeMs), 1_000L) / 1000.0
    return d > params.stayRadiusM + x.accuracyM + ref.accuracyM && d / dtS > params.maxPlausibleSpeedMps
}

/**
 * Drops runs of fixes (at most [SegmentationParams.spikeMaxRunMs] long) that are far from the last kept fix before
 * them and from the first fix after them, when those two neighbours agree with each other within their combined
 * accuracy (plus [SegmentationParams.spikeAgreeSlackM]): the phone went out and came straight back, so it was a
 * position error. A departure never qualifies, because the fix after it does not return.
 */
internal fun rejectSpikes(pings: List<Ping>, params: SegmentationParams): List<Ping> {
    val kept = ArrayList<Ping>(pings.size)
    var i = 0
    while (i < pings.size) {
        val x = pings[i]
        val p = kept.lastOrNull()
        if (p == null || !far(x, p, params)) {
            kept += x
            i++
            continue
        }
        var j = i
        while (j < pings.size && pings[j].timeMs - x.timeMs <= params.spikeMaxRunMs && far(pings[j], p, params)) j++
        val n = pings.getOrNull(j)
        val isSpike = n != null &&
            n.timeMs - pings[j - 1].timeMs <= params.spikeMaxRunMs &&
            dist(p, n) <= p.accuracyM + n.accuracyM + params.spikeAgreeSlackM &&
            (i until j).all { far(pings[it], n, params) }
        if (isSpike) {
            i = j
        } else {
            kept += x
            i++
        }
    }
    return kept
}

/**
 * Effective accuracy per fix (same order as [pings]). A fix is an anchor observation when it is poor (worse than
 * [SegmentationParams.goodAccuracyM]) and at least [SegmentationParams.anchorMinRepeats] poor fixes (itself included) lie within [SegmentationParams.anchorToleranceM] of
 * it inside a [SegmentationParams.anchorWindowMs] window centred on it. When the anchors in that window switch
 * between places at least [SegmentationParams.anchorMinSwitches] times (the phone is flipping between a few cached
 * network positions) the fix's accuracy becomes at least the distance to the furthest anchor within
 * [SegmentationParams.anchorSpreadMaxM]. A single anchor, or a walk from one anchor to another, is untouched.
 */
internal fun anchorAccuracies(pings: List<Ping>, params: SegmentationParams): FloatArray {
    val n = pings.size
    val acc = FloatArray(n) { pings[it].accuracyM }
    val half = params.anchorWindowMs / 2
    val first = pings.first().timeMs
    val last = pings.last().timeMs
    val lo = IntArray(n)
    val hi = IntArray(n) // exclusive
    var a = 0
    var b = 0
    for (i in 0 until n) {
        // Centred on the fix, but at the edge of the stream shifted inward so the window keeps its full width: a
        // half-empty window would count too few repeats and leave edge anchors unrecognised.
        var from = pings[i].timeMs - half
        var to = pings[i].timeMs + half
        if (from < first) {
            to += first - from
            from = first
        }
        if (to > last) {
            from = maxOf(first, from - (to - last))
            to = last
        }
        while (pings[a].timeMs < from) a++
        if (b < i) b = i
        while (b < n && pings[b].timeMs <= to) b++
        lo[i] = a
        hi[i] = b
    }
    // Only a poor fix can be an anchor: a still phone on good GPS jitters within the tolerance too, but its fixes are
    // right and must not be doubted. The neighbours counted are poor as well.
    val poor = BooleanArray(n) { pings[it].accuracyM > params.goodAccuracyM }
    val anchor = BooleanArray(n) { i ->
        var count = 0
        if (poor[i]) {
            for (j in lo[i] until hi[i]) if (poor[j] && dist(pings[i], pings[j]) <= params.anchorToleranceM) count++
        }
        count >= params.anchorMinRepeats
    }
    if (anchor.none { it }) return acc
    // Leader clustering of anchor fixes: a fix joins the first earlier anchor place within the tolerance.
    val leaders = ArrayList<Ping>()
    val label = IntArray(n) { -1 }
    for (i in 0 until n) {
        if (!anchor[i]) continue
        var k = leaders.indexOfFirst { dist(it, pings[i]) <= params.anchorToleranceM }
        if (k < 0) {
            leaders += pings[i]
            k = leaders.lastIndex
        }
        label[i] = k
    }
    for (i in 0 until n) {
        if (!anchor[i]) continue
        var switches = 0
        var spread = 0.0
        var prev = -1
        for (j in lo[i] until hi[i]) {
            if (!anchor[j]) continue
            if (prev >= 0 && label[j] != prev) switches++
            prev = label[j]
            val d = dist(pings[i], pings[j])
            if (d <= params.anchorSpreadMaxM) spread = max(spread, d)
        }
        if (switches >= params.anchorMinSwitches) acc[i] = max(acc[i], spread.toFloat())
    }
    return acc
}

/**
 * Replaces the position of every fix whose effective accuracy is worse than [SegmentationParams.goodAccuracyM] by the
 * weighted geometric median of the poor fixes within [SegmentationParams.smoothWindowMs] of it. Good fixes neither
 * move nor take part: they would dominate the median and drag a poor fix beside a drive back along the road.
 */
internal fun smoothPoorFixes(pings: List<Ping>, params: SegmentationParams): List<Ping> {
    val n = pings.size
    val poor = (0 until n).filter { pings[it].accuracyM > params.goodAccuracyM }
    val poorPings = poor.map { pings[it] }
    val poorAccuracy = FloatArray(poor.size) { poorPings[it].accuracyM }
    val smoothed = HashMap<Int, Pair<Double, Double>>()
    var a = 0
    var b = 0
    for (k in poor.indices) {
        val t = poorPings[k].timeMs
        while (poorPings[a].timeMs < t - params.smoothWindowMs) a++
        if (b < k) b = k
        while (b < poor.size && poorPings[b].timeMs <= t + params.smoothWindowMs) b++
        // At the edge of the stream the window is lopsided and a median of two or three fixes is noise: reach inward,
        // but never further than twice the window.
        var from = a
        var to = b
        val reach = 2 * params.smoothWindowMs
        while (to - from < params.smoothMinFixes) {
            if (from > 0 && t - poorPings[from - 1].timeMs <= reach) from--
            else if (to < poor.size && poorPings[to].timeMs - t <= reach) to++
            else break
        }
        if (to - from >= params.smoothMinFixes) smoothed[poor[k]] = geometricMedian(poorPings, poorAccuracy, from, to, params)
    }
    return pings.mapIndexed { i, p -> smoothed[i]?.let { p.copy(lat = it.first, lng = it.second) } ?: p }
}

/** Accuracy-weighted geometric median (Weiszfeld) of `pings[from until to]` in a local flat frame. */
private fun geometricMedian(
    pings: List<Ping>,
    accuracy: FloatArray,
    from: Int,
    to: Int,
    params: SegmentationParams,
): Pair<Double, Double> {
    val lat0 = pings[from].lat
    val lng0 = pings[from].lng
    val mLat = 111_194.9
    val mLng = mLat * cos(Math.toRadians(lat0))
    val count = to - from
    val xs = DoubleArray(count) { (pings[from + it].lng - lng0) * mLng }
    val ys = DoubleArray(count) { (pings[from + it].lat - lat0) * mLat }
    val ws = DoubleArray(count) {
        val acc = max(accuracy[from + it].toDouble(), params.minWeightAccuracyM)
        1.0 / (acc * acc)
    }
    var wSum = 0.0
    var x = 0.0
    var y = 0.0
    for (k in 0 until count) {
        wSum += ws[k]
        x += xs[k] * ws[k]
        y += ys[k] * ws[k]
    }
    x /= wSum
    y /= wSum
    repeat(MEDIAN_ITERATIONS) {
        var nx = 0.0
        var ny = 0.0
        var denom = 0.0
        for (k in 0 until count) {
            val d = max(sqrt((xs[k] - x) * (xs[k] - x) + (ys[k] - y) * (ys[k] - y)), 1.0)
            val w = ws[k] / d
            nx += xs[k] * w
            ny += ys[k] * w
            denom += w
        }
        nx /= denom
        ny /= denom
        val moved = abs(nx - x) + abs(ny - y)
        x = nx
        y = ny
        if (moved < 0.5) return lat0 + y / mLat to lng0 + x / mLng
    }
    return lat0 + y / mLat to lng0 + x / mLng
}

private const val MEDIAN_ITERATIONS = 30
