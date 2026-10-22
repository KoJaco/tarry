package me.tarry.app.ui.timeline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.filter
import me.tarry.app.ui.drawer.EditTarget
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import me.tarry.app.R
import me.tarry.app.ui.components.EmptyState
import me.tarry.app.ui.components.LoadingBlocks
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.TarryTheme
import java.time.LocalDate
import java.time.ZoneId

private const val DaySlideMillis = 200
private const val DaySlideDp = 24
private const val MillisPerMinute = 60_000L
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Stateless day view: header, week strip, then a loading state, an empty state or the summary card plus the day canvas.
 * [items] is null while loading, so an empty day is only shown once it is known to be empty. [today] decides which
 * days are selectable and whether the now-line shows. [nowMs] is for tests; null means "the wall clock, ticking
 * once a minute".
 */
@Composable
fun TimelineContent(
    date: LocalDate,
    items: List<TimelineItem>?,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = date,
    onSelectDate: (LocalDate) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    nowMs: Long? = null,
    /** Opens the edit drawer: a tapped visit or trip, or a new manual visit (gap sheet, "+" button). */
    onEdit: (EditTarget) -> Unit = {},
    /** A visit's edge was dragged and released: the new start and end (UTC epoch ms, snapped and clamped). */
    onResizeVisit: (id: String, start: Long, end: Long) -> Unit = { _, _, _ -> },
    minStayMs: Long = DefaultMinStayMs,
) {
    val scroll = rememberScrollState()
    // The visit whose resize handles show. Cleared by a day change, a scroll or a tap anywhere else.
    var selectedVisit by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(date) { selectedVisit = null }
    LaunchedEffect(scroll) { snapshotFlow { scroll.isScrollInProgress }.filter { it }.collect { selectedVisit = null } }
    var reviewOnly by rememberSaveable { mutableStateOf(false) }
    var gapSheetId by rememberSaveable { mutableStateOf<String?>(null) }
    val isToday = date == today
    // Only today has a now-line, and only a started screen needs it to move.
    val clock by rememberMinuteClock(enabled = nowMs == null && isToday)
    val now = nowMs ?: clock

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            // A tap nothing else consumed (blocks, buttons and handles consume theirs) deselects.
            .pointerInput(Unit) { detectTapGestures(onTap = { selectedVisit = null }) }
            .padding(horizontal = TarryTheme.space.gutter),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xl),
    ) {
        DayHeader(date, today, onPreviousDay, onNextDay, onSelectDate)
        WeekStrip(date, today, onSelectDate)
        ReviewRow(
            reviewOnly = reviewOnly,
            showToggle = !items.isNullOrEmpty(),
            onReviewOnlyChange = { reviewOnly = it },
            onNewVisit = {
                val (start, end) = firstFreeSlot(date, zone, items.orEmpty().filterIsInstance<TimelineItem.Visit>())
                onEdit(EditTarget.NewVisit(start, end))
            },
        )
        Column(
            modifier = Modifier.fillMaxWidth().daySlide(date),
            verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        ) {
            when {
                items == null -> LoadingBlocks(Modifier.testTag("timeline_loading"))
                items.isEmpty() -> EmptyState(
                    title = stringResource(if (isToday) R.string.timeline_empty_title_today else R.string.timeline_empty_title_past),
                    body = stringResource(if (isToday) R.string.timeline_empty_body_today else R.string.timeline_empty_body_past),
                    modifier = Modifier.testTag("timeline_empty"),
                )
                else -> {
                    val bounds = dayBounds(date, zone)
                    val fontScale = LocalDensity.current.fontScale
                    val minute = if (isToday) now / MillisPerMinute else 0L
                    val layout = remember(items, date, zone, minute, isToday, fontScale) {
                        layoutDay(items, bounds, zone, if (isToday) now else -1L, fontScale)
                    }
                    SummaryCard(summarize(items, bounds), isToday)
                    DayCanvas(
                        layout, bounds.first, bounds.second, zone, now,
                        dimNonReview = reviewOnly,
                        onOpenVisit = { onEdit(EditTarget.Visit(it)) },
                        selectedVisitId = selectedVisit,
                        onSelectVisit = { selectedVisit = it },
                        onResizeVisit = onResizeVisit,
                        minStayMs = minStayMs,
                        onOpenTrip = { onEdit(EditTarget.Trip(it)) },
                        onOpenGap = { gap, _ -> gapSheetId = gap.id },
                    )
                    val sheetBlock = gapSheetId?.let { id ->
                        layout.blocks.firstOrNull { it.item.id == id && it.item is TimelineItem.Gap }
                    }
                    val sheetGap = sheetBlock?.item as? TimelineItem.Gap
                    if (sheetBlock != null && sheetGap != null) {
                        val from = maxOf(sheetGap.start, bounds.first)
                        val to = minOf(sheetGap.end, bounds.second)
                        GapSheet(
                            gap = sheetGap,
                            outages = sheetBlock.cluster,
                            slot = freeSlotForGap(from, to, items.filterIsInstance<TimelineItem.Visit>()),
                            zone = zone,
                            onAddVisit = { start, end ->
                                gapSheetId = null
                                onEdit(EditTarget.NewVisit(start, end))
                            },
                            onDismiss = { gapSheetId = null },
                        )
                    }
                }
            }
        }
    }
}

/** Slides in 24 dp and fades over 200 ms when [date] changes; nothing moves with reduced motion. */
@Composable
private fun Modifier.daySlide(date: LocalDate): Modifier {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(date) }
    var direction by remember { mutableIntStateOf(1) }
    LaunchedEffect(date, reduced) {
        if (date != shown) {
            direction = if (date > shown) 1 else -1
            shown = date
            if (!reduced) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(DaySlideMillis, easing = EmphasizedEasing))
            }
        }
    }
    val offsetPx = with(LocalDensity.current) { DaySlideDp.dp.toPx() }
    return graphicsLayer {
        alpha = progress.value
        translationX = direction * offsetPx * (1f - progress.value)
    }
}

/**
 * The wall clock, refreshed on every minute boundary while the screen is at least STARTED and [enabled]. It stops in
 * the background and when disabled (not today), and re-reads the clock when it starts again. UI-only recomposition so
 * the now-line moves; nothing is recorded or scheduled here.
 */
@Composable
internal fun rememberMinuteClock(enabled: Boolean, wallClock: () -> Long = System::currentTimeMillis): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(wallClock(), enabled, lifecycle) {
        if (enabled) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                value = wallClock()
                while (true) {
                    delay(MillisPerMinute - value % MillisPerMinute)
                    value = wallClock()
                }
            }
        }
    }
}
