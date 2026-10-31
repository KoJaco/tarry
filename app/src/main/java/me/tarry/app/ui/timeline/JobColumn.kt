package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import kotlinx.coroutines.delay
import java.time.ZoneId
import kotlin.math.abs

/** How the width right of the time column is shared between the visits-and-trips column and the job column. */
internal object DayColumns {
    /** Share of the width right of the time column that the job column gets; visits and trips get the rest less [Gap]. */
    const val JobFraction = 0.4f

    /** Space between the visits-and-trips column and the job column. */
    val Gap = 8.dp

    /** Least share of the job column at large font sizes, where the visits column needs the room for its text. */
    const val MinJobFraction = 0.2f

    /** The job column is never narrower than one 48 dp touch target, whatever the font scale. */
    val MinJobWidth = 48.dp

    /** [JobFraction] at font scale 1, shrinking in proportion to the font scale (never below [MinJobFraction]). */
    fun jobFraction(fontScale: Float): Float = (JobFraction / maxOf(1f, fontScale)).coerceAtLeast(MinJobFraction)
}

private object JobColumnDefaults {
    /** Space between side-by-side lanes. */
    val LaneGap = 2.dp
    val BorderWidth = 1.5.dp

    /** Least height of a block's touch target, and least width of a lane; a shorter block is still easy to hit. */
    val Touch = 48.dp
    val PaddingH = 6.dp
    val PaddingV = 4.dp

    /** The billable mark: a filled dot when billable, a ring when not. */
    val MarkSize = 8.dp
    val MarkRing = 1.5.dp

    /** The job swatch in the overflow sheet. */
    val Swatch = 20.dp

    /** Below these heights (per unit of font scale) a block has no room for its duration line / its billable line. */
    const val DurationMinDp = 56f
    const val BillableMinDp = 84f

    /** Alpha of the range being drawn by a drag, over the card. */
    const val PreviewFillAlpha = 0.25f

    /** Alpha of a block while it is being dragged (the moved copy is drawn over it). */
    const val DraggedAlpha = 0.4f

    /** The grab pills on a movable block's top and bottom edge. */
    val GripWidth = 24.dp
    val GripHeight = 3.dp
    const val GripAlpha = 0.6f
}

/** Test tag of the job column (the area a long press and drag draws a new job on). */
internal const val JobColumnTag = "timeline_job_column"

private const val MsPerHour = 3_600_000f

/** The tiles the job column draws once lanes are limited to [JobColumnDefaults.Touch] wide; see [planJobColumn]. */
internal data class JobColumnPlan(
    val jobs: List<PlacedJob>,
    val suggestions: List<PlacedSuggestion>,
    val overflows: List<JobOverflow>,
)

/** A "+N" tile standing in for the blocks and suggestions that do not fit beside each other, over their combined range. */
internal data class JobOverflow(
    val lane: Int,
    val laneCount: Int,
    val topDp: Float,
    val heightDp: Float,
    val start: Long,
    val blocks: List<PlacedJob>,
    val suggestions: List<PlacedSuggestion>,
) {
    val count: Int get() = blocks.size + suggestions.size
}

/** How many lanes of at least [JobColumnDefaults.Touch] fit in [width] with [JobColumnDefaults.LaneGap] between them (at least one). */
internal fun maxJobLanes(width: Dp): Int {
    val gap = JobColumnDefaults.LaneGap
    return ((width + gap) / (JobColumnDefaults.Touch + gap)).toInt().coerceAtLeast(1)
}

/**
 * Fits the layout's lanes into [maxLanes]. A cluster of overlapping tiles needing more lanes keeps its first
 * `maxLanes - 1` lanes (re-spread over [maxLanes]) and gets one overflow tile in the last lane, spanning the hidden
 * tiles' combined range. Clusters that fit are untouched.
 */
internal fun planJobColumn(
    jobs: List<PlacedJob>,
    suggestions: List<PlacedSuggestion>,
    maxLanes: Int,
    dayStart: Long,
    dayEnd: Long,
): JobColumnPlan {
    class Entry(val start: Long, val end: Long, val lane: Int, val count: Int, val top: Float, val height: Float, val job: PlacedJob?, val sg: PlacedSuggestion?)
    val entries = jobs.map { Entry(maxOf(it.item.start, dayStart), minOf(it.item.end, dayEnd), it.lane, it.laneCount, it.topDp, it.heightDp, it, null) } +
        suggestions.map {
            Entry(maxOf(it.item.suggestion.start, dayStart), minOf(it.item.suggestion.end, dayEnd), it.lane, it.laneCount, it.topDp, it.heightDp, null, it)
        }
    if (entries.none { it.count > maxLanes }) return JobColumnPlan(jobs, suggestions, emptyList())

    // Same clustering as assignLanes: sorted by start, a new cluster once a tile starts at or after the cluster's end.
    val clusters = ArrayList<List<Entry>>()
    var current = ArrayList<Entry>()
    var clusterEnd = Long.MIN_VALUE
    for (e in entries.sortedWith(compareBy({ it.start }, { it.end }))) {
        if (current.isNotEmpty() && e.start >= clusterEnd) {
            clusters.add(current)
            current = ArrayList()
        }
        clusterEnd = if (current.isEmpty()) e.end else maxOf(clusterEnd, e.end)
        current.add(e)
    }
    if (current.isNotEmpty()) clusters.add(current)

    val keptJobs = ArrayList<PlacedJob>()
    val keptSuggestions = ArrayList<PlacedSuggestion>()
    val overflows = ArrayList<JobOverflow>()
    for (cluster in clusters) {
        val needed = cluster.maxOf { it.count }
        if (needed <= maxLanes) {
            cluster.forEach { e -> e.job?.let(keptJobs::add); e.sg?.let(keptSuggestions::add) }
            continue
        }
        val (visible, hidden) = cluster.partition { it.lane < maxLanes - 1 }
        visible.forEach { e ->
            e.job?.let { keptJobs.add(it.copy(laneCount = maxLanes)) }
            e.sg?.let { keptSuggestions.add(it.copy(laneCount = maxLanes)) }
        }
        val top = hidden.minOf { it.top }
        overflows.add(
            JobOverflow(
                lane = maxLanes - 1, laneCount = maxLanes, topDp = top, heightDp = hidden.maxOf { it.top + it.height } - top,
                start = hidden.minOf { it.start },
                blocks = hidden.mapNotNull { it.job }.sortedBy { it.item.start },
                suggestions = hidden.mapNotNull { it.sg }.sortedBy { it.item.suggestion.start },
            ),
        )
    }
    return JobColumnPlan(keptJobs, keptSuggestions, overflows)
}

/**
 * The job column of the day canvas: job blocks (side by side in lanes), suggestions (dashed, double tap to review: accept or decline) and the
 * range preview of a drag. Aligned to the end of the canvas, [width] wide, full height; positions come from [layout].
 *
 * Drag to create: long-press empty space, then drag vertically; the range is snapped to
 * [JobBlockConfig.snapMinutes] and, on release, handed to [onCreateJob]. A plain swipe is not consumed, so it still
 * scrolls the page (the pattern of the schedule grid). A press without a drag asks for
 * [JobBlockConfig.defaultLengthMs] from the pressed time. The non-drag alternative (WCAG 2.5.7) is the "Add job" button
 * in the review row, which opens the same new-job drawer. With [enabled] false (multi-select) nothing here reacts.
 *
 * Move and resize a block: long-press a block and drag to move it (duration kept); touch the top or bottom edge and drag to
 * move that edge ([blockDragGesture]). Both snap to [JobBlockConfig.snapMinutes] and are handed to [onJobRangeChange] on
 * release. The non-drag alternatives (WCAG 2.5.7) are the drawer's time pickers and the custom accessibility actions on
 * each block (move earlier / later, extend, shorten by one snap step). Suggestions and "+N" tiles cannot be moved.
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
    onDeclineSuggestion: (JobSuggestion) -> Unit,
    onCreateJob: (start: Long, end: Long) -> Unit,
    onJobRangeChange: (id: String, start: Long, end: Long) -> Unit = { _, _, _ -> },
    config: JobBlockConfig = JobBlockConfig(),
) {
    val formats = rememberTimeFormats()
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val currentLayout by rememberUpdatedState(layout)
    val currentCreate by rememberUpdatedState(onCreateJob)
    val currentRangeChange by rememberUpdatedState(onJobRangeChange)
    var preview by remember { mutableStateOf<Span?>(null) }
    var blockDrag by remember { mutableStateOf<BlockDrag?>(null) }
    val dpPerMs = layout.scale / MsPerHour
    val plan = remember(layout, width, dayStart, dayEnd) {
        planJobColumn(layout.jobs, layout.suggestions, maxJobLanes(width), dayStart, dayEnd)
    }
    var sheetKeys by remember { mutableStateOf<Set<String>?>(null) }
    // The suggestion whose accept / decline sheet is open, by (visit id, job id).
    var reviewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    // A released drag keeps showing until the saved block arrives (or a moment passes, if nothing was saved).
    LaunchedEffect(plan.jobs, layout.jobs) {
        blockDrag = blockDrag?.takeIf { d ->
            !d.committed || layout.jobs.any { it.item.id == d.id && it.item.start == d.fromStart && it.item.end == d.fromEnd }
        }
    }
    LaunchedEffect(blockDrag?.committed) {
        if (blockDrag?.committed == true) {
            delay(CommitHoldMs)
            blockDrag = null
        }
    }
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
        plan.jobs.forEach { placed ->
            val b = placed.item
            val colors = JobPalette.lookup(b.jobColorArgb, b.jobId).colors(TarryTheme.isDark)
            val start = maxOf(b.start, dayStart)
            val end = minOf(b.end, dayEnd)
            val spoken = stringResource(R.string.timeline_spoken_range, formats.time(start, zone), formats.time(end, zone))
            val billable = stringResource(if (b.billable) R.string.timeline_job_billable else R.string.timeline_job_not_billable)
            // A block cut by midnight is edited in the drawer only: dragging would clamp the part that is off screen.
            val movable = enabled && b.start >= dayStart && b.end <= dayEnd
            val stepMs = config.snapMinutes * 60_000L
            val minutes = config.snapMinutes
            fun applyRange(r: Span): Boolean {
                if (r.start == b.start && r.end == b.end) return false
                currentRangeChange(b.id, r.start, r.end)
                return true
            }
            val earlier = stringResource(R.string.timeline_job_move_earlier, minutes)
            val later = stringResource(R.string.timeline_job_move_later, minutes)
            val extend = stringResource(R.string.timeline_job_extend, minutes)
            val shorten = stringResource(R.string.timeline_job_shorten, minutes)
            val actions = if (!movable) emptyList() else listOf(
                CustomAccessibilityAction(earlier) { applyRange(moveRange(b.start, b.end, -stepMs, dayStart, dayEnd, config)) },
                CustomAccessibilityAction(later) { applyRange(moveRange(b.start, b.end, stepMs, dayStart, dayEnd, config)) },
                CustomAccessibilityAction(extend) { applyRange(resizeRange(ResizeEdge.End, b.start, b.end, b.end + stepMs, dayStart, dayEnd, config)) },
                CustomAccessibilityAction(shorten) { applyRange(resizeRange(ResizeEdge.End, b.start, b.end, b.end - stepMs, dayStart, dayEnd, config)) },
            )
            val touchHeight = maxOf(placed.heightDp, JobColumnDefaults.Touch.value)
            val gesture = if (!movable) {
                Modifier
            } else {
                Modifier.blockDragGesture(
                    b, placed.heightDp, touchHeight, { currentLayout.scale }, dayStart, dayEnd, config, haptics, reducedMotion,
                    onDrag = { r ->
                        blockDrag = if (r != null) BlockDrag(b.id, r, b.start, b.end) else blockDrag?.takeIf { it.committed }
                    },
                    onCommit = { r ->
                        blockDrag = BlockDrag(b.id, r, b.start, b.end, committed = true)
                        currentRangeChange(b.id, r.start, r.end)
                    },
                )
            }
            JobTile(
                top = placed.topDp, height = placed.heightDp, lane = placed.lane, laneCount = placed.laneCount, columnWidth = width,
                traversal = traversal(b.start),
                description = stringResource(R.string.timeline_job_description, b.jobName, spoken, billable.lowercase()),
                clickLabel = stringResource(R.string.timeline_open_job),
                enabled = enabled,
                container = colors.container, border = colors.border, dashed = false,
                tag = "timeline_job_${b.id}",
                onClick = { onOpenJob(b.id) },
                gesture = gesture,
                actions = actions,
                alpha = if (blockDrag?.id == b.id) JobColumnDefaults.DraggedAlpha else 1f,
                gripColor = if (movable) colors.border else null,
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
        plan.suggestions.forEach { placed ->
            val s = placed.item
            val colors = JobPalette.lookup(s.jobColorArgb, s.suggestion.jobId).colors(TarryTheme.isDark)
            val start = maxOf(s.suggestion.start, dayStart)
            val end = minOf(s.suggestion.end, dayEnd)
            val spoken = stringResource(R.string.timeline_spoken_range, formats.time(start, zone), formats.time(end, zone))
            JobTile(
                top = placed.topDp, height = placed.heightDp, lane = placed.lane, laneCount = placed.laneCount, columnWidth = width,
                traversal = traversal(s.suggestion.start),
                description = stringResource(R.string.timeline_job_suggestion_description, s.jobName, spoken),
                clickLabel = stringResource(R.string.timeline_review_suggestion),
                enabled = enabled,
                container = Color.Transparent, border = colors.border, dashed = true,
                tag = "timeline_suggestion_${s.suggestion.visitId}",
                onClick = {},
                onDoubleClick = { reviewing = s.suggestion.visitId to s.suggestion.jobId },
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
        plan.overflows.forEachIndexed { index, overflow ->
            val c = TarryTheme.colors
            val description = pluralStringResource(R.plurals.timeline_job_overflow_description, overflow.count, overflow.count)
            JobTile(
                top = overflow.topDp, height = overflow.heightDp, lane = overflow.lane, laneCount = overflow.laneCount, columnWidth = width,
                traversal = traversal(overflow.start),
                description = description,
                clickLabel = stringResource(R.string.timeline_show_jobs),
                enabled = enabled,
                container = c.accentSoft, border = c.accentStrong, dashed = false,
                tag = "timeline_job_overflow_$index",
                onClick = {
                    sheetKeys = overflow.blocks.mapTo(HashSet<String>()) { it.item.id } +
                        overflow.suggestions.map { SuggestionKeyPrefix + it.item.suggestion.visitId }
                },
            ) {
                Text(
                    text = stringResource(R.string.timeline_job_overflow_label, overflow.count),
                    style = MaterialTheme.typography.labelLarge,
                    color = c.onAccentSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        sheetKeys?.let { keys ->
            val blocks = layout.jobs.filter { it.item.id in keys }
            val suggestions = layout.suggestions.filter { SuggestionKeyPrefix + it.item.suggestion.visitId in keys }
            if (blocks.isEmpty() && suggestions.isEmpty()) {
                sheetKeys = null
            } else {
                JobOverflowSheet(
                    blocks = blocks.map { it.item }, suggestions = suggestions.map { it.item }, dayStart = dayStart, dayEnd = dayEnd,
                    zone = zone,
                    onOpenJob = { id -> sheetKeys = null; onOpenJob(id) },
                    onReviewSuggestion = { s -> sheetKeys = null; reviewing = s.visitId to s.jobId },
                    onDismiss = { sheetKeys = null },
                )
            }
        }
        reviewing?.let { (visitId, jobId) ->
            val item = layout.suggestions.firstOrNull { it.item.suggestion.visitId == visitId && it.item.suggestion.jobId == jobId }?.item
            if (item == null) {
                reviewing = null
            } else {
                SuggestionSheet(
                    item = item, dayStart = dayStart, dayEnd = dayEnd, zone = zone,
                    onAccept = { reviewing = null; onConfirmSuggestion(item.suggestion) },
                    onDecline = { reviewing = null; onDeclineSuggestion(item.suggestion) },
                    onDismiss = { reviewing = null },
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
        blockDrag?.let { drag ->
            val placed = plan.jobs.firstOrNull { it.item.id == drag.id } ?: return@let
            val b = placed.item
            val colors = JobPalette.lookup(b.jobColorArgb, b.jobId).colors(TarryTheme.isDark)
            val shape = TarryTheme.shapes.small
            val gap = JobColumnDefaults.LaneGap
            val laneWidth = maxOf((width - gap * (placed.laneCount - 1)) / placed.laneCount, JobColumnDefaults.Touch)
            Box(
                Modifier
                    .offset(x = (laneWidth + gap) * placed.lane, y = ((drag.range.start - layout.canvasStart) * dpPerMs).dp)
                    .width(laneWidth)
                    .height(((drag.range.end - drag.range.start) * dpPerMs).dp)
                    .clip(shape)
                    .background(colors.container)
                    .border(JobColumnDefaults.BorderWidth * 2, colors.border, shape)
                    .padding(horizontal = JobColumnDefaults.PaddingH, vertical = JobColumnDefaults.PaddingV)
                    // Visual feedback for the drag in flight; the block itself keeps its description.
                    .testTag("timeline_job_drag_preview")
                    .clearAndSetSemantics {},
            ) {
                Column {
                    Text(
                        text = b.jobName, style = MaterialTheme.typography.labelLarge, color = colors.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.timeline_time_range, formats.time(drag.range.start, zone), formats.time(drag.range.end, zone)),
                        style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * One block or suggestion in the job column: a rounded tile of its lane's width at its true time, inside an outer box
 * that is at least [JobColumnDefaults.Touch] tall so a short block is still a 48 dp target. Lanes are never narrower than
 * [JobColumnDefaults.Touch] ([planJobColumn] limits how many fit), but a column narrower than that still gives one 48 dp wide tile. Focusable, with a
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
    /** Move / resize gesture of a block; empty for everything else. */
    gesture: Modifier = Modifier,
    /** Set for a tile that opens on a double tap only (a single tap does nothing); TalkBack activation still opens it at once. */
    onDoubleClick: (() -> Unit)? = null,
    /** Non-drag alternatives to [gesture] (WCAG 2.5.7), as TalkBack custom actions. */
    actions: List<CustomAccessibilityAction> = emptyList(),
    alpha: Float = 1f,
    /** The colour of the grab pills on the top and bottom edge, or null for a tile that cannot be moved. */
    gripColor: Color? = null,
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
            .width(maxOf(laneWidth, JobColumnDefaults.Touch))
            .height(touchHeight)
            .alpha(alpha)
            .then(
                if (!enabled) Modifier
                else if (onDoubleClick != null) {
                    // The semantics sit outside the clickable so its (do-nothing) click action does not replace this one.
                    Modifier
                        .semantics { semanticsOnClick(label = clickLabel) { onDoubleClick(); true } }
                        .combinedClickable(onClickLabel = clickLabel, role = Role.Button, onClick = {}, onDoubleClick = onDoubleClick)
                } else gesture.then(Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)),
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = traversal
                blockColor = container
                blockDashed = dashed
                if (actions.isNotEmpty()) customActions = actions
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
        if (gripColor != null) {
            val inset = (touchHeight - height.dp) / 2 + JobColumnDefaults.PaddingV / 2
            listOf(Alignment.TopCenter, Alignment.BottomCenter).forEach { side ->
                Box(
                    Modifier
                        .align(side)
                        .padding(vertical = inset)
                        .size(JobColumnDefaults.GripWidth, JobColumnDefaults.GripHeight)
                        .background(gripColor.copy(alpha = JobColumnDefaults.GripAlpha), CircleShape),
                )
            }
        }
    }
}

private const val SuggestionKeyPrefix = "suggestion:"

/** The blocks and suggestions folded into a "+N" tile, as a bottom sheet; each row opens the block or the suggestion's review sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobOverflowSheet(
    blocks: List<JobBlockItem>,
    suggestions: List<JobSuggestionItem>,
    dayStart: Long,
    dayEnd: Long,
    zone: ZoneId,
    onOpenJob: (String) -> Unit,
    onReviewSuggestion: (JobSuggestion) -> Unit,
    onDismiss: () -> Unit,
) {
    val formats = rememberTimeFormats()
    @Composable fun range(start: Long, end: Long) =
        stringResource(R.string.timeline_time_range, formats.time(maxOf(start, dayStart), zone), formats.time(minOf(end, dayEnd), zone))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = TarryTheme.colors.paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TarryTheme.space.gutter)
                .padding(bottom = TarryTheme.space.l)
                .testTag("job_overflow_sheet"),
        ) {
            Text(
                text = stringResource(R.string.timeline_job_overflow_title),
                style = MaterialTheme.typography.titleLarge,
                color = TarryTheme.colors.ink,
                modifier = Modifier.semantics { heading() }.padding(bottom = TarryTheme.space.s),
            )
            val rows: List<Pair<Long, @Composable () -> Unit>> =
                blocks.map { b ->
                    b.start to @Composable {
                        OverflowRow(
                            b.jobColorArgb, b.jobId, b.jobName, range(b.start, b.end), dashed = false,
                            clickLabel = stringResource(R.string.timeline_open_job), tag = "job_overflow_row_${b.id}",
                        ) { onOpenJob(b.id) }
                    }
                } + suggestions.map { s ->
                    s.suggestion.start to @Composable {
                        OverflowRow(
                            s.jobColorArgb, s.suggestion.jobId, stringResource(R.string.timeline_job_suggestion, s.jobName),
                            range(s.suggestion.start, s.suggestion.end), dashed = true,
                            clickLabel = stringResource(R.string.timeline_review_suggestion),
                            tag = "job_overflow_row_$SuggestionKeyPrefix${s.suggestion.visitId}",
                        ) { onReviewSuggestion(s.suggestion) }
                    }
                }
            rows.sortedBy { it.first }.forEach { (_, row) -> row() }
        }
    }
}

/** The accept / decline sheet of one suggestion, titled with the job and the range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestionSheet(
    item: JobSuggestionItem,
    dayStart: Long,
    dayEnd: Long,
    zone: ZoneId,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formats = rememberTimeFormats()
    val range = stringResource(
        R.string.timeline_time_range,
        formats.time(maxOf(item.suggestion.start, dayStart), zone), formats.time(minOf(item.suggestion.end, dayEnd), zone),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = TarryTheme.colors.paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = TarryTheme.space.gutter)
                .padding(bottom = TarryTheme.space.l)
                .testTag("job_suggestion_sheet"),
        ) {
            Text(
                text = stringResource(R.string.timeline_suggestion_sheet_title, item.jobName, range),
                style = MaterialTheme.typography.titleLarge,
                color = TarryTheme.colors.ink,
                modifier = Modifier.semantics { heading() }.padding(bottom = TarryTheme.space.s),
            )
            listOf(
                Triple(R.string.timeline_suggestion_accept, "job_suggestion_accept", onAccept),
                Triple(R.string.timeline_suggestion_decline, "job_suggestion_decline", onDecline),
            ).forEach { (label, tag, action) ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = JobColumnDefaults.Touch)
                        .clickable(role = Role.Button, onClick = action)
                        .testTag(tag),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink)
                }
            }
        }
    }
}

@Composable
private fun OverflowRow(
    colorArgb: Int,
    jobId: String,
    title: String,
    range: String,
    dashed: Boolean,
    clickLabel: String,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = JobPalette.lookup(colorArgb, jobId).colors(TarryTheme.isDark)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = JobColumnDefaults.Touch)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        val swatch = Modifier.size(JobColumnDefaults.Swatch).clip(CircleShape)
        Box(
            if (dashed) swatch.border(JobColumnDefaults.BorderWidth, colors.border, CircleShape)
            else swatch.background(colors.container).border(JobColumnDefaults.BorderWidth, colors.border, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink)
            Text(text = range, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        }
    }
}

/** A block being moved or resized: the live [range] (and, once released, held until the saved block shows up). */
private data class BlockDrag(val id: String, val range: Span, val fromStart: Long, val fromEnd: Long, val committed: Boolean = false)

private enum class BlockIntent { Resize, Move, Ignore }

/** How long a released drag keeps showing its range while the save lands, if the block never changes. */
private const val CommitHoldMs = 1_000L

/**
 * Move and resize gesture of one job block, on the block's own touch box ([touchHeightDp] tall, [blockHeightDp] of it
 * the block). Decided in the Initial pass before the page scroll can react:
 *  - held still for the long-press timeout: move (the whole block, duration kept);
 *  - moved past touch slop vertically from the top / bottom edge zone ([edgeAt]): resize that edge at once;
 *  - moved past slop anywhere else: not ours, left to the page scroll. A tap is left to the block's click.
 * Once ours, every event is consumed so the page does not scroll. [onDrag] gets the live range and null when the gesture
 * ends; [onCommit] the final range on release (never on cancel). Ranges are snapped by [moveRange] / [resizeRange].
 */
private fun Modifier.blockDragGesture(
    block: JobBlockItem,
    blockHeightDp: Float,
    touchHeightDp: Float,
    layoutScale: () -> Float,
    dayStart: Long,
    dayEnd: Long,
    config: JobBlockConfig,
    haptics: HapticFeedback,
    reducedMotion: Boolean,
    onDrag: (Span?) -> Unit,
    onCommit: (Span) -> Unit,
): Modifier = pointerInput(block.id, block.start, block.end, blockHeightDp, touchHeightDp, dayStart, dayEnd, reducedMotion) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val edge = edgeAt(down.position.y / density, blockHeightDp, touchHeightDp, config.edgeZoneDp)
        val slop = viewConfiguration.touchSlop
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        val decided = withTimeoutOrNull(longPressMs) {
            var verdict: BlockIntent? = null
            while (verdict == null) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                verdict = when {
                    change == null || change.isConsumed || !change.pressed -> BlockIntent.Ignore
                    change.uptimeMillis - down.uptimeMillis >= longPressMs -> BlockIntent.Move
                    (change.position - down.position).getDistance() > slop -> {
                        val d = change.position - down.position
                        if (edge != null && abs(d.y) >= abs(d.x)) BlockIntent.Resize.also { change.consume() } else BlockIntent.Ignore
                    }
                    else -> null
                }
            }
            verdict
        } ?: BlockIntent.Move
        if (decided == BlockIntent.Ignore) return@awaitEachGesture
        val resizeEdge = if (decided == BlockIntent.Resize) edge else null
        val origin = Span(block.start, block.end)
        var range = origin
        if (decided == BlockIntent.Move) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onDrag(range)
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                val deltaMs = ((change.position.y - down.position.y) / density / layoutScale() * MsPerHour).toLong()
                val next = when (resizeEdge) {
                    null -> moveRange(origin.start, origin.end, deltaMs, dayStart, dayEnd, config)
                    ResizeEdge.Start -> resizeRange(ResizeEdge.Start, origin.start, origin.end, origin.start + deltaMs, dayStart, dayEnd, config)
                    ResizeEdge.End -> resizeRange(ResizeEdge.End, origin.start, origin.end, origin.end + deltaMs, dayStart, dayEnd, config)
                }
                if (!change.pressed) {
                    if (next != origin) onCommit(next)
                    break
                }
                if (next != range) {
                    range = next
                    onDrag(next)
                    if (!reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
        } finally {
            onDrag(null)
        }
    }
}
