package me.tarry.app.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Hilt-wired Schedule screen, pushed over Settings. [onClose] pops it after back-with-edits was confirmed. */
@Composable
fun ScheduleScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: ScheduleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScheduleEditorContent(
        state = state,
        actions = ScheduleEditorActions(
            onModeChange = viewModel::setMode,
            onShiftWeek = viewModel::shiftWeek,
            onToggleCell = viewModel::toggleCell,
            onStroke = viewModel::setDraft,
            onClearAll = viewModel::clearAll,
            onSetDayHours = viewModel::setDayHours,
            onCopyDay = viewModel::copyDay,
            onClearDay = viewModel::clearDay,
            onSave = viewModel::save,
            onDiscard = viewModel::discard,
            onUseEveryWeek = viewModel::useEveryWeekSchedule,
            onConfirmPending = viewModel::confirmPending,
            onCancelPending = viewModel::cancelPending,
        ),
        onClose = onClose,
        modifier = modifier,
    )
}
