package me.tarry.app.ui.timeline

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.map.ScrubItem
import me.tarry.app.map.ScrubKind
import me.tarry.app.map.ScrubberModel
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.drawer.TimePickerDialog
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val MinTouch = 48.dp
private val StepTouch = 48.dp
private val StepIcon = 20.dp
private val TrackInset = 10.dp
private val LineWidth = 3.dp
private val VisitLineWidth = 5.dp
private val GapDash = 4.dp
private val DotRadius = 5.dp
private val DotRing = 1.5.dp
private val ThumbRadius = 8.dp
/** The overlay card over the map: mostly opaque so the strip reads on any map content. */
private const val OverlayAlpha = 0.94f
/** Strip items other than the one under the thumb. */
private const val FadedAlpha = 0.4f

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
 * The map's time scrubber (MVP § Map view), drawn as an overlay: one line in proportion to time (visits in their job
 * colour with a dot at each start, trips `muted`, gaps dashed `alert`), with the [time] thumb and its label above. Dragging moves [time]
 * live ([onTimeChange]) and snaps to the nearest visit start on release. Tapping a visit block opens it
 * ([onOpenVisit]). Previous / next visit buttons and TalkBack actions are the non-drag alternative (WCAG 2.5.7).
 * Tapping the time label opens the date-time jump: a day, then a time; [onJump] gets that instant and its date.
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
) {
    val c = TarryTheme.colors
    val dark = TarryTheme.isDark
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
    val jobColors = remember(visitJobs, dark) {
        visitJobs.mapValues { (_, b) -> JobPalette.lookup(b.jobColorArgb, b.jobId).colors(dark) }
    }
    val previous = model.previous(time)
    val next = model.next(time)
    val previousLabel = stringResource(R.string.map_scrub_previous_visit)
    val nextLabel = stringResource(R.string.map_scrub_next_visit)

    val density = LocalDensity.current
    val inset = with(density) { TrackInset.toPx() }
    /** Strip x (px) → 0..1 along the track, which is inset so the end dots and the thumb aren't clipped. */
    fun fractionOf(x: Float): Float = if (widthPx <= 2 * inset) 0f else ((x - inset) / (widthPx - 2 * inset)).coerceIn(0f, 1f)

    Column(modifier) {
        // The time label floats above the thumb, over the map; the pill is small but its touch target is 48 dp.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = StepTouch + TarryTheme.space.xs)) {
            var labelWidth by remember { mutableStateOf(0) }
            val trackPx = constraints.maxWidth - 2 * inset
            val thumbX = inset + model.fractionAt(time) * trackPx
            val x = (thumbX - labelWidth / 2f).roundToInt().coerceIn(0, maxOf(0, constraints.maxWidth - labelWidth))
            Box(
                modifier = Modifier
                    .offset { IntOffset(x, 0) }
                    .onSizeChanged { labelWidth = it.width }
                    .sizeIn(minWidth = MinTouch, minHeight = MinTouch)
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
                .background(c.card.copy(alpha = OverlayAlpha), TarryTheme.shapes.pill)
                .padding(horizontal = TarryTheme.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(R.drawable.ic_chevron_left, previousLabel, enabled = previous != null, Modifier.testTag("map_scrub_previous")) {
                previous?.let(onTimeChange)
            }
            val description = stringResource(R.string.map_scrub_description)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(MinTouch)
                    .onSizeChanged { widthPx = it.width }
                    .semantics {
                        contentDescription = description
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
                            onDragEnd = { change(latestModel.snap(current)) },
                        ) { pointer, _ ->
                            if (widthPx > 0) change(latestModel.timeAt(fractionOf(pointer.position.x)))
                        }
                    }
                    .testTag("map_scrubber"),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val y = size.height / 2
                    val track = size.width - 2 * inset
                    fun xAt(f: Float) = inset + f * track
                    // One line for the worked span: trips and stays in their colours, gaps dashed in alert. The item the
                    // thumb is on is full strength; the rest are faded so the current place in the day stands out.
                    val activeId = model.itemAt(current)?.id
                    fun Color.emphasis(id: String) = if (id == activeId) this else copy(alpha = alpha * FadedAlpha)
                    drawLine(c.faint, Offset(xAt(0f), y), Offset(xAt(1f), y), LineWidth.toPx(), StrokeCap.Round)
                    model.blocks.forEach { block ->
                        val from = Offset(xAt(block.startFraction), y)
                        val to = Offset(xAt(block.endFraction), y)
                        when (block.item.kind) {
                            ScrubKind.TRIP -> drawLine(c.muted.emphasis(block.item.id), from, to, LineWidth.toPx(), StrokeCap.Round)
                            ScrubKind.GAP -> drawLine(
                                c.alert.emphasis(block.item.id), from, to, LineWidth.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(GapDash.toPx(), GapDash.toPx())),
                            )
                            ScrubKind.VISIT -> {
                                val colour = (jobColors[block.item.id]?.border ?: c.outline).emphasis(block.item.id)
                                drawLine(colour, from, to, VisitLineWidth.toPx(), StrokeCap.Round)
                            }
                        }
                    }
                    // A coloured point at each visit start: where the steps and the snap land.
                    model.blocks.filter { it.item.kind == ScrubKind.VISIT }.forEach { block ->
                        val at = Offset(xAt(block.startFraction), y)
                        drawCircle(c.card, DotRadius.toPx() + DotRing.toPx(), at)
                        drawCircle((jobColors[block.item.id]?.border ?: c.outline).emphasis(block.item.id), DotRadius.toPx(), at)
                    }
                    val thumb = Offset(xAt(model.fractionAt(current)), y)
                    drawCircle(c.card, ThumbRadius.toPx() + DotRing.toPx(), thumb)
                    drawCircle(c.ink, ThumbRadius.toPx(), thumb)
                }
            }
            StepButton(R.drawable.ic_chevron_right, nextLabel, enabled = next != null, Modifier.testTag("map_scrub_next")) {
                next?.let(onTimeChange)
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
            tint = if (enabled) TarryTheme.colors.ink else TarryTheme.colors.faint,
            modifier = Modifier.size(StepIcon),
        )
    }
}

/** Epoch ms → the instant's local date in [zone]. */
internal fun Long.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
