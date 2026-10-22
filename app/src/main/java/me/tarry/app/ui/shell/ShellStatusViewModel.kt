package me.tarry.app.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.app.ui.undo.UndoEvents
import javax.inject.Inject

/** What the shell needs besides tracking state: the undo snackbar feed. */
@HiltViewModel
class ShellStatusViewModel internal constructor(
    val undoEvents: SharedFlow<UndoEvent>,
    private val performUndo: suspend (UndoEvent) -> Unit,
) : ViewModel() {
    @Inject
    constructor(undo: UndoEvents) : this(undo.events, undo::undo)

    fun undo(event: UndoEvent) {
        viewModelScope.launch { performUndo(event) }
    }
}
