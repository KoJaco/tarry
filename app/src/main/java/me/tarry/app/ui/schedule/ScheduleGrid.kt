package me.tarry.app.ui.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek
import java.time.format.TextStyle

/** Test tag of the scrolling body of the grid (the 48 slot rows). */
const val ScheduleGridTag = "schedule_grid"

/** The slot shown at the top when the grid first appears: 06:00. */
const val ScheduleGridInitialSlot = 12

private val RowHeight = 48.dp
private val EdgeZone = 48.dp
private val AutoScrollSpeed = 480.dp
private const val DAYS = 7

/**
 * The weekly grid: a sticky Mon-Sun header over 48 half-hour rows of 48 dp, hour labels on the left. Stateless and
 * reusable (onboarding later). A tap toggles a cell through [onToggle]. A long press then drag paints or erases (the
 * first cell decides, see [PaintStroke]) and reports the whole schedule through [onStroke]; a plain swipe scrolls,
 * and holding the finger near the top or bottom edge scrolls on its own while the drag lasts. Tapping a day header
 * calls [onDayHeaderClick].
 *
 * Known WCAG 2.2 target-size deviation: seven columns on a 360 dp screen leave cells about 42-44 dp wide, under 48 dp.
 * The 48 dp day header, which opens the day sheet with Set hours, is the equivalent control (and the non-drag
 * alternative to painting, 2.5.7).
 */
@Composable
fun ScheduleGrid(
    schedule: Schedule,
    onToggle: (DayOfWeek, Int) -> Unit,
    onStroke: (Schedule) -> Unit,
    onDayHeaderClick: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
    highlightDay: DayOfWeek? = null,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = ScheduleGridInitialSlot),
) {
    val locale = currentLocale()
    val shortNames = remember(locale) { DayOfWeek.entries.map { dayName(it, TextStyle.SHORT, locale) } }
    val narrowNames = remember(locale) { DayOfWeek.entries.map { dayName(it, TextStyle.NARROW, locale) } }
    val fullNames = remember(locale) { DayOfWeek.entries.map { dayName(it, TextStyle.FULL, locale) } }
    val labelWidth = hourLabelWidth()
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(labelWidth))
            DayOfWeek.entries.forEach { day ->
                DayHeader(
                    shortName = shortNames[day.ordinal],
                    narrowName = narrowNames[day.ordinal],
                    fullName = fullNames[day.ordinal],
                    highlighted = day == highlightDay,
                    onClick = { onDayHeaderClick(day) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        GridBody(schedule, fullNames, labelWidth, onToggle, onStroke, listState, Modifier.weight(1f))
    }
}

@Composable
private fun hourLabelWidth(): Dp {
    val scale = maxOf(1f, LocalDensity.current.fontScale)
    return (28.dp * scale).coerceAtMost(64.dp)
}

@Composable
private fun DayHeader(shortName: String, narrowName: String, fullName: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = TarryTheme.colors
    val edit = stringResource(R.string.schedule_edit_day, fullName)
    val today = stringResource(R.string.schedule_today)
    // Large font scales leave a column too narrow for the short name: fall back to the narrow one rather than clip.
    val fontScale = LocalDensity.current.fontScale
    var narrow by remember(shortName, fontScale) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = edit
                if (highlighted) stateDescription = today
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (narrow) narrowName else shortName,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) c.onAccentSoft else c.ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { if (it.didOverflowWidth) narrow = true },
            modifier = Modifier
                .clip(TarryTheme.shapes.pill)
                .background(if (highlighted) c.accentSoft else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Measurements the pointer handler needs; plain fields, read only when an event arrives. */
private class GridMetrics {
    var width = 0
    var height by mutableIntStateOf(0)
    var labelPx = 0f
}

/** The long-press drag in progress, shared by the pointer handler and the edge auto-scroll. */
private class StrokeDrag {
    var stroke: PaintStroke? = null
    var lastReported: Schedule? = null
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var active by mutableStateOf(false)
}

@Composable
private fun GridBody(
    schedule: Schedule,
    dayNames: List<String>,
    labelWidth: Dp,
    onToggle: (DayOfWeek, Int) -> Unit,
    onStroke: (Schedule) -> Unit,
    listState: LazyListState,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptic = LocalHapticFeedback.current
    val currentSchedule by rememberUpdatedState(schedule)
    val currentOnStroke by rememberUpdatedState(onStroke)
    val metrics = remember { GridMetrics() }
    metrics.labelPx = with(density) { labelWidth.toPx() }
    val drag = remember { StrokeDrag() }
    val gridDescription = stringResource(R.string.schedule_grid_description)

    /**
     * The (day, slot) under a pointer position in the grid body. Rows clamp to the grid; columns clamp too while a
     * stroke moves, but a press in the hour-label column ([clampColumns] false) is no cell at all.
     */
    fun cellAt(x: Float, y: Float, clampColumns: Boolean): Pair<DayOfWeek, Int>? {
        val clampedY = y.coerceIn(0f, (metrics.height - 1).coerceAtLeast(0).toFloat())
        val item = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { clampedY >= it.offset && clampedY < it.offset + it.size } ?: return null
        val cellWidth = (metrics.width - metrics.labelPx) / DAYS
        if (cellWidth <= 0f) return null
        // Right-to-left mirrors the row: the labels sit on the right and Monday is next to them.
        val fromLabel = (if (rtl) metrics.width - x else x) - metrics.labelPx
        if (!clampColumns && (fromLabel < 0f || fromLabel >= cellWidth * DAYS)) return null
        val day = (fromLabel / cellWidth).toInt().coerceIn(0, DAYS - 1)
        return DayOfWeek.entries[day] to item.index
    }

    fun moveStroke() {
        val stroke = drag.stroke ?: return
        val (day, slot) = cellAt(drag.x, drag.y, clampColumns = true) ?: return
        stroke.moveTo(day, slot)
        if (stroke.schedule != drag.lastReported) {
            drag.lastReported = stroke.schedule
            currentOnStroke(stroke.schedule)
        }
    }

    val edgePx = with(density) { EdgeZone.toPx() }
    val speedPx = with(density) { AutoScrollSpeed.toPx() }
    // -1 near the top edge, 1 near the bottom, 0 elsewhere or when no drag is active.
    val scrollDirection by remember {
        derivedStateOf {
            when {
                !drag.active -> 0
                drag.y < edgePx -> -1
                drag.y > metrics.height - edgePx -> 1
                else -> 0
            }
        }
    }
    // Frame-driven and tied to the active drag only: it stops the moment the finger leaves the edge zone or lifts.
    LaunchedEffect(scrollDirection) {
        if (scrollDirection == 0) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / NANOS_PER_SECOND
            last = now
            listState.scrollBy(scrollDirection * speedPx * seconds)
            moveStroke()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .testTag(ScheduleGridTag)
            .semantics { contentDescription = gridDescription }
            .onSizeChanged {
                metrics.width = it.width
                metrics.height = it.height
            }
            .pointerInput(listState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val (startDay, startSlot) = cellAt(press.position.x, press.position.y, clampColumns = false) ?: return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val stroke = PaintStroke.begin(currentSchedule, startDay, startSlot)
                    drag.stroke = stroke
                    drag.lastReported = stroke.schedule
                    drag.x = press.position.x
                    drag.y = press.position.y
                    drag.active = true
                    try {
                        currentOnStroke(stroke.schedule)
                        press.consume()
                        // Initial pass: the list and the cells never see the rest of this gesture.
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            drag.x = change.position.x
                            drag.y = change.position.y
                            moveStroke()
                        }
                    } finally {
                        // Also when the gesture coroutine is cancelled: the edge auto-scroll must not outlive the finger.
                        drag.active = false
                        drag.stroke = null
                    }
                }
            },
    ) {
        items(Schedule.SLOTS_PER_DAY, key = { it }) { slot ->
            SlotRow(slot, schedule, dayNames, labelWidth, onToggle)
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000f

@Composable
private fun SlotRow(slot: Int, schedule: Schedule, dayNames: List<String>, labelWidth: Dp, onToggle: (DayOfWeek, Int) -> Unit) {
    val c = TarryTheme.colors
    val divider = c.divider
    val onHour = slot % 2 == 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .drawBehind {
                if (onHour && slot > 0) drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            },
    ) {
        Box(
            modifier = Modifier
                .width(labelWidth)
                .fillMaxHeight()
                // Decorative: every cell already says its own time range.
                .clearAndSetSemantics {},
            contentAlignment = Alignment.TopCenter,
        ) {
            if (onHour) {
                Text("%02d".format(slot / 2), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        DayOfWeek.entries.forEach { day ->
            SlotCell(
                day = day,
                slot = slot,
                active = schedule.isActive(day, slot),
                dayName = dayNames[day.ordinal],
                onToggle = onToggle,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One half-hour cell. Active = `accent` fill with an `accentStrong` edge (so it is never colour alone); inactive =
 * `inset`. A switch for TalkBack: "Monday 07:00 to 07:30", On / Off.
 */
@Composable
private fun SlotCell(day: DayOfWeek, slot: Int, active: Boolean, dayName: String, onToggle: (DayOfWeek, Int) -> Unit, modifier: Modifier) {
    val c = TarryTheme.colors
    val description = stringResource(R.string.schedule_cell_description, dayName, slotTime(slot), slotTime(slot + 1))
    val state = stringResource(if (active) R.string.schedule_cell_on else R.string.schedule_cell_off)
    val shape = TarryTheme.shapes.small
    Box(
        modifier = modifier
            .fillMaxHeight()
            .semantics {
                contentDescription = description
                stateDescription = state
            }
            .toggleable(
                value = active,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = { onToggle(day, slot) },
            )
            .padding(1.dp)
            .clip(shape)
            .background(if (active) c.accent else c.inset)
            .then(if (active) Modifier.border(BorderStroke(1.dp, c.accentStrong), shape) else Modifier),
    )
}
