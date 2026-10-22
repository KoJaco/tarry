package me.tarry.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.tarry.app.ui.theme.TarryTheme

/** Drag-to-resize snaps to this step. */
internal const val ResizeStepMs = 5 * 60_000L

/** Default floor for a visit's length while dragging; the app passes `SegmentationParams.minStayDurationMs`. */
internal const val DefaultMinStayMs = 5 * 60_000L

/** Test tag of the floating time label shown while a handle is dragged. */
internal const val ResizeLabelTag = "timeline_resize_label"

internal fun handleTag(edge: ResizeEdge, visitId: String) = "timeline_handle_${edge.name.lowercase()}_$visitId"

internal enum class ResizeEdge { Start, End }

internal data class Span(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/**
 * The edge's new time after the finger moved [deltaMs] from [originMs]: snapped to [ResizeStepMs], never leaving the
 * visit shorter than [minMs], never past a neighbour's own [minMs] (the neighbour is trimmed, so it must keep that
 * much), and inside the day. No movement keeps the exact original (the edge may not be on the grid).
 */
internal fun resizedEdge(
    edge: ResizeEdge,
    originMs: Long,
    deltaMs: Long,
    visit: Span,
    prev: Span?,
    next: Span?,
    minMs: Long,
    dayStart: Long,
    dayEnd: Long,
): Long {
    if (deltaMs == 0L) return originMs
    val snapped = Math.floorDiv(originMs + deltaMs + ResizeStepMs / 2, ResizeStepMs) * ResizeStepMs
    return when (edge) {
        ResizeEdge.Start -> {
            val floor = maxOf(dayStart, prev?.let { if (it.length >= minMs) it.start + minMs else it.end } ?: Long.MIN_VALUE)
            snapped.coerceAtMost(visit.end - minMs).coerceAtLeast(floor)
        }
        ResizeEdge.End -> {
            val ceiling = minOf(dayEnd, next?.let { if (it.length >= minMs) it.end - minMs else it.start } ?: Long.MAX_VALUE)
            snapped.coerceAtLeast(visit.start + minMs).coerceAtMost(ceiling)
        }
    }
}

private val HandleTouchHeight = 48.dp
private val HandleTouchWidth = 96.dp
private val PillWidth = 40.dp
private val PillHeight = 6.dp

/**
 * A drag handle on a selected visit's edge: a visible pill with a 48 dp touch target centred on [edgeDp] (the canvas
 * y of the edge). The touch target stays put while dragging, so finger deltas stay stable; only the pill follows
 * ([shiftDp]). Reports the finger's total vertical travel in dp. Not a separate TalkBack target: the drawer's Time
 * group is the non-drag alternative.
 */
@Composable
internal fun ResizeHandle(
    edge: ResizeEdge,
    visitId: String,
    edgeDp: Float,
    shiftDp: Float,
    start: Dp,
    onDragStart: () -> Unit,
    onDragTo: (totalDp: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val currentStart by rememberUpdatedState(onDragStart)
    val currentTo by rememberUpdatedState(onDragTo)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentCancel by rememberUpdatedState(onDragCancel)
    Box(
        modifier = Modifier
            .offset(y = edgeDp.dp - HandleTouchHeight / 2)
            .padding(start = start)
            .fillMaxWidth()
            .height(HandleTouchHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(HandleTouchWidth, HandleTouchHeight)
                // No text, description or action, so TalkBack has nothing to focus here.
                .testTag(handleTag(edge, visitId))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Consumed so neither the canvas scroll nor the "tap elsewhere" deselect sees this touch.
                        down.consume()
                        currentStart()
                        var total = 0f
                        val finished = drag(down.id) { change ->
                            total += change.positionChange().y
                            change.consume()
                            currentTo(total / density.density)
                        }
                        if (finished) currentEnd() else currentCancel()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, with(density) { shiftDp.dp.roundToPx() }) }
                    .size(PillWidth, PillHeight)
                    .background(TarryTheme.colors.ink, TarryTheme.shapes.pill),
            )
        }
    }
}

/** The floating time label next to the dragged edge. */
@Composable
internal fun ResizeLabel(text: String, edgeDp: Float, start: Dp) {
    Box(
        modifier = Modifier
            .offset(y = edgeDp.dp - HandleTouchHeight / 2)
            .padding(start = start, end = TarryTheme.space.s)
            .fillMaxWidth()
            .height(HandleTouchHeight),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = TarryTheme.colors.onInk,
            modifier = Modifier
                .testTag(ResizeLabelTag)
                .background(TarryTheme.colors.ink, TarryTheme.shapes.pill)
                .padding(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.xs),
        )
    }
}

