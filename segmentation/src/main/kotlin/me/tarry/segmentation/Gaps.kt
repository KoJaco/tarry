package me.tarry.segmentation

/** Pieces shorter than this are display noise and dropped. */
private const val MIN_GAP_MS = 60_000L

/**
 * Segmentation-owned gaps. NO_SESSION is scheduled-window time with no capture session; NO_FIX is a
 * silence longer than [SegmentationParams.maxGapToBridgeMs] inside a session. [pings] are the run's
 * pings after excluded ranges were dropped, in time order. Candidates are clipped to [SegmentationInput.range]
 * and never overlap health gaps or frozen ranges. The result is in time order and non-overlapping.
 */
internal fun buildGaps(input: SegmentationInput, pings: List<Ping>, params: SegmentationParams): List<Gap> {
    val sessions = input.sessions.normalized()
    val candidates = ArrayList<Gap>()

    for (w in input.windows.normalized()) {
        for (piece in w.minus(sessions)) candidates.add(Gap(piece, GapCause.NO_SESSION))
    }

    for (s in sessions) {
        var prev = s.startMs
        for (p in pings) {
            if (p.timeMs !in s) continue
            addSilence(candidates, prev, p.timeMs, params)
            prev = p.timeMs
        }
        addSilence(candidates, prev, s.endMs, params)
    }

    val blocked = (input.healthGaps + input.frozen).normalized()
    val result = ArrayList<Gap>()
    for (c in candidates.sortedBy { it.range.startMs }) {
        val clipped = c.range.clip(input.range) ?: continue
        for (piece in clipped.minus(blocked)) {
            if (piece.durationMs >= MIN_GAP_MS) result.add(Gap(piece, c.cause))
        }
    }
    return result
}

private fun addSilence(out: MutableList<Gap>, from: Long, to: Long, params: SegmentationParams) {
    if (to - from > params.maxGapToBridgeMs) out.add(Gap(TimeRange(from, to), GapCause.NO_FIX))
}
