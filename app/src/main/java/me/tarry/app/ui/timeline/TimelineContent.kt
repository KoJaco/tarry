package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val MinTouchTarget = 48.dp

/**
 * Stateless day list: date header with previous/next buttons, then visits, trips and gaps in time order.
 * [items] is null while loading, so an empty day is only shown once it is known to be empty.
 */
@Composable
fun TimelineContent(
    date: LocalDate,
    items: List<TimelineItem>?,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
    nextEnabled: Boolean = true,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPreviousDay, modifier = Modifier.size(MinTouchTarget).testTag("timeline_previous")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.timeline_previous_day))
            }
            Text(
                text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    }
                    .testTag("timeline_date"),
            )
            IconButton(
                onClick = onNextDay,
                enabled = nextEnabled,
                modifier = Modifier.size(MinTouchTarget).testTag("timeline_next"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.timeline_next_day))
            }
        }
        when {
            items == null -> {
                val loading = stringResource(R.string.timeline_loading)
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = loading }.testTag("timeline_loading"),
                    )
                }
            }
            items.isEmpty() -> Text(
                text = stringResource(R.string.timeline_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                val (dayStart, dayEnd) = dayBounds(date, zone)
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        TimelineRow(item, dayStart, dayEnd, zone)
                        if (index < items.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, dayStart: Long, dayEnd: Long, zone: ZoneId) {
    // Clamp to the day; a row that crosses midnight says so.
    val start = maxOf(item.start, dayStart)
    val end = minOf(item.end, dayEnd)
    val fromPrevious = item.start < dayStart
    val continues = item.end > dayEnd
    val startText = formatTime(start, zone)
    val endText = formatTime(end, zone)
    val suffix = listOfNotNull(
        if (fromPrevious) stringResource(R.string.timeline_range_from_previous_day) else null,
        if (continues) stringResource(R.string.timeline_range_continues) else null,
    ).joinToString(" ")
    val range = stringResource(R.string.timeline_time_range, startText, endText).withSuffix(suffix)
    val spokenRange = stringResource(R.string.timeline_spoken_range, startText, endText).withSuffix(suffix)
    val durationMs = end - start

    val modeLabel = (item as? TimelineItem.Trip)?.let { stringResource(it.mode.labelRes()) }
    val gapLabel = (item as? TimelineItem.Gap)?.let { stringResource(it.cause.labelRes()) }
    val distance = (item as? TimelineItem.Trip)?.let { formatDistance(it.distanceM) }
    val unknownPlace = stringResource(R.string.timeline_unknown_place)
    val spokenDuration = spokenDuration(durationMs)
    val description = when (item) {
        is TimelineItem.Visit -> stringResource(
            R.string.timeline_visit_description,
            if (item.labelIsCoordinates) unknownPlace else item.placeLabel ?: unknownPlace,
            spokenRange,
            spokenDuration,
        )
        is TimelineItem.Trip -> stringResource(R.string.timeline_trip_description, modeLabel!!, spokenRange, distance!!)
        is TimelineItem.Gap -> stringResource(R.string.timeline_gap_description, gapLabel!!, spokenRange)
    }

    val isGap = item is TimelineItem.Gap
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (isGap) {
                    Modifier.background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(8.dp)
                } else {
                    Modifier.padding(vertical = 4.dp)
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("timeline_row_${item.id}"),
    ) {
        Text(range, style = MaterialTheme.typography.titleSmall)
        when (item) {
            is TimelineItem.Visit -> {
                Text(formatDuration(durationMs), style = MaterialTheme.typography.bodyMedium)
                Text(item.placeLabel ?: unknownPlace, style = MaterialTheme.typography.bodyLarge)
            }
            is TimelineItem.Trip -> {
                Text(distance!!, style = MaterialTheme.typography.bodyMedium)
                Text(modeLabel!!, style = MaterialTheme.typography.bodyLarge)
            }
            is TimelineItem.Gap -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Decorative: the row description already says "Tracking gap".
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        gapLabel!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

private fun String.withSuffix(suffix: String): String = if (suffix.isEmpty()) this else "$this $suffix"

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun formatTime(epochMs: Long, zone: ZoneId): String =
    TimeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

@Composable
private fun formatDuration(ms: Long): String {
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
private fun spokenDuration(ms: Long): String {
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
private fun formatDistance(meters: Float): String =
    if (meters < 1000f) {
        stringResource(R.string.timeline_distance_m, meters.toInt())
    } else {
        stringResource(R.string.timeline_distance_km, meters / 1000f)
    }

private fun TripMode.labelRes(): Int = when (this) {
    TripMode.VEHICLE -> R.string.timeline_mode_vehicle
    TripMode.WALK -> R.string.timeline_mode_walk
    TripMode.UNKNOWN -> R.string.timeline_mode_unknown
}

private fun GapCause.labelRes(): Int = when (this) {
    GapCause.NO_SESSION -> R.string.timeline_gap_no_session
    GapCause.NO_FIX -> R.string.timeline_gap_no_fix
    GapCause.TRACKING_STOPPED -> R.string.timeline_gap_tracking_stopped
    GapCause.SERVICE_KILLED -> R.string.timeline_gap_service_killed
    GapCause.PERMISSION_LOST -> R.string.timeline_gap_permission_lost
    GapCause.LOCATION_OFF -> R.string.timeline_gap_location_off
}
