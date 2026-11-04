package me.tarry.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/** Width of the dark-theme edge on cards, sheets and dialogs. */
val SurfaceEdgeWidth = 1.dp

/**
 * Dark theme only: a 1 dp `line` edge around a card-type surface drawn with [shape]; nothing in the light theme. Colours
 * are untouched (the dark `card` stays as is); the edge alone separates a card from `paper`.
 */
@Composable
fun Modifier.surfaceEdge(shape: Shape): Modifier =
    if (TarryTheme.isDark) border(SurfaceEdgeWidth, TarryTheme.colors.line, shape) else this

/**
 * Dark theme only: the same 1 dp `line` edge along the top of a bottom sheet (its rounded top corners included), none
 * in light. Pass it as the `modifier` of the `ModalBottomSheet`.
 */
@Composable
fun Modifier.sheetEdge(): Modifier {
    if (!TarryTheme.isDark) return this
    val color = TarryTheme.colors.line
    val shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
    val cornerReach = 24.dp
    return drawWithContent {
        drawContent()
        val stroke = SurfaceEdgeWidth.toPx()
        // The outline is inset by half the stroke so the whole 1 dp line lies inside the sheet.
        val outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
        translate(stroke / 2f, stroke / 2f) {
            clipRect(left = -stroke, top = -stroke, right = size.width + stroke, bottom = cornerReach.toPx()) {
                drawOutline(outline, color, style = Stroke(stroke))
            }
        }
    }
}

/** The dark-theme edge as a [BorderStroke] for components with a `border` parameter (`DropdownMenu`); null in light. */
@Composable
fun surfaceEdgeStroke(): BorderStroke? = if (TarryTheme.isDark) BorderStroke(SurfaceEdgeWidth, TarryTheme.colors.line) else null
