package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import java.time.ZoneId

/** How the width right of the time column is shared between the visits-and-trips column and the job column. */
internal object DayColumns {
    /** Share of the width right of the time column that the job column gets; visits and trips get the rest less [Gap]. */
    const val JobFraction = 0.4f

    /** Space between the visits-and-trips column and the job column. */
    val Gap = 8.dp

    /** Least share of the job column at large font sizes, where the visits column needs the room for its text. */
    const val MinJobFraction = 0.2f

    /** [JobFraction] at font scale 1, shrinking in proportion to the font scale (never below [MinJobFraction]). */
    fun jobFraction(fontScale: Float): Float = (JobFraction / maxOf(1f, fontScale)).coerceAtLeast(MinJobFraction)
}

private object JobColumnDefaults {
    /** Space between side-by-side lanes. */
    val LaneGap = 2.dp
    val BorderWidth = 1.5.dp

    /** Least height and width of a block's touch target; a shorter block is still easy to hit. */
    val Touch = 48.dp
    val PaddingH = 6.dp
    val PaddingV = 4.dp

    /** The billable mark: a filled dot when billable, a ring when not. */
    val MarkSize = 8.dp
    val MarkRing = 1.5.dp

    /** Below these heights (per unit of font scale) a block has no room for its duration line / its billable line. */
    const val DurationMinDp = 56f
    const val BillableMinDp = 84f

    /** Alpha of the range being drawn by a drag, over the card. */
    const val PreviewFillAlpha = 0.25f
}

/** Test tag of the job column (the area a long press and drag draws a new job on). */
internal const val JobColumnTag = "timeline_job_column"

private const val MsPerHour = 3_600_000f

/**
 * The job column of the day canvas: job blocks (side by side in lanes), suggestions (dashed, tap to confirm) and the
 * range preview of a drag. Aligned to the end of the canvas, [width] wide, full height; positions come from [layout].
 *
 * Drag to create: long-press empty space, then drag vertically; the range is snapped to
 * [JobBlockConfig.snapMinutes] and, on release, handed to [onCreateJob]. A plain swipe is not consumed, so it still
 * scrolls the page (the pattern of the schedule grid). A press without a drag asks for
 * [JobBlockConfig.defaultLengthMs] from the pressed time. The non-drag alternative (WCAG 2.5.7) is the "Add job" button
 * in the review row, which opens the same new-job drawer. With [enabled] false (multi-select) nothing here reacts.
 */
@Composable
internal fun BoxScope.JobColumn(
    layout: DayLayout,
    dayStart: Long,
    dayEnd: Long,
    zone: ZoneId,
    width: Dp,
    fontScale: Float,
    ordered: List<Placed>,
    enabled: Boolean,
    onOpenJob: (String) -> Unit,
    onConfirmSuggestion: (JobSuggestion) -> Unit,
    onCreateJob: (start: Long, end: Long) -> Unit,
    config: JobBlockConfig = JobBlockConfig(),
) {
    val formats = rememberTimeFormats()
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val currentLayout by rememberUpdatedState(layout)
    val currentCreate by rememberUpdatedState(onCreateJob)
    var preview by remember { mutableStateOf<Span?>(null) }
    val dpPerMs = layout.scale / MsPerHour
    fun traversal(startMs: Long) = ordered.count { (if (it is PlacedBlock) it.item.start else (it as PlacedTrip).trip.start) < startMs } - 0.25f

    Box(Modifier.align(Alignment.TopEnd).width(width).fillMaxHeight().testTag(JobColumnTag)) {
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (!enabled) {
                        Modifier
                    } else {
                        Modifier.pointerInput(dayStart, dayEnd) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // A swipe (touch slop passed before the long press) is cancelled here and left to the scroll.
                                val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                fun timeAt(y: Float): Long {
                                    val l = currentLayout
                                    val dp = y.coerceIn(0f, size.height.toFloat()) / density
                                    return l.canvasStart + (dp / l.scale * MsPerHour).toLong()
                                }
                                val anchor = timeAt(press.position.y)
                                var range = dragJobRange(anchor, anchor, dayStart, dayEnd, config)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                preview = range
                                try {
                                    press.consume()
                                    // Initial pass: the page never sees the rest of this gesture.
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) {
                                            currentCreate(range.start, range.end)
                                            break
                                        }
                                        val next = dragJobRange(anchor, timeAt(change.position.y), dayStart, dayEnd, config)
                                        if (next != range) {
                                            range = next
                                            preview = next
                                            if (!reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                    }
                                } finally {
                                    preview = null
                                }
                            }
                        }
                    },
                ),
        )
        layout.jobs.forEach { placed ->
            val b = placed.item
            val colors = JobPalette.lookup(b.jobColorArgb, b.jobId).colors(TarryTheme.isDark)
            val start = maxOf(b.start, dayStart)
            val end = minOf(b.end, dayEnd)
            val spoken = stringResource(R.string.timeline_spoken_range, formats.time(start, zone), formats.time(end, zone))
            val billable = stringResource(if (b.billable) R.string.timeline_job_billable else R.string.timeline_job_not_billable)
            JobTile(
                top = placed.topDp, height = placed.heightDp, lane = placed.lane, laneCount = placed.laneCount, columnWidth = width,
                traversal = traversal(b.start),
                description = stringResource(R.string.timeline_job_description, b.jobName, spoken, billable.lowercase()),
                clickLabel = stringResource(R.string.timeline_open_job),
                enabled = enabled,
                container = colors.container, border = colors.border, dashed = false,
                tag = "timeline_job_${b.id}",
                onClick = { onOpenJob(b.id) },
            ) {
                val textColor = colors.text
                Text(
                    text = b.jobName,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (placed.heightDp >= JobColumnDefaults.DurationMinDp * fontScale) {
                    Text(
                        text = formatDuration(end - start),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (placed.heightDp >= JobColumnDefaults.BillableMinDp * fontScale) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
                        val mark = Modifier.size(JobColumnDefaults.MarkSize).clip(CircleShape)
                        Box(if (b.billable) mark.background(textColor) else mark.border(JobColumnDefaults.MarkRing, textColor, CircleShape))
                        Text(text = billable, style = MaterialTheme.typography.labelSmall, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        layout.suggestions.forEach { placed ->
            val s = placed.item
            val colors = JobPalette.lookup(s.jobColorArgb, s.suggestion.jobId).colors(TarryTheme.isDark)
            val start = maxOf(s.suggestion.start, dayStart)
            val end = minOf(s.suggestion.end, dayEnd)
            val spoken = stringResource(R.string.timeline_spoken_range, formats.time(start, zone), formats.time(end, zone))
            JobTile(
                top = placed.topDp, height = placed.heightDp, lane = placed.lane, laneCount = placed.laneCount, columnWidth = width,
                traversal = traversal(s.suggestion.start),
                description = stringResource(R.string.timeline_job_suggestion_description, s.jobName, spoken),
                clickLabel = stringResource(R.string.timeline_confirm_suggestion),
                enabled = enabled,
                container = Color.Transparent, border = colors.border, dashed = true,
                tag = "timeline_suggestion_${s.suggestion.visitId}",
                onClick = { onConfirmSuggestion(s.suggestion) },
            ) {
                Text(
                    text = stringResource(R.string.timeline_job_suggestion, s.jobName),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        preview?.let { range ->
            val c = TarryTheme.colors
            val shape = TarryTheme.shapes.small
            Box(
                Modifier
                    .offset(y = ((range.start - layout.canvasStart) * dpPerMs).dp)
                    .fillMaxWidth()
                    .height(((range.end - range.start) * dpPerMs).dp)
                    .clip(shape)
                    .background(c.accentSoft.copy(alpha = JobColumnDefaults.PreviewFillAlpha))
                    .dashedBorder(c.accentStrong, shape)
                    .padding(horizontal = JobColumnDefaults.PaddingH, vertical = JobColumnDefaults.PaddingV)
                    // Visual feedback for the drag in flight; the created job is announced by its own drawer.
                    .clearAndSetSemantics {}
                    .testTag("timeline_job_preview"),
            ) {
                Text(
                    text = stringResource(R.string.timeline_time_range, formats.time(range.start, zone), formats.time(range.end, zone)),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.onAccentSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One block or suggestion in the job column: a rounded tile of its lane's width at its true time, inside an outer box
 * that is at least [JobColumnDefaults.Touch] tall (and wide) so a short block is still a 48 dp target. Focusable, with a
 * visible ink ring; the tile reads as one element ([description]) with one click action ([clickLabel]).
 */
@Composable
private fun JobTile(
    top: Float,
    height: Float,
    lane: Int,
    laneCount: Int,
    columnWidth: Dp,
    traversal: Float,
    description: String,
    clickLabel: String,
    enabled: Boolean,
    container: Color,
    border: Color,
    dashed: Boolean,
    tag: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = TarryTheme.colors
    val shape = TarryTheme.shapes.small
    val gap = JobColumnDefaults.LaneGap
    val laneWidth = (columnWidth - gap * (laneCount - 1)) / laneCount
    val touchHeight = maxOf(height.dp, JobColumnDefaults.Touch)
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .offset(x = (laneWidth + gap) * lane, y = top.dp + (height.dp - touchHeight) / 2)
            .width(laneWidth)
            .height(touchHeight)
            .then(if (enabled) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = traversal
                blockColor = container
                blockDashed = dashed
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(shape)
                .background(container)
                .then(if (dashed) Modifier.dashedBorder(border, shape) else Modifier.border(JobColumnDefaults.BorderWidth, border, shape))
                .then(if (focused) Modifier.border(JobColumnDefaults.BorderWidth * 2, c.ink, shape) else Modifier)
                .padding(horizontal = JobColumnDefaults.PaddingH, vertical = JobColumnDefaults.PaddingV),
            content = content,
        )
    }
}
