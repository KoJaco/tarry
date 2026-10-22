package me.tarry.app.ui.undo

import androidx.annotation.AnyRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.tarry.app.segmentation.SegmentationScheduler
import me.tarry.core.data.repository.Snapshot
import me.tarry.core.data.repository.TimelineEditor
import javax.inject.Inject
import javax.inject.Singleton

/** A destructive edit that can be undone from the snackbar, e.g. "Visit deleted · Undo". */
data class UndoEvent(
    /** A string resource, or a plurals resource when [count] is set (bulk edits: "5 visits updated"). */
    @param:AnyRes val message: Int,
    val snapshot: Snapshot,
    val count: Int? = null,
)

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

    /** Restores the rows captured before the edit and re-runs segmentation over the rewound range. */
    suspend fun undo(event: UndoEvent) {
        editor.restore(event.snapshot)
        scheduler.enqueueNow()
    }

    companion object {
        const val HOLD_MS = 8_000L
    }
}
