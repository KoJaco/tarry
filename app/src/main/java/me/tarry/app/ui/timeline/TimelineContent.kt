package me.tarry.app.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
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
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Stateless day list: date header with previous/next buttons, then visits, trips and gaps in time order. */
@Composable
fun TimelineContent(
    date: LocalDate,
    items: List<TimelineItem>,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
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
                modifier = Modifier.testTag("timeline_date"),
            )
            IconButton(onClick = onNextDay, modifier = Modifier.size(MinTouchTarget).testTag("timeline_next")) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.timeline_next_day))
            }
        }
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.timeline_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(items, key = { it.id }) { item ->
                    TimelineRow(item, zone)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineItem, zone: ZoneId) {
    val range = stringResource(R.string.timeline_time_range, formatTime(item.start, zone), formatTime(item.end, zone))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("timeline_row_${item.id}"),
    ) {
        Text(range, style = MaterialTheme.typography.titleSmall)
        when (item) {
            is TimelineItem.Visit -> {
                Text(formatDuration(item.end - item.start), style = MaterialTheme.typography.bodyMedium)
                Text(
                    item.placeLabel ?: stringResource(R.string.timeline_unknown_place),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            is TimelineItem.Trip -> {
                Text(formatDistance(item.distanceM), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(item.mode.labelRes()), style = MaterialTheme.typography.bodyLarge)
            }
            is TimelineItem.Gap -> {
                Text(stringResource(item.cause.labelRes()), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

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
        else -> stringResource(R.string.timeline_duration_min, minutes)
    }
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
