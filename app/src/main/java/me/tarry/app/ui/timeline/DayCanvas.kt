package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
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
import me.tarry.app.ui.theme.JobColors
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.TripMode
import java.time.ZoneId
import kotlin.math.floor

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
                        drawLine(c.line, Offset(railX, y), Offset(size.width, y), strokeWidth = stroke)
                    }
                    drawLine(c.line, Offset(railX, 0f), Offset(railX, size.height), strokeWidth = stroke)
                }
                .semantics { isTraversalGroup = true },
        ) {
            layout.hourMarks.forEachIndexed { i, mark ->
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
                val position = Modifier
                    .offset(y = placed.topDp.dp)
                    .padding(start = timeColumn + DayCanvasDefaults.BlockInset)
                    .fillMaxWidth()
                    .height(placed.heightDp.dp)
                when (placed) {
                    is PlacedBlock -> {
                        val description = spokenDescription(placed.item, dayStart, dayEnd, zone, placed.foldedTrips)
                        val times = clampedTimes(placed.item, dayStart, dayEnd, zone)
                        when (val item = placed.item) {
                            is TimelineItem.Visit -> VisitBlock(item, placed, times, description, index, fontScale, position)
                            is TimelineItem.Gap -> GapBlock(item, placed, description, index, fontScale, position)
                            is TimelineItem.Trip -> Unit // Trips are placed as PlacedTrip, never as blocks.
                        }
                    }
                    is PlacedTrip -> TripRow(
                        placed.trip,
                        clampedTimes(placed.trip, dayStart, dayEnd, zone),
                        spokenDescription(placed.trip, dayStart, dayEnd, zone),
                        index,
                        position,
                    )
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
    modifier: Modifier,
) {
    val c = TarryTheme.colors
    val job: JobColors? = visit.jobName?.let { JobPalette.lookup(visit.jobColorArgb, visit.jobId ?: it).colors(TarryTheme.isDark) }
    val shape = if (placed.compact) TarryTheme.shapes.small else TarryTheme.shapes.block
    val container = job?.container ?: c.card
    val titleColor = job?.text ?: c.ink
    val detailColor = job?.text ?: c.muted
    val unknown = stringResource(R.string.timeline_unknown_place)
    val title = visit.placeLabel ?: unknown
    val oneLine = placed.compact || placed.heightDp < DayCanvasDefaults.OneLineBelowDp * fontScale
    val showChip = !oneLine && job == null && placed.heightDp >= DayCanvasDefaults.ChipMinDp * fontScale
    val (titleLines, detailLines) = textLines(placed.heightDp, fontScale, showChip)

    val look = if (job != null) {
        Modifier.clip(shape).background(container).border(DayCanvasDefaults.BorderWidth, job.border, shape)
    } else {
        Modifier.clip(shape).background(container).dashedBorder(c.outline, shape)
    }
    Column(
        modifier = modifier
            .then(look)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = index.toFloat()
                blockColor = container
                blockDashed = job == null
            }
            .testTag("timeline_row_${visit.id}")
            .padding(horizontal = TarryTheme.space.m, vertical = if (oneLine) DayCanvasDefaults.OneLinePaddingV else DayCanvasDefaults.BlockPaddingV),
        verticalArrangement = Arrangement.Center,
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
    description: String,
    index: Int,
    fontScale: Float,
    modifier: Modifier,
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
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = index.toFloat()
                gapHatch = true
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
            text = stringResource(R.string.timeline_gap_row, stringResource(gap.cause.labelRes())),
            style = MaterialTheme.typography.bodyMedium,
            color = c.onAlertSoft,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TripRow(trip: TimelineItem.Trip, times: ClampedTimes, description: String, index: Int, modifier: Modifier) {
    val c = TarryTheme.colors
    val text = stringResource(
        R.string.timeline_trip_row,
        stringResource(trip.mode.rowLabelRes()),
        formatDistance(trip.distanceM),
        formatDuration(times.durationMs),
    )
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = index.toFloat()
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
