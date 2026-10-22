package me.tarry.app.ui.drawer

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The instant at [hour]:[minute] in [zone] on the day (the anchor's day, the day before or the day after) that lies
 * closest to [anchorMs]. A time picker only returns a time of day; this picks the date, so a visit that crosses
 * midnight can be trimmed on either side of it.
 */
internal fun instantNear(hour: Int, minute: Int, zone: ZoneId, anchorMs: Long): Long {
    val anchorDate = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
    return (-1L..1L)
        .map { anchorDate.plusDays(it).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli() }
        .minBy { abs(it - anchorMs) }
}
