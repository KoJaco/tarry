package me.tarry.app.ui.timeline

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import me.tarry.app.ui.components.rememberReducedMotion
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.map.LargeFontScale
import me.tarry.app.map.ScrubItem
import me.tarry.app.map.ScrubKind
import me.tarry.app.map.ScrubberModel
import me.tarry.app.map.blockExtent
import me.tarry.app.map.hourLabel
import me.tarry.app.map.hourLabelStride
import me.tarry.app.map.hourMarks
import me.tarry.app.map.thinned
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.drawer.TimePickerDialog
import me.tarry.app.ui.theme.JobColors
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.theme.disabledIcon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val MinTouch = 48.dp
private val StepTouch = 48.dp
private val StepIcon = 20.dp
private val TrackInset = 10.dp
private val LineWidth = 3.dp
private val GapDash = 4.dp
private val DotRing = 1.5.dp
private val ThumbRadius = 8.dp
/** The bar's drag row, and the hour labels under it: together the strip's 64 dp. */
private val BarRow = 48.dp
private val LabelRow = 16.dp
private val BlockHeight = 14.dp
private val BlockCorner = 4.dp
private val BlockMinWidth = 4.dp
private val BlockStroke = 1.5.dp
private val VisitDash = 4.dp
private val VisitDashGap = 3.dp
private val TickLength = 5.dp
private val TickGap = 2.dp
/** The time pill gets a band of its own, the full touch height, so it never overlaps the card or the strip. */
private val PillBand = MinTouch
/** Minimised, the card surface starts this far down the pill band: the empty top of the band stays a touch target but shows map. */
private val MinimisedSurfaceInset = 16.dp
/** Fill strength of strip items other than the one under the thumb; outlines and lines stay full strength (>= 3:1). */
private const val FadedAlpha = 0.4f
/** The line under the thumb is this much thicker, so it stands out without fading the others. */
private val ActiveLineExtra = 1.5.dp
private const val OverlayMotionMillis = 200
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
/** A vertical swipe must travel this far (and mostly vertically) to minimise or maximise; a scrub never does. */
private val SwipeThreshold = 32.dp

/** The scrubber's rows: [TimelineItem]s as [ScrubItem]s (same ids, so a tapped visit block opens its drawer). */
internal fun List<TimelineItem>.toScrubItems(): List<ScrubItem> = map {
    val kind = when (it) {
        is TimelineItem.Visit -> ScrubKind.VISIT
        is TimelineItem.Trip -> ScrubKind.TRIP
        is TimelineItem.Gap -> ScrubKind.GAP
    }
    ScrubItem(it.id, kind, it.start, it.end)
}

/**
 * The map's time scrubber (MVP § Map view), drawn as an overlay: a card for the item under the thumb, then the day as a
 * strip in proportion to time (visits as blocks in their job colour, dashed when they have no job; trips `muted`; gaps
 * dashed `alert`; hour ticks under it), with the [time] thumb and its label above. Dragging moves [time]
 * live ([onTimeChange]) and snaps to the nearest visit start on release. Tapping a visit block opens it
 * ([onOpenVisit]). Previous / next visit buttons and TalkBack actions are the non-drag alternative (WCAG 2.5.7).
 * Tapping the time label opens the date-time jump: a day, then a time; [onJump] gets that instant and its date.
 * The card's text and the strip's description change when the thumb settles, not on every drag frame.
 */
@Composable
internal fun DayScrubber(
    model: ScrubberModel,
    /** The job block covering each visit (by visit id), to colour its segment; a visit without one is neutral. */
    visitJobs: Map<String, JobBlockItem>,
    time: Long,
    onTimeChange: (Long) -> Unit,
    onOpenVisit: (String) -> Unit,
    date: LocalDate,
    today: LocalDate,
    zone: ZoneId,
    onJump: (date: LocalDate, epochMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** The day's rows, for the card (names, distance, review state); a row missing here leaves the card blank. */
    items: List<TimelineItem> = emptyList(),
    /** The card (or its Open button) was used on a trip. */
    onOpenTrip: (String) -> Unit = {},
    /** "Add job" on a visit that needs review. */
    onAddJob: (TimelineItem.Visit) -> Unit = {},
    /** Minimised: only the time pill and the strip show (no card), with a button on the right to show details again. */
    minimised: Boolean = false,
    onMinimisedChange: (Boolean) -> Unit = {},
) {
    val c = TarryTheme.colors
    val dark = TarryTheme.isDark
    val reducedMotion = rememberReducedMotion()
    // 0 = expanded, 1 = minimised: drives the strip row's room for the maximise button. Instant under reduced motion.
    val minimisedProgress by animateFloatAsState(
        targetValue = if (minimised) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(OverlayMotionMillis, easing = EmphasizedEasing),
        label = "overlayMinimised",
    )
    // After a toggle the pressed button is gone: focus moves to its counterpart, never left on nothing.
    var toggled by remember { mutableStateOf(false) }
    val minimiseFocus = remember { FocusRequester() }
    val maximiseFocus = remember { FocusRequester() }
    LaunchedEffect(minimised) {
        if (toggled) runCatching { (if (minimised) maximiseFocus else minimiseFocus).requestFocus() }
    }
    val minimisedChange by rememberUpdatedState(onMinimisedChange)
    val setMinimised: (Boolean) -> Unit = { toggled = true; minimisedChange(it) }
    val formats = rememberTimeFormats()
    val timeText = formats.time(time, zone)
    val current by rememberUpdatedState(time)
    // Gestures read the latest model instead of restarting on it: today it is rebuilt as pings arrive, mid-drag.
    val latestModel by rememberUpdatedState(model)
    val openVisit by rememberUpdatedState(onOpenVisit)
    val change by rememberUpdatedState(onTimeChange)
    var widthPx by remember { mutableStateOf(0) }
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    var pickedDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    // The time the description and the card announce: follows [time] except while a drag is in progress.
    var dragging by remember { mutableStateOf(false) }
    var settled by remember { mutableLongStateOf(time) }
    LaunchedEffect(dragging, time) { if (!dragging) settled = time }
    val jobColors = remember(visitJobs, dark) {
        visitJobs.mapValues { (_, b) -> JobPalette.lookup(b.jobColorArgb, b.jobId).colors(dark) }
    }
    val itemsById = remember(items) { items.associateBy { it.id } }
    val previous = model.previous(time)
    val next = model.next(time)
    val previousLabel = stringResource(R.string.map_scrub_previous_visit)
    val nextLabel = stringResource(R.string.map_scrub_next_visit)

    val density = LocalDensity.current
    val inset = with(density) { TrackInset.toPx() }
    /** Strip x (px) → 0..1 along the track, which is inset so the end blocks and the thumb aren't clipped. */
    fun fractionOf(x: Float): Float = if (widthPx <= 2 * inset) 0f else ((x - inset) / (widthPx - 2 * inset)).coerceIn(0f, 1f)

    // Hour labels: whole hours inside the worked span, thinned when they would crowd or text is large.
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = c.muted)
    val measurer = rememberTextMeasurer()
    val range = model.range
    val labels: List<Pair<Long, TextLayoutResult>> = remember(range, zone, formats.is24Hour, labelStyle, density) {
        val marks = if (range == null) emptyList() else hourMarks(range.start, range.end, zone)
        marks.map { it to measurer.measure(hourLabel(it, zone, formats.is24Hour), labelStyle) }
    }
    val labelHeightDp = with(density) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
    // The labels hang under the blocks and ticks (centred on the bar): the bar's half, the block's half, tick and gaps.
    val stripHeight = maxOf(BarRow + LabelRow, BarRow / 2 + BlockHeight / 2 + TickLength + TickGap * 2 + labelHeightDp)
    val shownLabels = run {
        val length = range?.let { it.end - it.start } ?: 0L
        val trackDp = (widthPx - 2 * inset) / density.density
        val spacingDp = if (length <= 0L || widthPx <= 0) Float.MAX_VALUE else trackDp * 3_600_000f / length
        thinned(labels, hourLabelStride(spacingDp, density.fontScale))
    }

    // What TalkBack hears for the strip and the card, from the settled time only (never per drag frame).
    val settledItem = remember(settled, model, itemsById) { model.itemAt(settled)?.let { itemsById[it.id] } }
    val (dayStart, dayEnd) = remember(date, zone) { dayBounds(date, zone) }
    val (visitCount, tripCount) = remember(model) {
        model.blocks.count { it.item.kind == ScrubKind.VISIT } to model.blocks.count { it.item.kind == ScrubKind.TRIP }
    }
    val stripDescription = if (range == null) "" else {
        val head = stringResource(
            R.string.map_scrub_strip_description,
            formats.time(range.start, zone), formats.time(range.end, zone),
            pluralStringResource(R.plurals.map_visit_count, visitCount, visitCount),
            pluralStringResource(R.plurals.map_trip_count, tripCount, tripCount),
        )
        val at = settledItem?.let { itemLabel(it) }
        if (at == null) head else "$head ${stringResource(R.string.map_scrub_strip_at, formats.time(settled, zone), at)}"
    }
    val cardDescription = settledItem?.let { spokenDescription(it, dayStart, dayEnd, zone) }.orEmpty()

    // The item under the thumb, looked up once per frame for the card and the strip's emphasis.
    val liveScrub = model.itemAt(time)
    val live = liveScrub?.let { itemsById[it.id] }

    // Drawing objects made once, not per block per frame.
    val gapEffect = remember(density) { PathEffect.dashPathEffect(floatArrayOf(with(density) { GapDash.toPx() }, with(density) { GapDash.toPx() })) }
    val visitStroke = remember(density) {
        Stroke(
            with(density) { BlockStroke.toPx() },
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(with(density) { VisitDash.toPx() }, with(density) { VisitDashGap.toPx() })),
        )
    }
    val solidStroke = remember(density) { Stroke(with(density) { BlockStroke.toPx() }) }
    val containerShape = TarryTheme.shapes.container

    Column(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("map_overlay")
                .drawBehind {
                    // Minimised, the surface starts lower so more map shows; the pill band above it keeps its 48 dp target.
                    val top = MinimisedSurfaceInset.toPx() * minimisedProgress
                    val outline = containerShape.createOutline(size.copy(height = size.height - top), layoutDirection, this)
                    translate(top = top) { drawOutline(outline, c.card) }
                }
                .overlaySwipe(with(density) { SwipeThreshold.toPx() }) { down -> setMinimised(down) }
                .padding(bottom = TarryTheme.space.xs),
        ) {
            // The card collapses away when minimised; its size animates (instantly under reduced motion).
            AnimatedVisibility(
                visible = !minimised,
                enter = if (reducedMotion) EnterTransition.None else
                    fadeIn(tween(OverlayMotionMillis, easing = EmphasizedEasing)) + expandVertically(tween(OverlayMotionMillis, easing = EmphasizedEasing)),
                exit = if (reducedMotion) ExitTransition.None else
                    fadeOut(tween(OverlayMotionMillis, easing = EmphasizedEasing)) + shrinkVertically(tween(OverlayMotionMillis, easing = EmphasizedEasing)),
            ) {
                SelectedItemCard(
                    item = live,
                    items = items,
                    job = (live as? TimelineItem.Visit)?.let { visitJobs[it.id] },
                    jobColors = (live as? TimelineItem.Visit)?.let { jobColors[it.id] },
                    description = cardDescription,
                    dayStart = dayStart,
                    dayEnd = dayEnd,
                    zone = zone,
                    onOpenVisit = onOpenVisit,
                    onOpenTrip = onOpenTrip,
                    onAddJob = onAddJob,
                    onMinimise = { setMinimised(true) },
                    minimiseFocus = minimiseFocus,
                    // Expanded: the standard 12 dp above the card row (the minimised overlay keeps its slimmer top).
                    modifier = Modifier.padding(start = TarryTheme.space.l, end = TarryTheme.space.xs, top = TarryTheme.space.m),
                )
            }
            // The time label floats above the thumb in a 48 dp band of its own: the whole band is the touch target, so
            // it never overlaps the card above or the strip below. Its right edge follows the strip's.
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(PillBand)
                    .padding(start = StepTouch + TarryTheme.space.xs, end = StepTouch + TarryTheme.space.xs + MinTouch * minimisedProgress),
            ) {
                var labelWidth by remember { mutableStateOf(0) }
                val trackPx = constraints.maxWidth - 2 * inset
                val thumbX = inset + model.fractionAt(time) * trackPx
                val x = (thumbX - labelWidth / 2f).roundToInt().coerceIn(0, maxOf(0, constraints.maxWidth - labelWidth))
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x, 0) }
                        .onSizeChanged { labelWidth = it.width }
                        .fillMaxHeight()
                        .widthIn(min = MinTouch)
                        .clickable(onClickLabel = stringResource(R.string.map_scrub_jump), role = Role.Button) { pickingDay = true }
                        .testTag("map_scrub_time"),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Text(
                        timeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = c.onInk,
                        modifier = Modifier
                            .padding(bottom = TarryTheme.space.xs)
                            .background(c.ink, TarryTheme.shapes.pill)
                            .padding(horizontal = TarryTheme.space.s, vertical = 2.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TarryTheme.space.xs),
                verticalAlignment = Alignment.Top,
            ) {
                StepButton(R.drawable.ic_chevron_left, previousLabel, enabled = previous != null, Modifier.testTag("map_scrub_previous")) {
                    previous?.let(onTimeChange)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(stripHeight)
                        .onSizeChanged { widthPx = it.width }
                        .semantics {
                            contentDescription = stripDescription
                            stateDescription = timeText
                            progressBarRangeInfo = ProgressBarRangeInfo(model.fractionAt(current), 0f..1f)
                            setProgress { f -> change(model.snap(model.timeAt(f))); true }
                            customActions = listOfNotNull(
                                previous?.let { t -> CustomAccessibilityAction(previousLabel) { change(t); true } },
                                next?.let { t -> CustomAccessibilityAction(nextLabel) { change(t); true } },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { p ->
                                if (widthPx <= 0) return@detectTapGestures
                                val t = latestModel.timeAt(fractionOf(p.x))
                                val hit = latestModel.itemAt(t)
                                if (hit?.kind == ScrubKind.VISIT) openVisit(hit.id) else change(latestModel.snap(t))
                            }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = {
                                    dragging = false
                                    change(latestModel.snap(current))
                                },
                                onDragCancel = { dragging = false },
                            ) { pointer, _ ->
                                if (widthPx > 0) change(latestModel.timeAt(fractionOf(pointer.position.x)))
                            }
                        }
                        .testTag("map_scrubber"),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val y = BarRow.toPx() / 2
                        val track = size.width - 2 * inset
                        fun xAt(f: Float) = inset + f * track
                        // One line for the worked span with the day's items on it. Only fills fade for the items the
                        // thumb is not on; outlines and lines keep full strength so they hold 3:1 against the card
                        // (muted, alert and outline pass in both themes; a job border under 3:1 becomes `outline`).
                        val activeId = liveScrub?.id
                        fun Color.fade(id: String) = if (id == activeId) this else copy(alpha = alpha * FadedAlpha)
                        fun lineWidth(id: String) = LineWidth.toPx() + if (id == activeId) ActiveLineExtra.toPx() else 0f
                        drawLine(c.outline, Offset(xAt(0f), y), Offset(xAt(1f), y), LineWidth.toPx(), StrokeCap.Round)
                        model.blocks.forEach { block ->
                            val from = Offset(xAt(block.startFraction), y)
                            val to = Offset(xAt(block.endFraction), y)
                            val id = block.item.id
                            when (block.item.kind) {
                                ScrubKind.TRIP -> drawLine(c.muted, from, to, lineWidth(id), StrokeCap.Round)
                                ScrubKind.GAP -> drawLine(c.alert, from, to, lineWidth(id), pathEffect = gapEffect)
                                ScrubKind.VISIT -> {
                                    val (left, width) = blockExtent(from.x, to.x, BlockMinWidth.toPx())
                                    val topLeft = Offset(left, y - BlockHeight.toPx() / 2)
                                    val size = Size(width, BlockHeight.toPx())
                                    val corner = CornerRadius(BlockCorner.toPx())
                                    val job = jobColors[id]
                                    if (job != null) {
                                        drawRoundRect(job.container.fade(id), topLeft, size, corner)
                                        drawRoundRect(suggestionBorder(job.border, c), topLeft, size, corner, style = solidStroke)
                                    } else {
                                        // No job (needs review): card fill and a dashed outline, as on the timeline.
                                        drawRoundRect(c.card.fade(id), topLeft, size, corner)
                                        drawRoundRect(c.outline, topLeft, size, corner, style = visitStroke)
                                    }
                                }
                            }
                        }
                        // Hour ticks under the bar and their labels.
                        val tickTop = y + BlockHeight.toPx() / 2 + TickGap.toPx()
                        val labelTop = tickTop + TickLength.toPx() + TickGap.toPx()
                        shownLabels.forEach { (ms, layout) ->
                            val x = xAt(model.fractionAt(ms))
                            drawLine(c.outline, Offset(x, tickTop), Offset(x, tickTop + TickLength.toPx()), 1.dp.toPx())
                            val maxLeft = maxOf(0f, size.width - layout.size.width)
                            drawText(layout, topLeft = Offset((x - layout.size.width / 2f).coerceIn(0f, maxLeft), labelTop))
                        }
                        val thumb = Offset(xAt(model.fractionAt(current)), y)
                        drawCircle(c.card, ThumbRadius.toPx() + DotRing.toPx(), thumb)
                        drawCircle(c.ink, ThumbRadius.toPx(), thumb)
                    }
                }
                StepButton(R.drawable.ic_chevron_right, nextLabel, enabled = next != null, Modifier.testTag("map_scrub_next")) {
                    next?.let(onTimeChange)
                }
                // Minimised: the strip makes room on the right for the button that brings the details back.
                if (minimised || minimisedProgress > 0f) {
                    val showLabel = stringResource(R.string.map_overlay_show)
                    val hiddenState = stringResource(R.string.map_overlay_state_hidden)
                    Box(
                        Modifier
                            .width(MinTouch * minimisedProgress)
                            .height(MinTouch)
                            .clipToBounds()
                            .graphicsLayer { alpha = minimisedProgress; translationX = (1f - minimisedProgress) * 8.dp.toPx() },
                    ) {
                        IconCircleButton(
                            icon = R.drawable.ic_chevron_up,
                            contentDescription = showLabel,
                            onClick = { setMinimised(false) },
                            containerColor = c.inset,
                            modifier = Modifier
                                .wrapContentWidth(Alignment.Start, unbounded = true)
                                .semantics { stateDescription = hiddenState }
                                .focusRequester(maximiseFocus)
                                .testTag("map_scrub_maximise"),
                        )
                    }
                }
            }
        }
    }

    if (pickingDay) {
        DayPickerDialog(date, today, onDismiss = { pickingDay = false }, onPick = { pickedDay = it })
    }
    pickedDay?.let { day ->
        TimePickerDialog(
            title = stringResource(R.string.map_scrub_jump_time),
            initialMs = time,
            zone = zone,
            is24Hour = formats.is24Hour,
            onConfirm = { h, m ->
                pickedDay = null
                onJump(day, day.atTime(h, m).atZone(zone).toInstant().toEpochMilli())
            },
            onDismiss = { pickedDay = null },
        )
    }
}

/**
 * A vertical swipe on the overlay: down calls [onSwipe] with true (minimise), up with false (maximise). It must travel
 * [thresholdPx] and be mostly vertical, so the strip's horizontal scrub (which consumes its moves) never triggers it.
 * The buttons are the accessible alternative (WCAG 2.5.7).
 */
private fun Modifier.overlaySwipe(thresholdPx: Float, onSwipe: (down: Boolean) -> Unit): Modifier = pointerInput(thresholdPx) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        var dx = 0f
        var dy = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == first.id } ?: return@awaitEachGesture
            if (change.isConsumed || !change.pressed) return@awaitEachGesture
            val delta = change.positionChange()
            dx += delta.x
            dy += delta.y
            if (abs(dx) > thresholdPx / 2 && abs(dx) >= abs(dy)) return@awaitEachGesture
            if (abs(dy) > thresholdPx && abs(dy) > 2 * abs(dx)) {
                change.consume()
                onSwipe(dy > 0)
                return@awaitEachGesture
            }
        }
    }
}

/** The spoken name of an item: the visit's place, the trip's mode or the gap's cause. */
@Composable
private fun itemLabel(item: TimelineItem): String = when (item) {
    is TimelineItem.Visit ->
        if (item.labelIsCoordinates) stringResource(R.string.timeline_unknown_place) else item.placeLabel ?: stringResource(R.string.timeline_unknown_place)
    is TimelineItem.Trip -> stringResource(item.mode.labelRes())
    is TimelineItem.Gap -> stringResource(item.cause.labelRes())
}

/**
 * The item under the thumb: a visit (place, times and duration, job or "Needs review" with "Add job"), a trip (mode,
 * distance, duration; the places either side) or a gap (cause). Its height never changes with the item, so the strip
 * below does not jump while dragging: two text lines plus a 48 dp row for the chips. The whole card opens the item
 * (a gap has nothing to open); the text is described for TalkBack as a polite live region from [description].
 */
@Composable
private fun SelectedItemCard(
    item: TimelineItem?,
    items: List<TimelineItem>,
    job: JobBlockItem?,
    jobColors: JobColors?,
    description: String,
    dayStart: Long,
    dayEnd: Long,
    zone: ZoneId,
    onOpenVisit: (String) -> Unit,
    onOpenTrip: (String) -> Unit,
    onAddJob: (TimelineItem.Visit) -> Unit,
    onMinimise: () -> Unit,
    minimiseFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val c = TarryTheme.colors
    val formats = rememberTimeFormats()
    val open: (() -> Unit)? = when (item) {
        is TimelineItem.Visit -> {{ onOpenVisit(item.id) }}
        is TimelineItem.Trip -> {{ onOpenTrip(item.id) }}
        else -> null
    }
    // Both lines reserve the same height whatever the item, in the user's text size.
    val density = LocalDensity.current
    val titleMin = with(density) { MaterialTheme.typography.titleMedium.lineHeight.toDp() }
    val subMin = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() }
    // Large text: the second line (times with duration and "(continues)") may wrap; the height is reserved for every kind.
    val subLines = if (density.fontScale >= LargeFontScale) 2 else 1
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (open != null) Modifier.pointerInput(item?.id) { detectTapGestures { open() } } else Modifier)
            .testTag("map_scrub_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = description
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                val times = item?.let { clampedTimes(it, dayStart, dayEnd, zone) }
                when (item) {
                    is TimelineItem.Visit -> {
                        val place = itemLabel(item)
                        CardTitle(place, titleMin, c.ink)
                        // The timeline's compact range (a short visit already carries its duration).
                        val range = times!!.range
                        CardSub(
                            if (TimelineText.isShort(times.durationMs)) range else stringResource(R.string.map_card_visit_times, range, formatDuration(times.durationMs)),
                            subMin, c.muted, subLines,
                        )
                    }
                    is TimelineItem.Trip -> {
                        Row(Modifier.heightIn(min = titleMin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
                            Icon(painterResource(item.mode.iconRes()), contentDescription = null, tint = c.muted, modifier = Modifier.size(StepIcon))
                            Text(
                                stringResource(R.string.map_card_trip_title, stringResource(item.mode.rowLabelRes()), formatDistance(item.distanceM), formatDuration(times!!.durationMs)),
                                style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val sorted = remember(items) { items.sortedBy { it.start } }
                        val from = sorted.lastOrNull { it is TimelineItem.Visit && it.end <= item.start } as TimelineItem.Visit?
                        val to = sorted.firstOrNull { it is TimelineItem.Visit && it.start >= item.end } as TimelineItem.Visit?
                        if (from != null && to != null) {
                            // Each end gets its own share and ellipsizes alone, so "→ B" never disappears behind a long A.
                            Row(Modifier.heightIn(min = subMin * subLines), horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
                                Text(
                                    stringResource(R.string.map_card_trip_from, itemLabel(from)),
                                    style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = subLines, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Text(
                                    stringResource(R.string.map_card_trip_to, itemLabel(to)),
                                    style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = subLines, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        } else {
                            val only = from ?: to
                            CardSub(if (only != null) stringResource(R.string.map_card_trip_from, itemLabel(only)) else "", subMin, c.muted, subLines)
                        }
                    }
                    is TimelineItem.Gap -> {
                        CardTitle(stringResource(R.string.map_card_gap_title, formatDuration(times!!.durationMs)), titleMin, c.alert)
                        CardSub(stringResource(item.cause.labelRes()), subMin, c.muted, subLines)
                    }
                    null -> {
                        CardTitle("", titleMin, c.ink)
                        CardSub("", subMin, c.muted, subLines)
                    }
                }
            }
            // Minimise sits left of Open (and takes Open's place on a gap, which has nothing to open).
            val shownState = stringResource(R.string.map_overlay_state_shown)
            Box(Modifier.size(MinTouch), contentAlignment = Alignment.Center) {
                IconCircleButton(
                    icon = R.drawable.ic_chevron_down,
                    contentDescription = stringResource(R.string.map_overlay_hide),
                    onClick = onMinimise,
                    containerColor = c.inset,
                    modifier = Modifier
                        .semantics { stateDescription = shownState }
                        .focusRequester(minimiseFocus)
                        .testTag("map_scrub_minimise"),
                )
            }
            if (open != null) {
                Spacer(Modifier.width(TarryTheme.space.s))
                Box(Modifier.size(MinTouch), contentAlignment = Alignment.Center) {
                    IconCircleButton(
                        icon = R.drawable.ic_chevron_right,
                        contentDescription = stringResource(R.string.map_card_open),
                        onClick = open,
                        containerColor = c.inset,
                        modifier = Modifier.testTag("map_scrub_open"),
                    )
                }
            }
        }
        // The chip row always takes its 48 dp, so the card is the same height for every item.
        Row(
            Modifier.fillMaxWidth().height(MinTouch),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        ) {
            if (item is TimelineItem.Visit) {
                if (job != null && jobColors != null) {
                    val name = if (job.billable) stringResource(R.string.map_card_job_billable, job.jobName, stringResource(R.string.timeline_job_billable)) else job.jobName
                    Row(
                        Modifier
                            .clip(TarryTheme.shapes.pill)
                            .background(jobColors.container)
                            .border(BlockStroke, jobColors.border, TarryTheme.shapes.pill)
                            .padding(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.xs)
                            .testTag("map_scrub_job_chip"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.xs),
                    ) {
                        Box(Modifier.size(8.dp).background(jobColors.border, CircleShape))
                        Text(name, style = MaterialTheme.typography.labelMedium, color = jobColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else if (item.needsReview) {
                    Text(
                        stringResource(R.string.timeline_needs_review),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.onAccentSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(TarryTheme.shapes.pill)
                            .background(c.accentSoft)
                            .padding(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.xs)
                            .testTag("map_scrub_review_chip"),
                    )
                    val addLabel = stringResource(R.string.timeline_add_job)
                    val addDescription = stringResource(R.string.map_card_add_job_description, itemLabel(item))
                    Box(
                        Modifier
                            .heightIn(min = MinTouch)
                            .widthIn(min = MinTouch)
                            .clip(TarryTheme.shapes.pill)
                            .semantics { contentDescription = addDescription }
                            .clickable(role = Role.Button) { onAddJob(item) }
                            .padding(horizontal = TarryTheme.space.s)
                            .testTag("map_scrub_add_job"),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The box's description is what TalkBack reads; the visible word is not announced a second time.
                        Text(addLabel, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTitle(text: String, minHeight: androidx.compose.ui.unit.Dp, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.heightIn(min = minHeight),
    )
}

@Composable
private fun CardSub(text: String, lineHeight: androidx.compose.ui.unit.Dp, color: Color, lines: Int) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.heightIn(min = lineHeight * lines),
    )
}

/** A previous / next arrow: a small glyph on the overlay with the full 48 dp touch target. */
@Composable
private fun StepButton(@DrawableRes icon: Int, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(StepTouch)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (enabled) TarryTheme.colors.ink else TarryTheme.colors.disabledIcon,
            modifier = Modifier.size(StepIcon),
        )
    }
}

/** Epoch ms → the instant's local date in [zone]. */
internal fun Long.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
