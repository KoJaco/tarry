package me.tarry.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.theme.sheetEdge

/** The bottom-sheet shape: container corners on top, square at the bottom. */
@Composable
fun tarrySheetShape(): Shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))

/**
 * Every bottom sheet in the app goes through this. The dark-theme top edge is drawn by the drag handle, which sits inside
 * the sheet's own clipped surface, so it follows the sheet's top corners and moves with it (a modifier on the
 * `ModalBottomSheet` itself stays at the window's top instead).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TarrySheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = tarrySheetShape(),
    containerColor: Color = TarryTheme.colors.paper,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        dragHandle = { SheetEdgeHandle() },
        content = content,
    )
}

/** The standard drag handle on a full-width box that carries the dark-theme top edge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetEdgeHandle() {
    Box(Modifier.testTag("sheetEdgeHandle").fillMaxWidth().sheetEdge(), contentAlignment = Alignment.TopCenter) { BottomSheetDefaults.DragHandle() }
}
