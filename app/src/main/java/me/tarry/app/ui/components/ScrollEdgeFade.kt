package me.tarry.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width of the fade on a sideways-scrolling row's edges (Summary range chips, timeline review row, export actions). */
internal val ChipsEdgeFadeWidth = 32.dp

/** Which horizontal bands (start..end px) fade out: the left one while scrolled away from the start, the right one while more follows. */
internal fun scrollEdgeFadeBands(canScrollBackward: Boolean, canScrollForward: Boolean, widthPx: Float, fadePx: Float): List<ClosedFloatingPointRange<Float>> {
    val w = fadePx.coerceAtMost(widthPx / 2f)
    return buildList {
        if (canScrollBackward) add(0f..w)
        if (canScrollForward) add((widthPx - w)..widthPx)
    }
}

/** Fades the right edge while [state] can scroll forward and the left edge while it can scroll back; nothing when it all fits. */
// Black and Transparent below are only the DstIn alpha mask (opacity of the fade), never a visible colour.
internal fun Modifier.scrollEdgeFade(state: ScrollState, width: Dp = ChipsEdgeFadeWidth): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val bands = scrollEdgeFadeBands(state.canScrollBackward, state.canScrollForward, size.width, width.toPx())
        for (band in bands) {
            val toRight = band.start > 0f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = if (toRight) listOf(Color.Black, Color.Transparent) else listOf(Color.Transparent, Color.Black),
                    startX = band.start,
                    endX = band.endInclusive,
                ),
                topLeft = Offset(band.start, 0f),
                size = Size(band.endInclusive - band.start, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** [scrollEdgeFade] for a row that scrolls by hand: the fade and the scroll share one [state]. */
internal fun Modifier.horizontalScrollEdgeFade(state: ScrollState, width: Dp = ChipsEdgeFadeWidth): Modifier =
    scrollEdgeFade(state, width).horizontalScroll(state)

/** A [Row] that scrolls sideways (never wraps) and fades its edges while more content lies that way. */
@Composable
internal fun FadingScrollRow(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    val viewport = remember { ScrollRowViewport() }
    viewport.state = scrollState
    CompositionLocalProvider(LocalScrollRowViewport provides viewport) {
        Row(
            modifier = modifier
                .onGloballyPositioned { viewport.coordinates = it }
                .horizontalScrollEdgeFade(scrollState),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            content = content,
        )
    }
}

/** The visible window of a [FadingScrollRow] and its scroll state, so a child can scroll itself into view. */
internal class ScrollRowViewport(var coordinates: LayoutCoordinates? = null, var state: ScrollState? = null)

private val LocalScrollRowViewport = staticCompositionLocalOf<ScrollRowViewport?> { null }

/**
 * How far to scroll (positive = towards the end) so a child spanning [childStart]..[childEnd] lies fully inside
 * 0..[viewportWidth] (all in the same px space). 0 when it already fits; when it is wider than the viewport its start wins.
 */
internal fun scrollDeltaToReveal(childStart: Float, childEnd: Float, viewportWidth: Float): Float = when {
    childStart < 0f -> childStart
    childEnd > viewportWidth -> minOf(childEnd - viewportWidth, childStart)
    else -> 0f
}

/**
 * Inside a [FadingScrollRow]: when [selected] turns true (and on first composition) scroll the row so this child is fully
 * visible, clear of the edge fade. Snaps instead of animating under reduced motion.
 */
@Composable
internal fun Modifier.scrollIntoViewWhenSelected(selected: Boolean, fadeWidth: Dp = ChipsEdgeFadeWidth): Modifier {
    val viewport = LocalScrollRowViewport.current ?: return this
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val reduced = rememberReducedMotion()
    val fadePx = with(LocalDensity.current) { fadeWidth.toPx() }
    val placed = coords != null
    LaunchedEffect(selected, placed) {
        val child = coords ?: return@LaunchedEffect
        val row = viewport.coordinates ?: return@LaunchedEffect
        val state = viewport.state ?: return@LaunchedEffect
        if (!selected || !child.isAttached || !row.isAttached) return@LaunchedEffect
        val start = child.positionInRoot().x - row.positionInRoot().x
        val width = row.size.width.toFloat()
        val inset = fadePx.coerceAtMost(width / 4f)
        // Keep the chip clear of a fade, which only shows on a side that can still scroll.
        val left = if (state.value > 0) inset else 0f
        val right = if (state.value < state.maxValue) inset else 0f
        val delta = scrollDeltaToReveal(start - left, start + child.size.width + right, width)
        if (delta == 0f) return@LaunchedEffect
        val target = (state.value + delta).toInt().coerceIn(0, state.maxValue)
        if (reduced) state.scrollTo(target) else state.animateScrollTo(target)
    }
    return this.onGloballyPositioned { coords = it }
}
