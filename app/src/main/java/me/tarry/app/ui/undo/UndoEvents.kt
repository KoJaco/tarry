package me.tarry.app.ui.undo

import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import me.tarry.app.R
import me.tarry.core.data.repository.EditResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.core.data.repository.Snapshot
import me.tarry.core.data.repository.TimelineEditor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A snackbar message: a destructive edit that can be undone ("Visit deleted · Undo"), or, when [snapshot] is null, a
 * plain notice with no action (an edit that was refused, e.g. "That is still in use.").
 */
data class UndoEvent(
    /** A string resource, or a plurals resource when [count] is set (bulk edits: "5 visits updated"). */
    @param:AnyRes val message: Int,
    val snapshot: Snapshot?,
    val count: Int? = null,
    /** An undo that is not a timeline [Snapshot] (a deleted job block coming back). Takes the place of [snapshot]. */
    val onUndo: (suspend () -> Unit)? = null,
) {
    /** Whether the snackbar offers an Undo action. */
    val undoable: Boolean get() = snapshot != null || onUndo != null
}


/**
 * App-wide channel between whoever makes a destructive edit (the drawer, bulk actions) and the snackbar host in the
 * shell. Emitters call [offer]; the host shows the newest event for [HOLD_MS] and calls [undo] on its action.
 */
@Singleton
class UndoEvents @Inject constructor(
    private val editor: TimelineEditor,
    private val scheduler: SegmentationScheduler,
) {
    private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UndoEvent> = _events.asSharedFlow()

    fun offer(event: UndoEvent) {
        _events.tryEmit(event)
    }

    /** Shows [message] (a string resource) with no Undo action. */
    fun notice(@StringRes message: Int) = offer(UndoEvent(message, null))

    /** Restores the rows captured before the edit and re-runs segmentation over the rewound range. */
    suspend fun undo(event: UndoEvent) {
        event.onUndo?.let {
            it()
            return
        }
        editor.restore(event.snapshot ?: return)
        scheduler.enqueueNow()
    }

    companion object {
        const val HOLD_MS = 8_000L
    }
}

/** The message for a refused edit, or null when the result is Ok. Shared by the catalog and bulk-edit callers. */
@StringRes
fun EditResult.noticeRes(): Int? = when (this) {
    is EditResult.Ok -> null
    EditResult.Overlap -> R.string.edit_error_overlap
    EditResult.EmptyRange -> R.string.edit_error_empty_range
    EditResult.NotFound -> R.string.edit_error_not_found
    EditResult.BlankName -> R.string.edit_error_blank_name
    is EditResult.InUse -> R.string.edit_error_in_use
}
