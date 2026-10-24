package me.tarry.app.ui.timeline

import androidx.compose.foundation.Canvas
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

private val StripHeight = 32.dp
private val MinTouch = 48.dp
private val ThumbWidth = 3.dp
private val HatchSpacing = 6.dp
private val BorderWidth = 1.5.dp

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
 * The map's time scrubber (MVP § Map view): a strip of blocks in proportion to time (visits in job colour, trips
 * `faint`, gaps hatched in `alert` on `alertSoft`), with the [time] thumb and its label above. Dragging moves [time]
 * live ([onTimeChange]) and snaps to the nearest visit start on release. Tapping a visit block opens it
 * ([onOpenVisit]). Previous / next visit buttons and TalkBack actions are the non-drag alternative (WCAG 2.5.7).
 * Tapping the time label opens the date-time jump: a day, then a time; [onJump] gets that instant and its date.
 */
@Composable
internal fun DayScrubber(
    model: ScrubberModel,
    visits: List<TimelineItem.Visit>,
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
    val jobColors = remember(visits, dark) {
        visits.associate { v -> v.id to v.jobId?.let { JobPalette.lookup(v.jobColorArgb, it).colors(dark) } }
    }
    val previous = model.previous(time)
    val next = model.next(time)
    val previousLabel = stringResource(R.string.map_scrub_previous_visit)
    val nextLabel = stringResource(R.string.map_scrub_next_visit)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
        // The time label rides above the thumb (clamped to the strip), and is the date-time jump button.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = MinTouch + TarryTheme.space.s)) {
            var labelWidth by remember { mutableStateOf(0) }
            val stripPx = constraints.maxWidth
            val x = (model.fractionAt(time) * stripPx - labelWidth / 2f).roundToInt().coerceIn(0, maxOf(0, stripPx - labelWidth))
            Box(
                modifier = Modifier
                    .offset { IntOffset(x, 0) }
                    .onSizeChanged { labelWidth = it.width }
                    .heightIn(min = MinTouch)
                    .widthIn(min = MinTouch)
                    .clip(TarryTheme.shapes.pill)
                    .background(c.ink)
                    .clickable(onClickLabel = stringResource(R.string.map_scrub_jump), role = Role.Button) { pickingDay = true }
                    .padding(horizontal = TarryTheme.space.m)
                    .testTag("map_scrub_time"),
                contentAlignment = Alignment.Center,
            ) {
                Text(timeText, style = MaterialTheme.typography.labelLarge, color = c.onInk)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
            IconCircleButton(
                icon = R.drawable.ic_chevron_left,
                contentDescription = previousLabel,
                onClick = { previous?.let(onTimeChange) },
                enabled = previous != null,
                modifier = Modifier.testTag("map_scrub_previous"),
            )
            val description = stringResource(R.string.map_scrub_description)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch)
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
                            val t = latestModel.timeAt(p.x / widthPx)
                            val hit = latestModel.itemAt(t)
                            if (hit?.kind == ScrubKind.VISIT) openVisit(hit.id) else change(latestModel.snap(t))
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = { change(latestModel.snap(current)) },
                        ) { pointer, _ ->
                            if (widthPx > 0) change(latestModel.timeAt(pointer.position.x / widthPx))
                        }
                    }
                    .testTag("map_scrubber"),
                contentAlignment = Alignment.Center,
            ) {
                val hatch = with(LocalDensity.current) { HatchSpacing.toPx() }
                val thumb = with(LocalDensity.current) { ThumbWidth.toPx() }
                Canvas(Modifier.fillMaxWidth().height(StripHeight).clip(TarryTheme.shapes.small)) {
                    drawRect(c.inset)
                    model.blocks.forEach { block ->
                        val left = block.startFraction * size.width
                        val w = maxOf(1f, (block.endFraction - block.startFraction) * size.width)
                        when (block.item.kind) {
                            ScrubKind.TRIP -> drawRect(c.faint, Offset(left, 0f), Size(w, size.height))
                            ScrubKind.GAP -> drawHatched(left, w, c.alertSoft, c.alert, hatch)
                            ScrubKind.VISIT -> {
                                val job = jobColors[block.item.id]
                                drawRect(job?.container ?: c.card, Offset(left, 0f), Size(w, size.height))
                                drawRect(job?.border ?: c.outline, Offset(left, 0f), Size(w, size.height), style = Stroke(BorderWidth.toPx()))
                            }
                        }
                    }
                    val tx = model.fractionAt(current) * size.width
                    drawRect(c.ink, Offset(tx - thumb / 2, 0f), Size(thumb, size.height))
                }
            }
            IconCircleButton(
                icon = R.drawable.ic_chevron_right,
                contentDescription = nextLabel,
                onClick = { next?.let(onTimeChange) },
                enabled = next != null,
                modifier = Modifier.testTag("map_scrub_next"),
            )
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

private fun DrawScope.drawHatched(left: Float, width: Float, fill: Color, line: Color, spacing: Float) {
    drawRect(fill, Offset(left, 0f), Size(width, size.height))
    clipRect(left, 0f, left + width, size.height) {
        var x = left - size.height
        while (x < left + width) {
            drawLine(line, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = BorderWidth.toPx())
            x += spacing
        }
    }
}

/** Epoch ms → the instant's local date in [zone]. */
internal fun Long.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
