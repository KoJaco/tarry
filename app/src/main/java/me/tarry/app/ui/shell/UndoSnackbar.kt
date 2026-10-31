package me.tarry.app.ui.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import me.tarry.app.R
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents

/**
 * Shows each [UndoEvent] from [events] as a snackbar with an "Undo" action, held for [holdMs] (8 s by default). A newer
 * event replaces the one on screen. Tapping Undo calls [onUndo]. The hold is our own timeout rather than M3's short or
 * long duration, so the 8 s is exact and does not depend on the accessibility timeout setting.
 */
@Composable
fun UndoSnackbarEffect(
    events: Flow<UndoEvent>,
    hostState: SnackbarHostState,
    onUndo: (UndoEvent) -> Unit,
    holdMs: Long = UndoEvents.HOLD_MS,
) {
    val resources = LocalResources.current
    val currentOnUndo by rememberUpdatedState(onUndo)
    LaunchedEffect(events, hostState) {
        events.collectLatest { event ->
            val result = withTimeoutOrNull(holdMs) {
                hostState.showSnackbar(
                    message = event.count?.let { resources.getQuantityString(event.message, it, it) } ?: resources.getString(event.message, *event.args.toTypedArray()),
                    actionLabel = if (event.undoable) resources.getString(R.string.undo_action) else null,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed && event.undoable) currentOnUndo(event)
        }
    }
}
