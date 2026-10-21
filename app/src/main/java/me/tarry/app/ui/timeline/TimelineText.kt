package me.tarry.app.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.tarry.app.R
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

internal fun formatTime(epochMs: Long, zone: ZoneId): String =
    TimeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** An item's start and end clamped to the day, with the range texts (marked when it crosses midnight). */
internal class ClampedTimes(val start: Long, val end: Long, val range: String, val spokenRange: String) {
    val durationMs: Long get() = end - start
}

@Composable
internal fun clampedTimes(item: TimelineItem, dayStart: Long, dayEnd: Long, zone: ZoneId): ClampedTimes {
    val start = maxOf(item.start, dayStart)
    val end = minOf(item.end, dayEnd)
    val startText = formatTime(start, zone)
    val endText = formatTime(end, zone)
    val suffix = listOfNotNull(
        if (item.start < dayStart) stringResource(R.string.timeline_range_from_previous_day) else null,
        if (item.end > dayEnd) stringResource(R.string.timeline_range_continues) else null,
    ).joinToString(" ")
    fun String.withSuffix() = if (suffix.isEmpty()) this else "$this $suffix"
    return ClampedTimes(
        start,
        end,
        stringResource(R.string.timeline_time_range, startText, endText).withSuffix(),
        stringResource(R.string.timeline_spoken_range, startText, endText).withSuffix(),
    )
}

/** The full spoken description of one item; [folded] trips without a row of their own are appended. */
@Composable
internal fun spokenDescription(
    item: TimelineItem,
    dayStart: Long,
    dayEnd: Long,
    zone: ZoneId,
    folded: List<TimelineItem.Trip> = emptyList(),
): String {
    val t = clampedTimes(item, dayStart, dayEnd, zone)
    val base = when (item) {
        is TimelineItem.Visit -> {
            val place = if (item.labelIsCoordinates) {
                stringResource(R.string.timeline_unknown_place)
            } else {
                item.placeLabel ?: stringResource(R.string.timeline_unknown_place)
            }
            val job = item.jobName
            if (job != null) {
                stringResource(R.string.timeline_visit_job_description, place, t.spokenRange, spokenDuration(t.durationMs), job)
            } else {
                stringResource(R.string.timeline_visit_description, place, t.spokenRange, spokenDuration(t.durationMs))
            }
        }
        is TimelineItem.Trip -> stringResource(
            R.string.timeline_trip_description,
            stringResource(item.mode.labelRes()),
            t.spokenRange,
            formatDistance(item.distanceM),
        )
        is TimelineItem.Gap -> stringResource(
            R.string.timeline_gap_description,
            stringResource(item.cause.labelRes()),
            t.spokenRange,
        )
    }
    if (folded.isEmpty()) return base
    val extras = folded.map { stringResource(R.string.timeline_folded_trip, spokenDescription(it, dayStart, dayEnd, zone)) }
    return (listOf(base) + extras).joinToString(". ")
}

@Composable
internal fun formatDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0)
    val hours = (totalMin / 60).toInt()
    val minutes = (totalMin % 60).toInt()
    return when {
        hours > 0 && minutes > 0 -> stringResource(R.string.timeline_duration_h_min, hours, minutes)
        hours > 0 -> stringResource(R.string.timeline_duration_h, hours)
        minutes > 0 -> stringResource(R.string.timeline_duration_min, minutes)
        else -> stringResource(R.string.timeline_duration_less_than_min)
    }
}

@Composable
internal fun spokenDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0)
    val hours = (totalMin / 60).toInt()
    val minutes = (totalMin % 60).toInt()
    val lessThanMinute = stringResource(R.string.timeline_spoken_less_than_min)
    return listOfNotNull(
        if (hours > 0) pluralStringResource(R.plurals.timeline_spoken_hours, hours, hours) else null,
        if (minutes > 0) pluralStringResource(R.plurals.timeline_spoken_minutes, minutes, minutes) else null,
    ).joinToString(" ").ifEmpty { lessThanMinute }
}

@Composable
internal fun formatDistance(meters: Float): String =
    if (meters < 1000f) {
        stringResource(R.string.timeline_distance_m, meters.toInt())
    } else {
        stringResource(R.string.timeline_distance_km, meters / 1000f)
    }

internal fun TripMode.labelRes(): Int = when (this) {
    TripMode.VEHICLE -> R.string.timeline_mode_vehicle
    TripMode.WALK -> R.string.timeline_mode_walk
    TripMode.UNKNOWN -> R.string.timeline_mode_unknown
}

internal fun TripMode.rowLabelRes(): Int = when (this) {
    TripMode.VEHICLE -> R.string.timeline_trip_row_drive
    TripMode.WALK -> R.string.timeline_trip_row_walk
    TripMode.UNKNOWN -> R.string.timeline_trip_row_unknown
}

internal fun GapCause.labelRes(): Int = when (this) {
    GapCause.NO_SESSION -> R.string.timeline_gap_no_session
    GapCause.NO_FIX -> R.string.timeline_gap_no_fix
    GapCause.TRACKING_STOPPED -> R.string.timeline_gap_tracking_stopped
    GapCause.SERVICE_KILLED -> R.string.timeline_gap_service_killed
    GapCause.PERMISSION_LOST -> R.string.timeline_gap_permission_lost
    GapCause.LOCATION_OFF -> R.string.timeline_gap_location_off
}
