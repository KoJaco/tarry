package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters

/**
 * Segmentation-owned gaps. NO_SESSION is scheduled-window time with no capture session; NO_FIX is a
 * silence longer than [SegmentationParams.maxGapToBridgeMs] inside a session, or an unrecorded move (see
 * [addUnrecordedMoves]). [pings] are the run's pings after excluded ranges were dropped, in time order; [fixes] are
 * the positional fixes the move check may trust (the cleaned pings: spikes removed), in time order. Candidates are
 * clipped to [SegmentationInput.range] and never overlap health gaps, frozen ranges or excluded ranges. The result is
 * in time order and non-overlapping.
 */
internal fun buildGaps(
    input: SegmentationInput,
    pings: List<Ping>,
    params: SegmentationParams,
    fixes: List<Ping> = pings,
): List<Gap> {
    val sessions = input.sessions.normalized()
    val candidates = ArrayList<Gap>()

    for (w in input.windows.normalized()) {
        for (piece in w.minus(sessions)) candidates.add(Gap(piece, GapCause.NO_SESSION))
    }

    // Silence is measured within each part of a session outside excluded ranges: an excluded range is time the
    // user removed, so it neither is a silence nor joins the silences either side of it into one.
    val parts = sessions.flatMap { it.minus(input.excluded) }
    for (s in parts) {
        var prev = s.startMs
        for (p in pings) {
            if (p.timeMs !in s) continue
            addSilence(candidates, prev, p.timeMs, params)
            prev = p.timeMs
        }
        addSilence(candidates, prev, s.endMs, params)
    }
    addUnrecordedMoves(candidates, parts, fixes, params)

    // Excluded ranges are deleted visits: the user removed that time, it is not a signal gap.
    val blocked = (input.healthGaps + input.frozen + input.excluded).normalized()
    val result = ArrayList<Gap>()
    for (c in candidates.sortedBy { it.range.startMs }) {
        val clipped = c.range.clip(input.range) ?: continue
        for (piece in clipped.minus(blocked)) {
            if (piece.durationMs >= params.minGapMs) result.add(Gap(piece, c.cause))
        }
    }
    return result
}

private fun addSilence(out: MutableList<Gap>, from: Long, to: Long, params: SegmentationParams) {
    if (to - from > params.maxGapToBridgeMs) out.add(Gap(TimeRange(from, to), GapCause.NO_FIX))
}

/**
 * A shorter silence is lost capture too when the phone moved during it: consecutive good fixes (accuracy within
 * [SegmentationParams.goodAccuracyM]) more than [SegmentationParams.unrecordedMoveMinMs] apart whose distance, less
 * both accuracies, exceeds [SegmentationParams.minTripDistanceM] (24 Oct 2026: a 12-minute drive with no fixes).
 * Poor fixes in between neither record the move nor break the silence. Only time inside one session [parts] piece
 * counts, and time an existing NO_FIX silence already covers is not added again.
 */
private fun addUnrecordedMoves(
    out: MutableList<Gap>,
    parts: List<TimeRange>,
    fixes: List<Ping>,
    params: SegmentationParams,
) {
    val silences = out.filter { it.cause == GapCause.NO_FIX }.map { it.range }
    for (s in parts) {
        var prev: Ping? = null
        for (p in fixes) {
            if (p.timeMs !in s || p.accuracyM > params.goodAccuracyM) continue
            val a = prev
            prev = p
            if (a == null || p.timeMs - a.timeMs <= params.unrecordedMoveMinMs) continue
            val moved = haversineMeters(a.lat, a.lng, p.lat, p.lng) - a.accuracyM - p.accuracyM
            if (moved <= params.minTripDistanceM) continue
            for (piece in TimeRange(a.timeMs, p.timeMs).minus(silences)) out.add(Gap(piece, GapCause.NO_FIX))
        }
    }
}
