package me.tarry.segmentation

/** Half-open interval helpers shared by gap and trip logic. Empty ranges are dropped. */

/** Sorts and merges overlapping or touching ranges; empty ranges are dropped. */
internal fun List<TimeRange>.normalized(): List<TimeRange> {
    val out = ArrayList<TimeRange>()
    for (r in sortedBy { it.startMs }) {
        if (r.durationMs == 0L) continue
        val last = out.lastOrNull()
        if (last != null && r.startMs <= last.endMs) {
            if (r.endMs > last.endMs) out[out.lastIndex] = TimeRange(last.startMs, r.endMs)
        } else {
            out.add(r)
        }
    }
    return out
}

/** The parts of this range not covered by [others]: 0, 1 or more pieces, in time order. */
internal fun TimeRange.minus(others: List<TimeRange>): List<TimeRange> {
    val out = ArrayList<TimeRange>()
    var cursor = startMs
    for (o in others.normalized()) {
        if (o.endMs <= cursor) continue
        if (o.startMs >= endMs) break
        if (o.startMs > cursor) out.add(TimeRange(cursor, o.startMs))
        cursor = maxOf(cursor, o.endMs)
        if (cursor >= endMs) break
    }
    if (cursor < endMs) out.add(TimeRange(cursor, endMs))
    return out
}

/** The intersection with [to], or null when it is empty. */
internal fun TimeRange.clip(to: TimeRange): TimeRange? {
    val s = maxOf(startMs, to.startMs)
    val e = minOf(endMs, to.endMs)
    return if (e > s) TimeRange(s, e) else null
}
