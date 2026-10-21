package me.tarry.app.ui.timeline

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import me.tarry.app.R
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The single source of timeline time text, honouring the user's 12/24-hour setting: hour labels ("09" or "9 AM") and
 * block / now-pill times ("9:12" or "9:12 AM"), in [locale]'s own pattern.
 */
internal class TimeFormats(locale: Locale, val is24Hour: Boolean) {
    private val hourFormat = formatter(locale, if (is24Hour) "HH" else "ha")
    private val timeFormat = formatter(locale, if (is24Hour) "Hm" else "hm")

    fun hour(epochMs: Long, zone: ZoneId): String = hourFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun time(epochMs: Long, zone: ZoneId): String = timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    private fun formatter(locale: Locale, skeleton: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

/** Formats for the current locale and 24-hour setting; rebuilt when either (or the configuration) changes. */
@Composable
internal fun rememberTimeFormats(): TimeFormats {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    return remember(context, configuration, locale) { TimeFormats(locale, DateFormat.is24HourFormat(context)) }
}

/** An item's start and end clamped to the day, with the range texts (marked when it crosses midnight). */
internal class ClampedTimes(val start: Long, val end: Long, val range: String, val spokenRange: String) {
    val durationMs: Long get() = end - start
}

@Composable
internal fun clampedTimes(item: TimelineItem, dayStart: Long, dayEnd: Long, zone: ZoneId): ClampedTimes {
    val start = maxOf(item.start, dayStart)
    val end = minOf(item.end, dayEnd)
    val formats = rememberTimeFormats()
    val startText = formats.time(start, zone)
    val endText = formats.time(end, zone)
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
    innerGaps: List<InnerGap> = emptyList(),
): String {
    val t = clampedTimes(item, dayStart, dayEnd, zone)
    val notTracked = if (innerGaps.isEmpty()) {
        null
    } else {
        // Each gap clamped to the visit and the day, like the band drawn for it.
        val ms = innerGaps.sumOf { minOf(it.gap.end, t.end) - maxOf(it.gap.start, t.start) }.coerceAtLeast(0)
        val minutes = (ms / 60_000L).toInt()
        if (minutes < 1) {
            stringResource(R.string.timeline_includes_not_tracked_short)
        } else {
            pluralStringResource(R.plurals.timeline_includes_not_tracked, minutes, minutes)
        }
    }
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
    }.let { if (notTracked != null) "$it, $notTracked" else it }
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
