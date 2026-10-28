package me.tarry.app.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.ui.drawer.EditDrawer
import me.tarry.app.ui.drawer.EditTarget

/** Hilt-wired day list. Keeps the open edit target (it survives rotation) and hosts the edit drawer. */
@Composable
fun TimelineScreen(
    modifier: Modifier = Modifier,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val date by viewModel.selectedDate.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val jobBlocks by viewModel.jobBlocks.collectAsStateWithLifecycle()
    val jobSuggestions by viewModel.jobSuggestions.collectAsStateWithLifecycle()
    var editTarget by rememberSaveable(stateSaver = EditTargetSaver) { mutableStateOf<EditTarget?>(null) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // After midnight the old "today" would stay selected; re-derive it when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }
    TimelineContent(
        date = date,
        items = items,
        onPreviousDay = viewModel::previousDay,
        onNextDay = viewModel::nextDay,
        today = today,
        onSelectDate = viewModel::selectDate,
        onEdit = { editTarget = it },
        onResizeVisit = viewModel::resizeVisit,
        minStayMs = viewModel.minStayMs,
        selection = selection,
        onStartSelection = viewModel::startSelection,
        onToggleSelected = viewModel::toggleSelected,
        onClearSelection = viewModel::clearSelection,
        onAddJob = { scope.launch { editTarget = viewModel.addJobTarget() } },
        showMap = showMap,
        onShowMapChange = { showMap = it },
        // Collected only while the Map tab shows, so the route isn't built otherwise.
        dayMap = if (showMap) viewModel.dayMap.collectAsStateWithLifecycle().value else null,
        trackingMarkers = viewModel.trackingMarkers.collectAsStateWithLifecycle().value,
        jobBlocks = jobBlocks,
        jobSuggestions = jobSuggestions,
        onOpenJob = { editTarget = EditTarget.Job(it) },
        onConfirmSuggestion = viewModel::confirmSuggestion,
        onCreateJob = { start, end -> editTarget = EditTarget.NewJob(start, end) },
        onJobRangeChange = viewModel::moveOrResizeBlock,
        modifier = modifier,
    )
    editTarget?.let { target ->
        EditDrawer(
            target = target,
            onDismiss = { editTarget = null },
            onRetarget = { editTarget = it },
        )
    }
}
