package me.tarry.app.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.GapCause
import java.time.ZoneId

/**
 * Read-only sheet for a tapped gap block or outage cluster: what happened, when, what it means, and one "Add visit
 * here" over [slot] (the free part of the span, see [freeSlotForGap]). [outages] is the cluster's real gaps (empty for
 * a single gap).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GapSheet(
    gap: TimelineItem.Gap,
    outages: List<TimelineItem.Gap>,
    slot: Pair<Long, Long>?,
    zone: ZoneId,
    onAddVisit: (start: Long, end: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = TarryTheme.colors.paper,
    ) {
        GapSheetContent(gap, outages, slot, zone, onAddVisit)
    }
}

/** Sheet body without the sheet chrome, so it can be tested directly. */
@Composable
fun GapSheetContent(
    gap: TimelineItem.Gap,
    outages: List<TimelineItem.Gap>,
    slot: Pair<Long, Long>?,
    zone: ZoneId,
    onAddVisit: (start: Long, end: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = rememberTimeFormats()
    val isCluster = outages.isNotEmpty()
    @Composable fun range(start: Long, end: Long) = stringResource(R.string.timeline_time_range, formats.time(start, zone), formats.time(end, zone))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TarryTheme.space.gutter)
            .padding(bottom = TarryTheme.space.l)
            .testTag("gap_sheet"),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        Text(
            text = if (isCluster) {
                stringResource(R.string.gap_sheet_cluster_title)
            } else {
                stringResource(R.string.timeline_gap_row, stringResource(gap.cause.labelRes()))
            },
            style = MaterialTheme.typography.titleLarge,
            color = TarryTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(
                R.string.gap_sheet_times,
                range(gap.start, gap.end),
                if (isCluster) {
                    pluralStringResource(R.plurals.timeline_cluster_outages, outages.size, outages.size)
                } else {
                    formatDuration(gap.end - gap.start)
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
        )
        Text(
            text = stringResource(if (isCluster) R.string.gap_sheet_meaning_cluster else gap.cause.meaningRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.ink,
        )
        if (isCluster) {
            Text(
                text = stringResource(R.string.gap_sheet_outages_heading),
                style = MaterialTheme.typography.titleMedium,
                color = TarryTheme.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            outages.forEach { o ->
                Text(
                    text = stringResource(
                        R.string.gap_sheet_outage_line,
                        range(o.start, o.end),
                        stringResource(o.cause.labelRes()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TarryTheme.colors.ink,
                )
            }
        }
        if (slot == null) {
            Text(
                text = stringResource(R.string.gap_sheet_add_visit_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = TarryTheme.colors.muted,
            )
        }
        PrimaryButton(
            text = stringResource(R.string.gap_sheet_add_visit),
            onClick = { slot?.let { onAddVisit(it.first, it.second) } },
            enabled = slot != null,
            modifier = Modifier.fillMaxWidth().testTag("gap_sheet_add_visit"),
        )
    }
}

private fun GapCause.meaningRes(): Int = when (this) {
    GapCause.NO_SESSION -> R.string.gap_sheet_meaning_no_session
    GapCause.NO_FIX -> R.string.gap_sheet_meaning_no_fix
    GapCause.TRACKING_STOPPED -> R.string.gap_sheet_meaning_tracking_stopped
    GapCause.SERVICE_KILLED -> R.string.gap_sheet_meaning_service_killed
    GapCause.PERMISSION_LOST -> R.string.gap_sheet_meaning_permission_lost
    GapCause.LOCATION_OFF -> R.string.gap_sheet_meaning_location_off
}
