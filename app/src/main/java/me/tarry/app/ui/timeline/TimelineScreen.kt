package me.tarry.app.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Hilt-wired day list. */
@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val date by viewModel.selectedDate.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    TimelineContent(
        date = date,
        items = items,
        onPreviousDay = viewModel::previousDay,
        onNextDay = viewModel::nextDay,
        modifier = modifier,
    )
}
