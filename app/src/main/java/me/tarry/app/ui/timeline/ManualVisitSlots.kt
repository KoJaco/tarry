package me.tarry.app.ui.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Default start of the "+" slot on a day with no visits (local time). */
internal const val DefaultSlotStartHour = 9

/**
 * The span a gap sheet's "Add visit here" prefills: `[start, end)` minus every visit in [visits], then the longest
 * piece left. For an ordinary gap that is the gap clamped to the visits either side; for an outage cluster it skips the
 * short visits inside it, because a manual visit may not overlap a live one. Null when nothing is free.
 */
internal fun freeSlotForGap(start: Long, end: Long, visits: List<TimelineItem.Visit>): Pair<Long, Long>? {
    var best: Pair<Long, Long>? = null
    var cursor = start
    fun consider(from: Long, to: Long) {
        if (to > from && (best == null || to - from > best!!.second - best!!.first)) best = from to to
    }
    for (v in visits.sortedBy { it.start }) {
        if (v.end <= cursor) continue
        if (v.start >= end) break
        consider(cursor, minOf(v.start, end))
        cursor = maxOf(cursor, v.end)
    }
    consider(cursor, end)
    return best
}

/**
 * The "+" button's default slot on [date]: 9:00-10:00 local when the day has no visit, otherwise the first whole hour at
 * or after the end of the last visit (one hour long), kept inside the day. Local wall-clock hours, so DST days work.
 */
internal fun firstFreeSlot(date: LocalDate, zone: ZoneId, visits: List<TimelineItem.Visit>): Pair<Long, Long> {
    val (dayStart, dayEnd) = dayBounds(date, zone)
    val lastEnd = visits.maxOfOrNull { it.end.coerceIn(dayStart, dayEnd) }
    var start = if (lastEnd == null) {
        date.atTime(DefaultSlotStartHour, 0).atZone(zone)
    } else {
        val floor = Instant.ofEpochMilli(lastEnd).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        if (floor.toInstant().toEpochMilli() == lastEnd) floor else floor.plusHours(1)
    }
    val lastHourStart = Instant.ofEpochMilli(dayEnd).atZone(zone).minusHours(1)
    if (start.toInstant().isAfter(lastHourStart.toInstant())) start = lastHourStart.truncatedTo(ChronoUnit.HOURS)
    val end = start.plusHours(1).toInstant().toEpochMilli().coerceAtMost(dayEnd)
    return start.toInstant().toEpochMilli() to end
}
