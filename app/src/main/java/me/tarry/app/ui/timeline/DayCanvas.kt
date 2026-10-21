package me.tarry.app.ui.timeline

import android.text.format.DateFormat
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
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

private val TimeColumn = 52.dp
private val BlockInset = 8.dp
private val BorderWidth = 1.5.dp

/** Below this height (per unit of font scale) a block has no room for its second line. */
private const val OneLineBelowDp = 56f

/** Below this height (per unit of font scale) a block has no room for the "Needs review" chip. */
private const val ChipMinDp = 92f
private const val HatchStepDp = 8f
private const val HatchAlpha = 0.25f

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
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val timeColumn = TimeColumn * minOf(fontScale, 1.5f)
    val hourFormat = remember(locale, zone) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "j"), locale).withZone(zone)
    }
    val ordered = layout.ordered()

    TarryCard(modifier = modifier, contentPadding = PaddingValues(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.l)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(layout.heightDp.dp)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    val railX = timeColumn.toPx()
                    layout.hourMarks.forEach { mark ->
                        val y = mark.topDp.dp.toPx()
                        drawLine(c.line, Offset(railX, y), Offset(size.width, y), strokeWidth = stroke)
                    }
                    drawLine(c.line, Offset(railX, 0f), Offset(railX, size.height), strokeWidth = stroke)
                }
                .semantics { isTraversalGroup = true },
        ) {
            layout.hourMarks.forEach { mark ->
                Text(
                    text = hourFormat.format(Instant.ofEpochMilli(mark.epochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .offset(y = mark.topDp.dp - 8.dp * fontScale)
                        .width(timeColumn - TarryTheme.space.s)
                        // Decorative: the blocks carry their own times.
                        .clearAndSetSemantics {},
                )
            }
            ordered.forEachIndexed { index, placed ->
                val position = Modifier
                    .offset(y = placed.topDp.dp)
                    .padding(start = timeColumn + BlockInset)
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
    val job: JobColors? = visit.jobName?.let { JobPalette.lookup(visit.jobColorArgb, it).colors(TarryTheme.isDark) }
    val shape = if (placed.compact) TarryTheme.shapes.small else TarryTheme.shapes.block
    val container = job?.container ?: c.card
    val titleColor = job?.text ?: c.ink
    val detailColor = job?.text ?: c.muted
    val unknown = stringResource(R.string.timeline_unknown_place)
    val title = visit.placeLabel ?: unknown
    val oneLine = placed.compact || placed.heightDp < OneLineBelowDp * fontScale

    val look = if (job != null) {
        Modifier.clip(shape).background(container).border(BorderWidth, job.border, shape)
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
            .padding(horizontal = TarryTheme.space.m, vertical = if (oneLine) 2.dp else 6.dp),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = visit.jobName?.let { stringResource(R.string.timeline_range_with_job, times.range, it) } ?: times.range,
                style = MaterialTheme.typography.bodyMedium,
                color = detailColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (job == null && placed.heightDp >= ChipMinDp * fontScale) {
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
                        .padding(horizontal = TarryTheme.space.m, vertical = 3.dp),
                )
            }
        }
    }
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
    val lines = floor((placed.heightDp - 8f) / (20f * fontScale)).toInt().coerceAtLeast(1)
    Row(
        modifier = modifier
            .clip(shape)
            .background(c.alertSoft)
            .hatch(c.alert.copy(alpha = HatchAlpha))
            .border(BorderWidth, c.alert, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = index.toFloat()
                gapHatch = true
            }
            .testTag("timeline_row_${gap.id}")
            .padding(horizontal = TarryTheme.space.m, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        // Decorative: the element's description already says "Tracking gap".
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = c.alert,
            modifier = Modifier.size(20.dp),
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
            modifier = Modifier.size(16.dp),
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
    val time = formatTime(now, zone)
    val description = stringResource(R.string.timeline_now_description, time)
    // The line itself is decorative; the pill carries the element.
    Box(
        Modifier
            .offset(y = nowDp.dp - 1.dp)
            .padding(start = timeColumn)
            .fillMaxWidth()
            .height(2.dp)
            .background(c.accent)
            .clearAndSetSemantics {},
    )
    Text(
        text = time,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = c.onAccent,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .offset(y = nowDp.dp - 10.dp * fontScale)
            .width(timeColumn)
            .clip(TarryTheme.shapes.pill)
            .background(c.accent)
            .padding(vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = traversal
            }
            .testTag("timeline_now_line"),
    )
}

private fun Modifier.dashedBorder(color: Color, shape: RoundedCornerShape, width: Dp = BorderWidth): Modifier =
    drawBehind {
        val sw = width.toPx()
        val radius = shape.topStart.toPx(size, this) - sw / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(sw / 2, sw / 2),
            size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))),
        )
    }

/** Diagonal hatch: 1 dp lines every 8 dp, clipped by the caller's shape. */
private fun Modifier.hatch(color: Color): Modifier = drawBehind {
    val step = HatchStepDp.dp.toPx()
    val stroke = 1.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stroke)
        x += step
    }
}
