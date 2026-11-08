package me.tarry.app.ui.drawer

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The instant at [hour]:[minute] in [zone] that a time picker's time of day means for a segment spanning
 * `[rangeStartMs, rangeEndMs]` (default: just the anchor). A time of day is resolved on the segment's own local dates,
 * the capture zone's, and the one closest to [anchorMs] wins. So an edit never lands on the day before or after a
 * segment that sits inside one day; only a segment that already crosses midnight offers both dates, which lets it be
 * trimmed on either side of midnight.
 */
internal fun instantNear(
    hour: Int,
    minute: Int,
    zone: ZoneId,
    anchorMs: Long,
    rangeStartMs: Long = anchorMs,
    rangeEndMs: Long = anchorMs,
): Long {
    val low = minOf(rangeStartMs, rangeEndMs, anchorMs)
    val high = maxOf(rangeStartMs, rangeEndMs, anchorMs)
    val firstDate = Instant.ofEpochMilli(low).atZone(zone).toLocalDate()
    // An end exactly at local midnight belongs to the day it closes, not the next one.
    val lastDate = Instant.ofEpochMilli(if (high > low) maxOf(low, high - 1) else high).atZone(zone).toLocalDate()
    val anchorDate = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
    val dates = generateSequence(firstDate) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDate) }.toList().ifEmpty { listOf(anchorDate) }
    return dates
        .map { it.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli() }
        .minBy { abs(it - anchorMs) }
}
