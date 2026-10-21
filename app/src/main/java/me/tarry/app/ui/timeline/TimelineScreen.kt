package me.tarry.app.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Hilt-wired day list. */
@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val date by viewModel.selectedDate.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    // After midnight the old "today" would stay selected; re-derive it when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }
    TimelineContent(
        date = date,
        items = items,
        onPreviousDay = viewModel::previousDay,
        onNextDay = viewModel::nextDay,
        today = today,
        onSelectDate = viewModel::selectDate,
        modifier = modifier,
    )
}
