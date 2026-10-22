package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.JobColors
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.TripMode
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

/** Opacity of blocks that do not need review while the "Needs review only" filter is on. */
internal const val DimmedAlpha = 0.4f

private const val MsPerHour = 3_600_000f

/** A handle drag in flight: [edgeMs] is the snapped, clamped time the edge is at now; [originMs] where it started. */
private data class ResizeDrag(val visitId: String, val edge: ResizeEdge, val originMs: Long, val edgeMs: Long)

/** Test tag of every hour label in the time column. */
internal const val HourLabelTag = "timeline_hour_label"

/** Sizes and offsets of the day canvas (dp unless noted). Text-linked offsets are multiplied by the font scale. */
private object DayCanvasDefaults {
    /** Minimum width of the time column; it grows to fit the widest hour label and the now-pill. */
    val TimeColumn = 44.dp
    val BlockInset = 4.dp
    val BorderWidth = 1.5.dp
    val RuleWidth = 1.dp

    /** Gap between the hour label's right edge and the rail, and between the time text and the pill's edge. */
    val LabelGap = 6.dp
    val PillPaddingH = 4.dp
    val PillPaddingV = 2.dp

    /** Half a `bodySmall` line: centres an hour label on its rule. */
    val HourLabelHalfLine = 8.dp

    /** Half the now-pill's height: centres the pill on the now-line. */
    val NowPillHalfHeight = 10.dp
    val NowLineHeight = 2.dp

    val BlockPaddingV = 6.dp
    val OneLinePaddingV = 2.dp
    val ChipPaddingV = 3.dp

    val GapPaddingV = 4.dp
    val GapIconSize = 20.dp
    val TripIconSize = 16.dp
    val InnerGapIconSize = 16.dp
    /** Radius of the outage dot that sits on a visit block's left border. */
    val InnerGapDotRadius = 2.5.dp

    /** Height of the "Includes N min not tracked" line (per unit of font scale). */
    const val SummaryLineDp = 20f

    /** Line heights of `titleMedium`, `bodyMedium` and `labelMedium`, to work out how many lines fit in a block. */
    const val TitleLineDp = 22f
    const val DetailLineDp = 20f
    const val ChipLineDp = 18f

    /** The chip's own vertical padding (2 x 3 dp) plus the gap above it (4 dp). */
    const val ChipExtraDp = 10f

    /** Height of one `bodyMedium` line, used to work out how many lines fit in a gap block. */
    const val GapLineDp = 20f

    /** Below this height (per unit of font scale) a block has no room for its second line. */
    const val OneLineBelowDp = 56f

    /** Below this height (per unit of font scale) a block has no room for the "Needs review" chip. */
    const val ChipMinDp = 92f
    const val HatchStepDp = 8f
    const val HatchAlpha = 0.25f
    val DashOn = 8.dp
    val DashOff = 5.dp
}

/**
 * The day drawn to scale: time column and hour rules, visit blocks, trip rows, hatched gaps and (today only) the
 * now-line. Positions and heights come from [layout]. Each block, trip and gap is one focusable element with its full
 * spoken description, in time order.
 */
@Composable
fun DayCanvas(
    layout: DayLayout,
    dayStart: Long,
    dayEnd: Long,
    zone: ZoneId,
    now: Long,
    modifier: Modifier = Modifier,
    /** The "Needs review only" filter: everything that does not need review is drawn at [DimmedAlpha]. Nothing is hidden. */
    dimNonReview: Boolean = false,
    /** Double tap on a visit block (or TalkBack activation): opens the drawer. */
    onOpenVisit: (String) -> Unit = {},
    /** The visit whose resize handles are shown; a single tap on a visit block selects it. */
    selectedVisitId: String? = null,
    onSelectVisit: (String) -> Unit = {},
    /** A handle drag was released: the visit's new start and end (UTC epoch ms, snapped, already clamped). */
    onResizeVisit: (id: String, start: Long, end: Long) -> Unit = { _, _, _ -> },
    /** Shortest a visit may be dragged to, and the least a trimmed neighbour keeps. */
    minStayMs: Long = DefaultMinStayMs,
    onOpenTrip: (String) -> Unit = {},
    /** The tapped gap block: the gap itself, and for an outage cluster the real gaps it stands for (else empty). */
    onOpenGap: (TimelineItem.Gap, List<TimelineItem.Gap>) -> Unit = { _, _ -> },
) {
    val c = TarryTheme.colors
    val fontScale = maxOf(1f, LocalDensity.current.fontScale)
    val formats = rememberTimeFormats()
    val hourStyle = MaterialTheme.typography.bodySmall
    val nowStyle = hourStyle.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val hourLabels = remember(layout.hourMarks, formats, zone) { layout.hourMarks.map { formats.hour(it.epochMs, zone) } }
    // The time column grows to the widest hour label or now-pill text, so neither is ever clipped at large font sizes.
    val timeColumn = remember(hourLabels, now, formats, zone, hourStyle, density, layout.nowDp != null) {
        val texts = hourLabels.map { it to hourStyle } +
            if (layout.nowDp != null) listOf(formats.time(now, zone) to nowStyle) else emptyList()
        val widest = texts.maxOfOrNull { (text, style) ->
            measurer.measure(text, style, maxLines = 1, softWrap = false, density = density).size.width
        } ?: 0
        maxOf(DayCanvasDefaults.TimeColumn, with(density) { widest.toDp() } + DayCanvasDefaults.LabelGap * 2)
    }
    val ordered = layout.ordered()
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    var drag by remember { mutableStateOf<ResizeDrag?>(null) }
    val dpPerMs = layout.scale / MsPerHour
    val visitItems = remember(layout) { layout.blocks.mapNotNull { it.item as? TimelineItem.Visit }.sortedBy { it.start } }
    val handleStart = timeColumn + DayCanvasDefaults.BlockInset

    TarryCard(modifier = modifier, contentPadding = PaddingValues(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.l)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(layout.heightDp.dp)
                .drawBehind {
                    val stroke = DayCanvasDefaults.RuleWidth.toPx()
                    val railX = timeColumn.toPx()
                    layout.hourMarks.forEach { mark ->
                        // No rule at the very top edge: hour rules only separate hours.
                        if (mark.topDp <= 0f) return@forEach
                        val y = mark.topDp.dp.toPx()
                        drawLine(c.divider, Offset(railX, y), Offset(size.width, y), strokeWidth = stroke)
                    }
                    drawLine(c.divider, Offset(railX, 0f), Offset(railX, size.height), strokeWidth = stroke)
                }
                .semantics { isTraversalGroup = true },
        ) {
            layout.hourMarks.forEachIndexed { i, mark ->
                if (hourLabelCoveredByNowPill(mark.topDp, layout.nowDp, fontScale)) return@forEachIndexed
                Text(
                    text = hourLabels[i],
                    style = hourStyle,
                    color = c.muted,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .offset(y = mark.topDp.dp - DayCanvasDefaults.HourLabelHalfLine * fontScale)
                        .width(timeColumn - DayCanvasDefaults.LabelGap)
                        // Decorative: the blocks carry their own times.
                        .semantics {
                            hideFromAccessibility()
                            testTag = HourLabelTag
                        },
                )
            }
            ordered.forEachIndexed { index, placed ->
                val dimmed = dimNonReview && ((placed as? PlacedBlock)?.item as? TimelineItem.Visit)?.needsReview != true
                // While a handle is dragged the block follows the finger (neighbours stay put until release).
                val live = drag?.takeIf { d -> placed is PlacedBlock && d.visitId == placed.item.id }
                val shown = if (live != null && placed is PlacedBlock) {
                    val shift = (live.edgeMs - live.originMs) * dpPerMs
                    val startShift = if (live.edge == ResizeEdge.Start) shift else 0f
                    val endShift = if (live.edge == ResizeEdge.End) shift else 0f
                    placed.copy(topDp = placed.topDp + startShift, heightDp = (placed.heightDp - startShift + endShift).coerceAtLeast(1f))
                } else {
                    placed
                }
                val position = Modifier
                    .offset(y = shown.topDp.dp)
                    .padding(start = timeColumn + DayCanvasDefaults.BlockInset)
                    .fillMaxWidth()
                    .height(shown.heightDp.dp)
                    .then(if (dimmed) Modifier.alpha(DimmedAlpha) else Modifier)
                when (placed) {
                    is PlacedBlock -> {
                        val times = clampedTimes(placed.item, dayStart, dayEnd, zone)
                        val description = if (placed.cluster.isNotEmpty()) {
                            stringResource(
                                R.string.timeline_cluster_description,
                                times.spokenRange,
                                pluralStringResource(R.plurals.timeline_cluster_outages, placed.cluster.size, placed.cluster.size),
                            )
                        } else {
                            spokenDescription(placed.item, dayStart, dayEnd, zone, placed.foldedTrips, placed.innerGaps)
                        }
                        when (val item = placed.item) {
                            is TimelineItem.Visit -> VisitBlock(
                                item, shown as? PlacedBlock ?: placed, times, description, index, fontScale, dimmed, position,
                                selected = item.id == selectedVisitId,
                                onSelect = { onSelectVisit(item.id) },
                                onOpen = { onOpenVisit(item.id) },
                            )
                            is TimelineItem.Gap -> GapBlock(item, placed, times, description, index, fontScale, dimmed, position) { onOpenGap(item, placed.cluster) }
                            is TimelineItem.Trip -> Unit // Trips are placed as PlacedTrip, never as blocks.
                        }
                    }
                    is PlacedTrip -> TripRow(
                        placed.trip,
                        clampedTimes(placed.trip, dayStart, dayEnd, zone),
                        spokenDescription(placed.trip, dayStart, dayEnd, zone),
                        index,
                        dimmed,
                        position,
                    ) { onOpenTrip(placed.trip.id) }
                }
            }
            // Resize handles of the selected visit, above every block. An edge cut by midnight has no handle.
            val selectedBlock = selectedVisitId?.let { id -> layout.blocks.firstOrNull { it.item.id == id && it.cluster.isEmpty() } }
            val selectedVisit = selectedBlock?.item as? TimelineItem.Visit
            if (selectedBlock != null && selectedVisit != null) {
                val index = visitItems.indexOfFirst { it.id == selectedVisit.id }
                val prev = visitItems.getOrNull(index - 1)?.let { Span(it.start, it.end) }
                val next = visitItems.getOrNull(index + 1)?.let { Span(it.start, it.end) }
                val span = Span(selectedVisit.start, selectedVisit.end)
                listOf(ResizeEdge.Start, ResizeEdge.End).forEach { edge ->
                    val originMs = if (edge == ResizeEdge.Start) span.start else span.end
                    if (originMs <= dayStart || originMs >= dayEnd) return@forEach
                    val edgeDp = if (edge == ResizeEdge.Start) selectedBlock.topDp else selectedBlock.topDp + selectedBlock.heightDp
                    val live = drag?.takeIf { it.visitId == selectedVisit.id && it.edge == edge }
                    ResizeHandle(
                        edge = edge,
                        visitId = selectedVisit.id,
                        edgeDp = edgeDp,
                        shiftDp = if (live != null) (live.edgeMs - live.originMs) * dpPerMs else 0f,
                        start = handleStart,
                        onDragStart = { drag = ResizeDrag(selectedVisit.id, edge, originMs, originMs) },
                        onDragTo = { totalDp ->
                            val current = drag
                            if (current != null && current.visitId == selectedVisit.id && current.edge == edge) {
                                val deltaMs = (totalDp / dpPerMs).toLong()
                                val edgeMs = resizedEdge(edge, originMs, deltaMs, span, prev, next, minStayMs, dayStart, dayEnd)
                                if (edgeMs != current.edgeMs) {
                                    if (!reducedMotion) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    drag = current.copy(edgeMs = edgeMs)
                                }
                            }
                        },
                        onDragEnd = {
                            val done = drag
                            drag = null
                            if (done != null && done.edgeMs != done.originMs) {
                                val newStart = if (edge == ResizeEdge.Start) done.edgeMs else span.start
                                val newEnd = if (edge == ResizeEdge.End) done.edgeMs else span.end
                                onResizeVisit(selectedVisit.id, newStart, newEnd)
                            }
                        },
                        onDragCancel = { drag = null },
                    )
                }
                drag?.takeIf { it.visitId == selectedVisit.id }?.let { d ->
                    val edgeDp = (if (d.edge == ResizeEdge.Start) selectedBlock.topDp else selectedBlock.topDp + selectedBlock.heightDp) +
                        (d.edgeMs - d.originMs) * dpPerMs
                    ResizeLabel(formats.time(d.edgeMs, zone), edgeDp, handleStart)
                }
            }
            layout.nowDp?.let { nowDp ->
                NowLine(nowDp, now, zone, ordered.count { it.topDp < nowDp } - 0.5f, timeColumn, fontScale)
            }
        }
    }
}

@Composable
private fun VisitBlock(
    visit: TimelineItem.Visit,
    placed: PlacedBlock,
    times: ClampedTimes,
    description: String,
    index: Int,
    fontScale: Float,
    dimmed: Boolean,
    modifier: Modifier,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = TarryTheme.colors
    val job: JobColors? = visit.jobName?.let { JobPalette.lookup(visit.jobColorArgb, visit.jobId ?: it).colors(TarryTheme.isDark) }
    val shape = if (placed.compact) TarryTheme.shapes.small else TarryTheme.shapes.block
    val container = job?.container ?: c.card
    val titleColor = job?.text ?: c.ink
    val detailColor = job?.text ?: c.muted
    val unknown = stringResource(R.string.timeline_unknown_place)
    val openLabel = stringResource(R.string.timeline_edit_visit)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentOpen by rememberUpdatedState(onOpen)
    val title = visit.placeLabel ?: unknown
    val oneLine = placed.compact || placed.heightDp < DayCanvasDefaults.OneLineBelowDp * fontScale
    val showChip = !oneLine && job == null && placed.heightDp >= DayCanvasDefaults.ChipMinDp * fontScale
    val (titleLines, detailLines) = textLines(placed.heightDp, fontScale, showChip)

    // Outage dots sit on top of the left border, outside the block clip.
    val dots = Modifier.innerGapDots(placed.innerGaps)
    val notTracked = notTrackedText(placed.innerGaps, times)
    val d = DayCanvasDefaults
    val summaryRoom = d.BlockPaddingV.value * 2 + titleLines * d.TitleLineDp * fontScale + detailLines * d.DetailLineDp * fontScale +
        (if (showChip) d.ChipLineDp * fontScale + d.ChipExtraDp else 0f) + d.SummaryLineDp * fontScale
    val showSummary = notTracked != null && !oneLine && placed.heightDp >= summaryRoom
    var focused by remember { mutableStateOf(false) }
    val look = if (job != null) {
        Modifier.clip(shape).background(container).border(DayCanvasDefaults.BorderWidth, job.border, shape)
    } else {
        Modifier.clip(shape).background(container).dashedBorder(c.outline, shape)
    }
    Box(
        modifier = modifier
            .then(dots)
            .then(look)
            // Single tap selects (confirmed after the double-tap timeout), double tap opens the drawer. A long press is
            // reserved for multi-select and does nothing yet. TalkBack's activate opens the drawer directly.
            .pointerInput(Unit) { detectTapGestures(onTap = { currentSelect() }, onDoubleTap = { currentOpen() }) }
            // Keyboard / switch access (WCAG 2.1.1): focusable, with a visible ink ring; Enter or Space opens the drawer.
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .then(if (focused) Modifier.border(DayCanvasDefaults.BorderWidth * 2, c.ink, shape) else Modifier)
            .onKeyEvent { e ->
                val activate = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar
                if (activate && e.type == KeyEventType.KeyUp) currentOpen()
                activate
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = openLabel) {
                    currentOpen()
                    true
                }
                this.selected = selected
                contentDescription = description
                traversalIndex = index.toFloat()
                blockDimmed = dimmed
                blockColor = container
                blockDashed = job == null
                if (placed.innerGaps.isNotEmpty()) {
                    innerGapHatch = placed.innerGaps.size
                    innerGapRects = innerGapDotRects(placed.innerGaps)
                }
            }
            .testTag("timeline_row_${visit.id}"),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = TarryTheme.space.m,
                end = TarryTheme.space.m,
                top = if (oneLine) DayCanvasDefaults.OneLinePaddingV else DayCanvasDefaults.BlockPaddingV,
                bottom = if (oneLine) DayCanvasDefaults.OneLinePaddingV else DayCanvasDefaults.BlockPaddingV,
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        if (oneLine) {
            Text(
                text = stringResource(R.string.timeline_compact_line, title, formatDuration(times.durationMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = visit.jobName?.let { stringResource(R.string.timeline_range_with_job, times.range, it) } ?: times.range,
                style = MaterialTheme.typography.bodyMedium,
                color = detailColor,
                maxLines = detailLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSummary && notTracked != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.xs),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = c.alert,
                        modifier = Modifier.size(DayCanvasDefaults.InnerGapIconSize),
                    )
                    Text(
                        text = notTracked.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onAlertSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showChip) {
                Text(
                    text = stringResource(R.string.timeline_needs_review),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onAccentSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = TarryTheme.space.xs)
                        .clip(TarryTheme.shapes.pill)
                        .background(c.accentSoft)
                        .padding(horizontal = TarryTheme.space.m, vertical = DayCanvasDefaults.ChipPaddingV),
                )
            }
        }
    }
    }
}


/**
 * How many lines the title and the time line of a block may use. At normal font size both stay on one line and
 * ellipsize (tokens.md). Once the font is scaled up the text no longer fits one line, so a block that has the height
 * gives the title up to two lines and the time line up to three, instead of ellipsizing.
 */
private fun textLines(heightDp: Float, fontScale: Float, showChip: Boolean): Pair<Int, Int> {
    if (fontScale <= 1f) return 1 to 1
    val d = DayCanvasDefaults
    val titleLine = d.TitleLineDp * fontScale
    val detailLine = d.DetailLineDp * fontScale
    val chip = if (showChip) d.ChipLineDp * fontScale + d.ChipExtraDp else 0f
    val spare = heightDp - 2 * d.BlockPaddingV.value - chip
    val title = if (spare >= 2 * titleLine + detailLine) 2 else 1
    val detail = ((spare - title * titleLine) / detailLine).toInt().coerceIn(1, 3)
    return title to detail
}

@Composable
private fun GapBlock(
    gap: TimelineItem.Gap,
    placed: PlacedBlock,
    times: ClampedTimes,
    description: String,
    index: Int,
    fontScale: Float,
    dimmed: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = TarryTheme.colors
    val shape = if (placed.compact) TarryTheme.shapes.small else TarryTheme.shapes.block
    val lines = floor((placed.heightDp - 2 * DayCanvasDefaults.GapPaddingV.value) / (DayCanvasDefaults.GapLineDp * fontScale)).toInt().coerceAtLeast(1)
    Row(
        modifier = modifier
            .clip(shape)
            .background(c.alertSoft)
            .hatch(c.alert.copy(alpha = DayCanvasDefaults.HatchAlpha))
            .border(DayCanvasDefaults.BorderWidth, c.alert, shape)
            .clickable(onClickLabel = stringResource(R.string.timeline_show_details), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = index.toFloat()
                blockDimmed = dimmed
                gapHatch = true
                if (placed.cluster.isNotEmpty()) clusterOutages = placed.cluster.size
            }
            .testTag("timeline_row_${gap.id}")
            .padding(horizontal = TarryTheme.space.m, vertical = DayCanvasDefaults.GapPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        // Decorative: the element's description already says "Tracking gap".
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = c.alert,
            modifier = Modifier.size(DayCanvasDefaults.GapIconSize),
        )
        Text(
            text = if (placed.cluster.isNotEmpty()) {
                stringResource(
                    R.string.timeline_cluster_row,
                    times.range,
                    pluralStringResource(R.plurals.timeline_cluster_outages, placed.cluster.size, placed.cluster.size),
                )
            } else {
                stringResource(R.string.timeline_gap_row, stringResource(gap.cause.labelRes()))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.onAlertSoft,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TripRow(
    trip: TimelineItem.Trip,
    times: ClampedTimes,
    description: String,
    index: Int,
    dimmed: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = TarryTheme.colors
    val text = stringResource(
        R.string.timeline_trip_row,
        stringResource(trip.mode.rowLabelRes()),
        formatDistance(trip.distanceM),
        formatDuration(times.durationMs),
    )
    Row(
        modifier = modifier
            .clickable(onClickLabel = stringResource(R.string.timeline_edit_trip), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = index.toFloat()
                blockDimmed = dimmed
            }
            .testTag("timeline_row_${trip.id}")
            .padding(horizontal = TarryTheme.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        Icon(
            painter = painterResource(if (trip.mode == TripMode.WALK) R.drawable.ic_walk else R.drawable.ic_car),
            contentDescription = null,
            tint = c.muted,
            modifier = Modifier.size(DayCanvasDefaults.TripIconSize),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = c.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** True when the now-pill would overlap the hour label at [markDp]; that label is hidden, as calendars do. */
internal fun hourLabelCoveredByNowPill(markDp: Float, nowDp: Float?, fontScale: Float): Boolean {
    if (nowDp == null) return false
    val reach = (DayCanvasDefaults.NowPillHalfHeight + DayCanvasDefaults.HourLabelHalfLine).value * fontScale
    return abs(markDp - nowDp) < reach
}

@Composable
private fun NowLine(nowDp: Float, now: Long, zone: ZoneId, traversal: Float, timeColumn: Dp, fontScale: Float) {
    val c = TarryTheme.colors
    val time = rememberTimeFormats().time(now, zone)
    val description = stringResource(R.string.timeline_now_description, time)
    // The line itself is decorative; the pill carries the element.
    Box(
        Modifier
            .offset(y = nowDp.dp - DayCanvasDefaults.NowLineHeight / 2)
            .padding(start = timeColumn)
            .fillMaxWidth()
            .height(DayCanvasDefaults.NowLineHeight)
            .background(c.accent)
            .clearAndSetSemantics {},
    )
    // A Box so the pill wraps its text (a centred Text would fill the whole canvas width) with timeColumn as its minimum.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(y = nowDp.dp - DayCanvasDefaults.NowPillHalfHeight * fontScale)
            .widthIn(min = timeColumn)
            .clip(TarryTheme.shapes.pill)
            .background(c.accent)
            .padding(horizontal = DayCanvasDefaults.PillPaddingH, vertical = DayCanvasDefaults.PillPaddingV)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = traversal
            }
            .testTag("timeline_now_line"),
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = c.onAccent,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun Modifier.dashedBorder(color: Color, shape: RoundedCornerShape, width: Dp = DayCanvasDefaults.BorderWidth): Modifier =
    drawBehind {
        val sw = width.toPx()
        val radius = shape.topStart.toPx(size, this) - sw / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(sw / 2, sw / 2),
            size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DayCanvasDefaults.DashOn.toPx(), DayCanvasDefaults.DashOff.toPx()))),
        )
    }

/** Diagonal hatch: 1 dp lines every 8 dp, clipped by the caller's shape. */
private fun Modifier.hatch(color: Color): Modifier = drawBehind {
    val step = DayCanvasDefaults.HatchStepDp.dp.toPx()
    val stroke = DayCanvasDefaults.RuleWidth.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
        x += step
    }
}

/** Outage dots (dp, relative to the block's top-left): one dot on the left border at each outage's midpoint. */
private fun innerGapDotRects(innerGaps: List<InnerGap>): List<Rect> {
    val r = DayCanvasDefaults.InnerGapDotRadius.value
    val x = DayCanvasDefaults.BorderWidth.value / 2
    return innerGaps.map {
        val y = it.offsetDp + it.heightDp / 2
        Rect(x - r, y - r, x + r, y + r)
    }
}

/**
 * Outages inside a visit as a small `alert` dot sitting on the block's left border, at the outage's real time. It is
 * drawn after the block (above the border, outside its clip), so it never touches text. Decorative for a11y: the
 * spoken description and the "Includes N min not tracked" line cover it.
 */
@Composable
private fun Modifier.innerGapDots(innerGaps: List<InnerGap>): Modifier {
    if (innerGaps.isEmpty()) return this
    val color = TarryTheme.colors.alert
    return drawWithContent {
        drawContent()
        innerGapDotRects(innerGaps).forEach { rect ->
            drawCircle(color, radius = rect.width.dp.toPx() / 2, center = Offset(rect.center.x.dp.toPx(), rect.center.y.dp.toPx()))
        }
    }
}
