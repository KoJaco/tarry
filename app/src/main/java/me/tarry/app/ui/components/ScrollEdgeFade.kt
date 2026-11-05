package me.tarry.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
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
    Row(
        modifier = modifier.horizontalScrollEdgeFade(scrollState),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}
