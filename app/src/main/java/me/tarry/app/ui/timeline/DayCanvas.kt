package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.TarryColors
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.TripMode
import java.time.ZoneId
import me.tarry.core.data.model.SessionEndReason
import kotlin.math.abs
import kotlin.math.floor

/** Opacity of blocks that do not need review while the "Needs review only" filter is on. */
internal const val DimmedAlpha = 0.4f

private const val MsPerHour = 3_600_000f

/** A handle drag in flight: [edgeMs] is the snapped, clamped time the edge is at now; [originMs] where it started. */
private data class ResizeDrag(val visitId: String, val edge: ResizeEdge, val originMs: Long, val edgeMs: Long)

/** Test tag of every hour label in the time column. */
internal const val HourLabelTag = "timeline_hour_label"

/** Test tag of every minor-mark label (":10", ":30" ...) in the time column. */
internal const val MinorLabelTag = "timeline_minor_label"

/** Sizes and offsets of the day canvas (dp unless noted). Text-linked offsets are multiplied by the font scale. */
private object DayCanvasDefaults {
    /** Minimum width of the time column; it grows to fit the widest hour label and the now-pill (below [StackedTimeLabelsFontScale]). */
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

    /** The ink check badge on a selected visit block, and its inset from the block's corner. */
    val CheckBadge = 24.dp
    val CheckIcon = 16.dp
    val CheckInset = 4.dp

    val BlockPaddingV = 6.dp
    val OneLinePaddingV = 2.dp
    val ChipPaddingV = 3.dp

    val GapPaddingV = 4.dp
    val GapIconSize = 20.dp
    val TripIconSize = 16.dp
    /** Least height and width of a trip's touch target. It may extend invisibly over the neighbouring visits' edges. */
    val TripTouch = 48.dp

    /** Half-length of a 15-minute tick, centred on the rail. */
    val QuarterTickHalf = 3.dp

    /** Half-length of an hour tick, centred on the rail. */
    val HourTickHalf = 6.dp

    /** The dotted line joining a trip to its visits: dot size, and the gap between dots as a multiple of it. */
    val TripIconBackdrop = 2.dp
    val ConnectorWidth = 1.5.dp
    const val ConnectorGapFactor = 3f

    /** Alpha of a 15-minute tick relative to the hour rule (both `divider`). */
    const val QuarterTickAlpha = 0.6f
    val InnerGapIconSize = 16.dp
    /** Radius of the outage dot that sits on a visit block's left border. */
    val InnerGapDotRadius = 2.5.dp

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

    val MarkerLabelGap = 2.dp

    /** The side column once it has collapsed to the rail (large fonts): room for the hour ticks either side of the rail line. */
    val RailColumn = 12.dp

    /** Horizontal padding of a time label drawn over the canvas on a `card` background (large fonts). */
    val StackedLabelPadding = 2.dp

    /** One line of the card's own text (per unit of font scale): a card shorter than this plus a handle's 48 dp gets its handles outside. */
    const val HandleCardTextDp = 28f

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
    /** Multi-select mode: taps toggle visits in [selectedIds]; the single-tap select, double tap and handles are off. */
    selecting: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    /** Long press on a visit block (or the TalkBack "Select for bulk edit" action) enters selection mode. */
    onLongPressVisit: (String) -> Unit = {},
    onToggleVisit: (String) -> Unit = {},
    /** A handle drag was released: the visit's new start and end (UTC epoch ms, snapped, already clamped). */
    onResizeVisit: (id: String, start: Long, end: Long) -> Unit = { _, _, _ -> },
    /** Shortest a visit may be dragged to, and the least a trimmed neighbour keeps. */
    minStayMs: Long = DefaultMinStayMs,
    onOpenTrip: (String) -> Unit = {},
    /** The tapped gap block: the gap itself, and for an outage cluster the real gaps it stands for (else empty). */
    onOpenGap: (TimelineItem.Gap, List<TimelineItem.Gap>) -> Unit = { _, _ -> },
    /** Tracking starts and stops inside the day: a small "Start" / "Stop" and time label in the time column. */
    trackingMarkers: List<TrackingMarker> = emptyList(),
    /** A job block (or TalkBack activation of it) was tapped: opens its drawer. The blocks themselves are in [layout]. */
    onOpenJob: (String) -> Unit = {},
    /** A suggestion was accepted in its review sheet: the host stores it as a real block. */
    onConfirmSuggestion: (JobSuggestion) -> Unit = {},
    /** A suggestion was declined in its review sheet: the host stops suggesting it. */
    onDeclineSuggestion: (JobSuggestion) -> Unit = {},
    /** A long press and drag on empty job column space was released: the snapped range (UTC epoch ms) for a new job. */
    onCreateJob: (start: Long, end: Long) -> Unit = { _, _ -> },
    /** A job block was moved or resized by drag (or accessibility action): its new snapped range. The host saves it. */
    onJobRangeChange: (id: String, start: Long, end: Long) -> Unit = { _, _, _ -> },
    /** The settled zoom level's snap step in minutes for job drags (never a mid-pinch value). */
    jobSnapMinutes: Int = JobBlockConfig().snapMinutes,
    /** Applied to the canvas box itself (below the card padding): the host's pinch gesture and position tracking. */
    canvasModifier: Modifier = Modifier,
    /** Drawn on top of the canvas box, last in its traversal group (the pinned zoom buttons). */
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = TarryTheme.colors
    val fontScale = maxOf(1f, LocalDensity.current.fontScale)
    val formats = rememberTimeFormats()
    val hourStyle = MaterialTheme.typography.bodySmall
    val nowStyle = hourStyle.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val metrics = rememberTextMetrics()
    val density = LocalDensity.current
    val hourLabels = remember(layout.hourMarks, formats, zone) { layout.hourMarks.map { formats.hour(it.epochMs, zone) } }
    val minorLabels = remember(layout.minorLabels, formats, zone) { layout.minorLabels.map { formats.minute(it.epochMs, zone) } }
    val markerWords = MarkerWords(
        stringResource(R.string.timeline_marker_start), stringResource(R.string.timeline_marker_stop), stringResource(R.string.timeline_marker_off),
        stringResource(R.string.timeline_marker_interrupted),
    )
    // The visible lines of each tracking marker's label.
    val markerLineTexts = remember(trackingMarkers, markerWords, formats, zone) {
        trackingMarkers.associateWith { m -> markerLines(m, markerWords, { formats.time(it, zone) }) }
    }
    // The time column grows to the widest hour label, marker label or now-pill text, so none is ever clipped at large font sizes.
    val stacked = stackedTimeLabels(fontScale)
    // The real height of one label line at this font scale (never a literal): the collapsed layout puts a label's bottom exactly on its line, and marker labels keep clear of the now-pill by its real extent.
    val hourLineDp = metrics.lineDp(hourStyle)
    val timeColumn = if (stacked) DayCanvasDefaults.RailColumn else remember(hourLabels, markerLineTexts, now, formats, zone, hourStyle, density, layout.nowDp != null) {
        val texts = hourLabels.map { it to hourStyle } + markerLineTexts.values.flatten().map { it to hourStyle } +
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
    val placedMarkers = remember(markerLineTexts, layout, fontScale, stacked, hourLineDp) {
        // The now-pill sits in the time column too; a marker label never goes under it.
        val pillHalf = nowPillHalfHeightDp(hourLineDp)
        val pill = layout.nowDp?.let { (it - pillHalf)..(it + pillHalf) }
        placeTrackingMarkers(
            trackingMarkers, layout.canvasStart, layout.canvasEnd, layout.scale, layout.heightDp,
            hourLineDp, DayCanvasDefaults.MarkerLabelGap.value,
            lineCount = { markerLineTexts[it]?.size ?: 2 },
            avoid = pill,
            above = stacked,
            blocks = layout.blocks.map { it.topDp..(it.topDp + it.heightDp) },
        )
    }

    // "Not tracking" stretches (stop to next start; only past time): a faint hatch under everything, with a label if it fits.
    val offLabelHeightDp = metrics.lineDp(MaterialTheme.typography.labelSmall)
    val offStretches = remember(trackingMarkers, layout, dayEnd, now, dayStart) {
        val isToday = now in dayStart until dayEnd
        clipOutOfVisits(
            offStretches(trackingMarkers, dayEnd, if (isToday) now else null),
            // A visit that carries on across a stop and restart is not time off.
            layout.blocks.mapNotNull { b -> (b.item as? TimelineItem.Visit)?.let { it.start..it.end } },
        ).mapNotNull { s ->
            val a = maxOf(s.start, layout.canvasStart)
            val b = minOf(s.end, layout.canvasEnd)
            if (b <= a) null else PlacedOff(s, ((a - layout.canvasStart) * dpPerMs), ((b - a) * dpPerMs))
        }
    }
    // Each trip is joined to its visits by a dotted line through the trip icon's centre, drawn under the blocks.
    val connectorX = timeColumn + DayCanvasDefaults.BlockInset + TarryTheme.space.m + DayCanvasDefaults.TripIconBackdrop + DayCanvasDefaults.TripIconSize / 2
    val connectors = remember(layout) { layout.trips.map { tripConnector(it, layout.blocks) } }

    TarryCard(modifier = modifier, contentPadding = PaddingValues(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.l)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(layout.heightDp.dp)
                .then(canvasModifier)
                .drawBehind {
                    val stroke = DayCanvasDefaults.RuleWidth.toPx()
                    val hourTick = DayCanvasDefaults.HourTickHalf.toPx()
                    val railX = timeColumn.toPx()
                    // Off stretches: the gap-hatch style, in the quiet `line` colour, right of the rail.
                    offStretches.forEach { off ->
                        val top = off.topDp.dp.toPx()
                        val bottom = top + off.heightDp.dp.toPx()
                        clipRect(left = railX, top = top, right = size.width, bottom = bottom) {
                            val step = DayCanvasDefaults.HatchStepDp.dp.toPx()
                            var x = railX - (bottom - top)
                            while (x < size.width) {
                                drawLine(c.line, Offset(x, bottom), Offset(x + (bottom - top), top), strokeWidth = stroke)
                                x += step
                            }
                        }
                    }
                    layout.hourMarks.forEach { mark ->
                        // No rule at the very top edge: hour rules only separate hours.
                        if (mark.topDp <= 0f) return@forEach
                        val y = mark.topDp.dp.toPx()
                        drawLine(hourRuleColor(c), Offset(railX, y), Offset(size.width, y), strokeWidth = stroke)
                        // The hour tick crosses the rail, longer than the minor ticks, so hours read at a glance.
                        drawLine(hourRuleColor(c), Offset(railX - hourTick, y), Offset(railX + hourTick, y), strokeWidth = stroke)
                    }
                    drawLine(c.divider, Offset(railX, 0f), Offset(railX, size.height), strokeWidth = stroke)
                    // Minor ticks on the rail (every 15, 10 or 5 min by zoom): drawn only, so they are neither focusable nor spoken.
                    val tick = DayCanvasDefaults.QuarterTickHalf.toPx()
                    val tickColor = minorTickColor(c)
                    layout.minorMarks.forEach { dp ->
                        val y = dp.dp.toPx()
                        drawLine(tickColor, Offset(railX - tick, y), Offset(railX + tick, y), strokeWidth = stroke)
                    }
                    val dotted = DayCanvasDefaults.ConnectorWidth.toPx()
                    connectors.forEach { range ->
                        drawLine(
                            hourRuleColor(c), Offset(connectorX.toPx(), range.start.dp.toPx()), Offset(connectorX.toPx(), range.endInclusive.dp.toPx()),
                            strokeWidth = dotted, cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, dotted * DayCanvasDefaults.ConnectorGapFactor)),
                        )
                    }
                    // Collapsed time column (large fonts): a tick at each tracking marker, with its label just above it.
                    if (stacked) {
                        placedMarkers.forEach { pm ->
                            pm.marker.ruleTimes.forEach { t ->
                                val y = ((t - layout.canvasStart) * dpPerMs).dp.toPx()
                                drawLine(hourRuleColor(c), Offset(railX - hourTick, y), Offset(railX + hourTick, y), strokeWidth = stroke)
                            }
                        }
                    }
                }
                .semantics { isTraversalGroup = true },
        ) {
            // Time column | visits and trips | job column. The visit column ends [visitEnd] short of the right edge.
            val jobWidth = maxOf((maxWidth - timeColumn) * DayColumns.jobFraction(fontScale), DayColumns.MinJobWidth)
            val visitEnd = jobWidth + DayColumns.Gap
            // The off stretches, behind everything: one node each for TalkBack ("Not tracking, 7:00 AM to 7:30 AM"), with the label if it fits.
            val offLabel = stringResource(R.string.timeline_off_label)
            offStretches.forEach { off ->
                val description = stringResource(R.string.timeline_off_description, formats.time(off.stretch.start, zone), formats.time(off.stretch.end, zone))
                val traversal = ordered.count { (if (it is PlacedBlock) it.item.start else (it as PlacedTrip).trip.start) < off.stretch.start } - 0.4f
                Box(
                    Modifier
                        .offset(y = off.topDp.dp)
                        .padding(start = timeColumn + DayCanvasDefaults.BlockInset)
                        .fillMaxWidth()
                        .height(off.heightDp.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            traversalIndex = traversal
                        }
                        .testTag("timeline_off_stretch"),
                ) {
                    if (offLabelFits(off, offLabelHeightDp, ordered)) {
                        Text(
                            text = offLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = TarryTheme.space.s, top = OffLabelInsetDp.dp),
                        )
                    }
                }
            }
            if (!stacked) {
            layout.hourMarks.forEachIndexed { i, mark ->
                if (hourLabelCoveredByNowPill(mark.topDp, layout.nowDp, fontScale)) return@forEachIndexed
                // A tracking marker label in the time column wins over an hour label it would collide with.
                if (placedMarkers.any { hourLabelCoveredByMarker(mark.topDp, it, DayCanvasDefaults.HourLabelHalfLine.value * fontScale) }) return@forEachIndexed
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
            val minorStyle = MaterialTheme.typography.labelSmall
            layout.minorLabels.forEachIndexed { i, mark ->
                // The same collision rules as the hour labels: the now-pill and tracking-marker labels win.
                if (hourLabelCoveredByNowPill(mark.topDp, layout.nowDp, fontScale)) return@forEachIndexed
                if (placedMarkers.any { hourLabelCoveredByMarker(mark.topDp, it, DayCanvasDefaults.HourLabelHalfLine.value * fontScale) }) return@forEachIndexed
                Text(
                    text = minorLabels[i],
                    style = minorStyle,
                    color = c.muted,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .offset(y = mark.topDp.dp - DayCanvasDefaults.HourLabelHalfLine * fontScale)
                        .width(timeColumn - DayCanvasDefaults.LabelGap)
                        // Decorative, like the hour labels.
                        .semantics {
                            hideFromAccessibility()
                            testTag = MinorLabelTag
                        },
                )
            }
            }
            JobColumn(
                layout, dayStart, dayEnd, zone, jobWidth, fontScale, ordered, enabled = !selecting,
                onOpenJob = onOpenJob, onConfirmSuggestion = onConfirmSuggestion, onDeclineSuggestion = onDeclineSuggestion, onCreateJob = onCreateJob,
                onJobRangeChange = onJobRangeChange, snapMinutes = jobSnapMinutes,
            )
            // Collapsed time column (large fonts): hour and minor labels sit just above their lines on a card backdrop.
            // Minor labels go UNDER the blocks (composed before them), so they only show in free space. Hour labels are
            // composed after the blocks (below) so a long visit never hides which hour it is (owner, 2026-11-09).
            // A tracking marker label or the now-pill wins over a label it would collide with.
            fun stackedLabelCovered(top: Float, height: Float): Boolean =
                hourLabelCoveredByNowPill(top + height / 2, layout.nowDp, fontScale) ||
                    placedMarkers.any { hourLabelCoveredByMarker(top + height / 2, it, height / 2) }
            if (stacked) {
                val minorStyle = MaterialTheme.typography.labelSmall
                val minorLine = metrics.lineDp(minorStyle)
                fun covered(top: Float, height: Float) = stackedLabelCovered(top, height)
                layout.minorLabels.forEachIndexed { i, mark ->
                    val top = (mark.topDp - minorLine).coerceAtLeast(0f)
                    if (!covered(top, minorLine)) StackedTimeLabel(minorLabels[i], minorStyle, top, MinorLabelTag)
                }
            }
            placedMarkers.filterNot { it.overBlocks }.forEach { TrackingMarkerLabel(it, markerLineTexts[it.marker].orEmpty(), ordered, timeColumn, DayCanvasDefaults.LabelGap, zone, stacked) }
            // Blocks first, trips after: a trip's 48 dp touch target may reach over the visits' edges and wins taps there.
            // Resize handles are composed after both, so they win over a trip. The index is the time-order traversal index.
            ordered.withIndex().sortedBy { it.value is PlacedTrip }.forEach { (index, placed) ->
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
                    .padding(start = timeColumn + DayCanvasDefaults.BlockInset, end = visitEnd)
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
                                item, shown as? PlacedBlock ?: placed, times, description, index, fontScale, dimmed, position, blockWidth = maxWidth - timeColumn - DayCanvasDefaults.BlockInset - visitEnd,
                                selected = if (selecting) item.id in selectedIds else item.id == selectedVisitId,
                                selecting = selecting,
                                onSelect = { onSelectVisit(item.id) },
                                onOpen = { onOpenVisit(item.id) },
                                onLongPress = { onLongPressVisit(item.id) },
                                onToggle = { onToggleVisit(item.id) },
                            )
                            // Trips and gaps are not selectable: while selecting they are disabled, so they expose no "Open" action either.
                            is TimelineItem.Gap -> GapBlock(item, placed, times, description, index, fontScale, dimmed, position, enabled = !selecting) { onOpenGap(item, placed.cluster) }
                            is TimelineItem.Trip -> Unit // Trips are placed as PlacedTrip, never as blocks.
                        }
                    }
                    is PlacedTrip -> TripRow(
                        placed.trip,
                        clampedTimes(placed.trip, dayStart, dayEnd, zone),
                        spokenDescription(placed.trip, dayStart, dayEnd, zone),
                        index,
                        dimmed,
                        placed.topDp,
                        placed.heightDp,
                        timeColumn + DayCanvasDefaults.BlockInset,
                        enabled = !selecting,
                        iconOnly = placed.iconOnly,
                    ) { onOpenTrip(placed.trip.id) }
                }
            }
            // Large-font hour labels, over the blocks on their card backdrop (resize handles still win, composed after).
            if (stacked) {
                layout.hourMarks.forEachIndexed { i, mark ->
                    val top = (mark.topDp - hourLineDp).coerceAtLeast(0f)
                    if (!stackedLabelCovered(top, hourLineDp)) StackedTimeLabel(hourLabels[i], hourStyle, top, HourLabelTag)
                }
            }
            // Resize handles of the selected visit, above every block. An edge cut by midnight has no handle.
            val selectedBlock = selectedVisitId?.takeUnless { selecting }?.let { id -> layout.blocks.firstOrNull { it.item.id == id && it.cluster.isEmpty() } }
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
                        outside = handlesOutside(selectedBlock.heightDp, DayCanvasDefaults.HandleCardTextDp * fontScale, selectedBlock.topDp, layout.heightDp),
                        start = handleStart,
                        end = visitEnd,
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
                    ResizeLabel(formats.time(d.edgeMs, zone), edgeDp, handleStart, visitEnd)
                }
            }
            // A large-font label with blocks on both sides of its tick is drawn over them, on its card backdrop.
            placedMarkers.filter { it.overBlocks }.forEach { TrackingMarkerLabel(it, markerLineTexts[it.marker].orEmpty(), ordered, timeColumn, DayCanvasDefaults.LabelGap, zone, stacked) }
            layout.nowDp?.let { nowDp ->
                NowLine(nowDp, now, zone, ordered.count { it.topDp < nowDp } - 0.5f, timeColumn, nowPillHalfHeightDp(hourLineDp).dp)
            }
            overlay()
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
    blockWidth: Dp,
    selected: Boolean,
    selecting: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggle: () -> Unit,
) {
    val c = TarryTheme.colors
    val shape = if (placed.compact) TarryTheme.shapes.small else TarryTheme.shapes.block
    val container = c.card
    val unknown = stringResource(R.string.timeline_unknown_place)
    val openLabel = stringResource(R.string.timeline_edit_visit)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentOpen by rememberUpdatedState(onOpen)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentToggle by rememberUpdatedState(onToggle)
    val toggleLabel = stringResource(R.string.timeline_toggle_selection)
    val bulkLabel = stringResource(R.string.timeline_select_for_bulk)
    val title = visit.placeLabel ?: unknown
    val oneLine = placed.compact || placed.heightDp < DayCanvasDefaults.OneLineBelowDp * fontScale
    val showChip = !oneLine && visit.needsReview && placed.heightDp >= DayCanvasDefaults.ChipMinDp * fontScale

    // Outage dots sit on top of the left border, outside the block clip.
    val dots = Modifier.innerGapDots(placed.innerGaps)
    val notTracked = notTrackedText(placed.innerGaps, times)
    val d = DayCanvasDefaults
    // How many lines each text gets: real line heights for this font scale, real wrapping at this width. Everything shown
    // keeps one line first; the height left over goes to the title, then the times, then the warning (see [allocateLines]).
    val metrics = rememberTextMetrics()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val textWidthPx = with(density) {
        (blockWidth - TarryTheme.space.m * 2 - (if (selected && selecting) d.CheckBadge + TarryTheme.space.xs else 0.dp)).roundToPx()
    }.coerceAtLeast(0)
    fun lineDp(style: TextStyle) = metrics.lineDp(style)
    fun linesNeeded(text: String, style: TextStyle, widthPx: Int = textWidthPx) =
        metrics.lineCount(text, style, widthPx)
    val titleLine = lineDp(typography.titleMedium)
    val detailLine = lineDp(typography.bodyMedium)
    val warnLine = lineDp(typography.bodySmall)
    val chipHeight = if (showChip) lineDp(typography.labelMedium) + d.ChipExtraDp else 0f
    val roomForSummary = d.BlockPaddingV.value * 2 + titleLine + detailLine + chipHeight + warnLine
    val summary = notTracked?.takeIf { !oneLine && placed.heightDp >= roomForSummary }
    val summaryText = summary?.replaceFirstChar { it.uppercase() }
    val summaryWidthPx = with(density) { (d.InnerGapIconSize + TarryTheme.space.xs).roundToPx() }
    val shownLines = if (oneLine) listOf(1, 1, 1) else {
        val fixed = d.BlockPaddingV.value * 2 + titleLine + detailLine + chipHeight + (if (summary != null) warnLine else 0f)
        allocateLines(
            extraDp = placed.heightDp - fixed,
            lineDps = listOf(titleLine, detailLine, warnLine),
            needs = listOf(
                linesNeeded(title, typography.titleMedium),
                linesNeeded(times.range, typography.bodyMedium),
                if (summaryText != null) linesNeeded(summaryText, typography.bodySmall, (textWidthPx - summaryWidthPx).coerceAtLeast(0)) else 1,
            ),
        )
    }
    val (titleLines, detailLines, summaryLines) = shownLines
    var focused by remember { mutableStateOf(false) }
    // A visit no job block covers keeps the dashed "needs review" outline; a covered one is a plain card.
    val look = if (visit.needsReview) {
        Modifier.clip(shape).background(container).dashedBorder(c.outline, shape)
    } else {
        Modifier.clip(shape).background(container).border(DayCanvasDefaults.BorderWidth, c.outline, shape)
    }
    Box(
        modifier = modifier
            .then(dots)
            .then(look)
            // Single tap selects (confirmed after the double-tap timeout), double tap opens the drawer, long press enters
            // multi-select. While selecting, a tap just toggles this block. TalkBack's activate opens the drawer directly.
            .pointerInput(selecting) {
                if (selecting) {
                    detectTapGestures(onTap = { currentToggle() })
                } else {
                    detectTapGestures(
                        onTap = { currentSelect() },
                        onDoubleTap = { currentOpen() },
                        onLongPress = { currentLongPress() },
                    )
                }
            }
            // Keyboard / switch access (WCAG 2.1.1): focusable, with a visible ink ring; Enter or Space opens the drawer.
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .then(if (focused) Modifier.border(DayCanvasDefaults.BorderWidth * 2, c.ink, shape) else Modifier)
            .onKeyEvent { e ->
                val activate = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar
                if (activate && e.type == KeyEventType.KeyUp) { if (selecting) currentToggle() else currentOpen() }
                activate
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = if (selecting) toggleLabel else openLabel) {
                    if (selecting) currentToggle() else currentOpen()
                    true
                }
                if (!selecting) {
                    onLongClick(label = bulkLabel) {
                        currentLongPress()
                        true
                    }
                }
                this.selected = selected
                contentDescription = description
                traversalIndex = index.toFloat()
                blockDimmed = dimmed
                blockColor = container
                blockDashed = visit.needsReview
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
                end = TarryTheme.space.m + (if (selected && selecting) DayCanvasDefaults.CheckBadge + TarryTheme.space.xs else 0.dp),
                top = if (oneLine) DayCanvasDefaults.OneLinePaddingV else DayCanvasDefaults.BlockPaddingV,
                bottom = if (oneLine) DayCanvasDefaults.OneLinePaddingV else DayCanvasDefaults.BlockPaddingV,
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        if (oneLine) {
            Text(
                text = stringResource(R.string.timeline_compact_line, title, formatDuration(times.durationMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = times.range,
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
                maxLines = detailLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
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
                        text = summaryText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onAlertSoft,
                        maxLines = summaryLines,
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
    if (selected && selecting) {
        // Selected look from the theme: a heavier ink ring plus an ink badge with a check (state is also in semantics).
        Box(Modifier.matchParentSize().border(DayCanvasDefaults.BorderWidth * 2, c.ink, shape))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(DayCanvasDefaults.CheckInset)
                .size(DayCanvasDefaults.CheckBadge)
                .clip(CircleShape)
                .background(c.ink),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = c.onInk,
                modifier = Modifier.size(DayCanvasDefaults.CheckIcon),
            )
        }
    }
    }
}


/**
 * Splits a block's spare height over its texts. Every text starts with one line; [extraDp] (the height left once those
 * are counted, and anything else fixed) then goes to the first text until it has the lines it [needs] or the next line
 * would not fit, then to the second, and so on. A text therefore only ends in an ellipsis when the block truly has no
 * more room for it.
 */
internal fun allocateLines(extraDp: Float, lineDps: List<Float>, needs: List<Int>): List<Int> {
    val lines = MutableList(lineDps.size) { 1 }
    var left = extraDp
    for (i in lineDps.indices) {
        while (lines[i] < needs[i] && lineDps[i] > 0f && left >= lineDps[i]) {
            lines[i]++
            left -= lineDps[i]
        }
    }
    return lines
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
    enabled: Boolean,
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
            // Not merely disabled: a disabled clickable still exposes its click action, so it is left off while selecting.
            .then(if (enabled) Modifier.clickable(onClickLabel = stringResource(R.string.timeline_show_details), role = Role.Button, onClick = onClick) else Modifier)
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

/**
 * A trip: mode icon + compact text on the canvas, left-aligned at the visit column's content inset, no outline, no
 * fill. It sits in its slot ([slotTopDp], [slotHeightDp]) between the visits and never paints over them. The touch
 * target is at least 48 dp tall and wide and is centred on the slot, so it may reach invisibly over the neighbours'
 * edges. With [iconOnly] the slot is too small for text and only the icon is drawn (the description still speaks it all).
 */
@Composable
private fun TripRow(
    trip: TimelineItem.Trip,
    times: ClampedTimes,
    description: String,
    index: Int,
    dimmed: Boolean,
    slotTopDp: Float,
    slotHeightDp: Float,
    start: Dp,
    enabled: Boolean,
    iconOnly: Boolean,
    onClick: () -> Unit,
) {
    val c = TarryTheme.colors
    val text = stringResource(R.string.timeline_trip_compact, formatDistance(trip.distanceM), formatDuration(times.durationMs))
    val touch = maxOf(slotHeightDp.dp, DayCanvasDefaults.TripTouch)
    Row(
        modifier = Modifier
            .offset(y = slotTopDp.dp + (slotHeightDp.dp - touch) / 2)
            .padding(start = start)
            .height(touch)
            .widthIn(min = DayCanvasDefaults.TripTouch)
            .then(if (dimmed) Modifier.alpha(DimmedAlpha) else Modifier)
            .then(if (enabled) Modifier.clickable(onClickLabel = stringResource(R.string.timeline_edit_trip), role = Role.Button, onClick = onClick) else Modifier)
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
            painter = painterResource(trip.mode.iconRes()),
            contentDescription = null,
            tint = c.muted,
            // A card-coloured disc behind the glyph breaks the connector line, so the line never runs through the icon.
            modifier = Modifier.background(TarryTheme.colors.card, CircleShape).padding(DayCanvasDefaults.TripIconBackdrop).size(DayCanvasDefaults.TripIconSize),
        )
        if (!iconOnly) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Walk and drive have their own glyph; an unknown mode gets the neutral route icon, never the car. */
internal fun TripMode.iconRes(): Int = when (this) {
    TripMode.WALK -> R.drawable.ic_walk
    TripMode.VEHICLE -> R.drawable.ic_car
    TripMode.UNKNOWN -> R.drawable.ic_route
}

/** True when the now-pill would overlap the hour label at [markDp]; that label is hidden, as calendars do. */
internal fun hourLabelCoveredByNowPill(markDp: Float, nowDp: Float?, fontScale: Float): Boolean {
    if (nowDp == null) return false
    val reach = (DayCanvasDefaults.NowPillHalfHeight + DayCanvasDefaults.HourLabelHalfLine).value * fontScale
    return abs(markDp - nowDp) < reach
}

/** Half the now-pill's real height: one text line plus its vertical padding. Marker labels keep clear of this span and the pill is drawn with it. */
internal fun nowPillHalfHeightDp(lineDp: Float): Float = (lineDp + DayCanvasDefaults.PillPaddingV.value * 2) / 2

@Composable
private fun NowLine(nowDp: Float, now: Long, zone: ZoneId, traversal: Float, timeColumn: Dp, pillHalf: Dp) {
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
            .offset(y = nowDp.dp - pillHalf)
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

internal fun Modifier.dashedBorder(color: Color, shape: RoundedCornerShape, width: Dp = DayCanvasDefaults.BorderWidth): Modifier =
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

/**
 * The label of a tracking start/stop mark in the time column: the word over the time ("Start" / "7:30", `bodySmall`,
 * muted, right-aligned like the hour labels), centred on its time. It has no pointer input. TalkBack gets one node with
 * the full sentence ("Tracking stopped 6:30 PM", with the end reason's wording) instead of the two lines, in time
 * order between the blocks it falls between.
 */
@Composable
private fun TrackingMarkerLabel(placed: PlacedMarker, lines: List<String>, ordered: List<Placed>, timeColumn: Dp, labelGap: Dp, zone: ZoneId, stacked: Boolean) {
    val formats = rememberTimeFormats()
    val m = placed.marker
    val stopped = m.stoppedMs?.let { formats.time(it, zone) }
    val started = m.startedMs?.let { formats.time(it, zone) }
    val spoken = when {
        stopped != null && started != null -> stringResource(R.string.timeline_tracking_restarted, stopped, started)
        stopped != null -> stringResource(
            when (m.reason) {
                SessionEndReason.USER_PAUSE -> R.string.timeline_tracking_paused
                SessionEndReason.SCHEDULE_END -> R.string.timeline_tracking_ended
                SessionEndReason.KILLED -> R.string.timeline_tracking_interrupted
                SessionEndReason.PERMISSION_LOST -> R.string.timeline_tracking_no_permission
                else -> R.string.timeline_tracking_stopped
            },
            stopped,
        )
        else -> stringResource(R.string.timeline_tracking_started, started.orEmpty())
    }
    val traversal = ordered.count { (if (it is PlacedBlock) it.item.start else (it as PlacedTrip).trip.start) < m.timeMs } - 0.5f
    Column(
        horizontalAlignment = if (stacked) Alignment.Start else Alignment.End,
        modifier = Modifier
            .offset(y = placed.labelTopDp.dp)
            // Collapsed time column: the label is as wide as its text, over the canvas on a card background.
            .then(if (stacked) Modifier.background(TarryTheme.colors.card).padding(horizontal = DayCanvasDefaults.StackedLabelPadding) else Modifier.width(timeColumn - labelGap))
            .clearAndSetSemantics {
                contentDescription = spoken
                traversalIndex = traversal
                testTag = "timeline_tracking_marker"
            },
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = TarryTheme.colors.muted,
                textAlign = if (stacked) TextAlign.Start else TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("timeline_tracking_marker_line"),
            )
        }
    }
}

/** Hour rules and hour ticks: `outline`, which is at least 3:1 on `card` in both themes. */
internal fun hourRuleColor(c: TarryColors): Color = c.outline

/** Minor marks (15, 10 and 5 minutes) stay the quiet `divider` (the `line` colour at 0.8) so they never compete with the hours. */
internal fun minorTickColor(c: TarryColors): Color = c.divider.copy(alpha = c.divider.alpha * DayCanvasDefaults.QuarterTickAlpha)

/** An hour or minor time label of the collapsed time column: just above its line at the canvas's left padding, on `card` so it reads over blocks. */
@Composable
private fun StackedTimeLabel(text: String, style: TextStyle, topDp: Float, tag: String) {
    Text(
        text = text,
        style = style,
        color = TarryTheme.colors.muted,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .offset(y = topDp.dp)
            .background(TarryTheme.colors.card)
            .padding(horizontal = DayCanvasDefaults.StackedLabelPadding)
            // Decorative: the blocks carry their own times.
            .semantics {
                hideFromAccessibility()
                testTag = tag
            },
    )
}

/**
 * The span (dp from the canvas top) of a trip's dotted connector: from the bottom of the visit before it to the top of
 * the visit after it, never shorter than the trip's own slot. Without a visit on a side, the slot's edge ends it.
 */
internal fun tripConnector(trip: PlacedTrip, blocks: List<PlacedBlock>): ClosedFloatingPointRange<Float> {
    val centre = trip.topDp + trip.heightDp / 2
    val visits = blocks.filter { it.item is TimelineItem.Visit }
    val before = visits.filter { it.topDp + it.heightDp <= centre }.maxOfOrNull { it.topDp + it.heightDp }
    val after = visits.filter { it.topDp >= centre }.minOfOrNull { it.topDp }
    return minOf(trip.topDp, before ?: trip.topDp)..maxOf(trip.topDp + trip.heightDp, after ?: (trip.topDp + trip.heightDp))
}

/** An [OffStretch] clamped to the canvas: where it sits (dp from the canvas top) and how tall it is. */
internal data class PlacedOff(val stretch: OffStretch, val topDp: Float, val heightDp: Float)

/**
 * Whether the "Not tracking" label fits at the top of [off]: the stretch is tall enough for it, and no visit, gap or
 * trip covers the label's rows (a gap wins over the stretch, so the label never sits on one).
 */
internal fun offLabelFits(off: PlacedOff, labelHeightDp: Float, placed: List<Placed>): Boolean {
    val top = off.topDp + OffLabelInsetDp
    val bottom = top + labelHeightDp
    return off.heightDp >= labelHeightDp + 2 * OffLabelInsetDp && placed.none { it.topDp < bottom && it.topDp + it.heightDp > top }
}

private const val OffLabelInsetDp = 2f
