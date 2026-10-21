package me.tarry.app.ui.drawer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.ui.components.LoadingBlocks
import me.tarry.app.ui.theme.TarryTheme

/**
 * The edit drawer for [target]. Hosted by the timeline screen, which keeps the open target; [onRetarget] swaps it
 * (a trip's "Add a stop" opens [EditTarget.NewVisit], merging into the previous visit follows that visit, and saving a
 * new visit reopens it as [EditTarget.Visit]).
 *
 * An M3 modal sheet that opens half height and expands to full. The view model is keyed by the target, so each
 * target gets its own state even though the drawer is not a navigation destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDrawer(
    target: EditTarget,
    onDismiss: () -> Unit,
    onRetarget: (EditTarget) -> Unit,
) {
    val viewModel: EditDrawerViewModel = hiltViewModel(key = "edit-drawer:$target")
    // Idempotent and cheap, so the first frame already has the target (no empty flash).
    remember(viewModel, target) {
        viewModel.load(target)
        target
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentRetarget by rememberUpdatedState(onRetarget)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditDrawerEvent.Dismiss -> currentDismiss()
                is EditDrawerEvent.Retarget -> currentRetarget(event.target)
            }
        }
    }
    val actions = remember(viewModel, target) {
        EditDrawerActions(
            onSetJob = { viewModel.setJob(it) },
            onCreateJob = { viewModel.createJob(it) },
            onToggleTag = { viewModel.toggleTag(it) },
            onCreateTag = { viewModel.createTag(it) },
            onBillable = { viewModel.setBillable(it) },
            onNotes = { viewModel.setNotes(it) },
            onSetPlaceDefault = { viewModel.setPlaceDefault(it) },
            onTrimStart = { viewModel.trimStart(it) },
            onTrimEnd = { viewModel.trimEnd(it) },
            onSplit = { viewModel.split(it) },
            onMerge = { viewModel.merge(it) },
            onChangePlace = { viewModel.changePlace(it) },
            onDelete = { viewModel.delete() },
            onReset = { viewModel.resetToDetected() },
            onAddStop = {
                (target as? EditTarget.Trip)?.let { trip ->
                    state?.let { s -> currentRetarget(EditTarget.NewVisit(s.start, s.end, trip.id)) }
                }
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        shape = TarryTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = TarryTheme.colors.paper,
    ) {
        val current = state
        if (current == null) {
            Column(modifier = Modifier.padding(horizontal = TarryTheme.space.gutter)) { LoadingBlocks(count = 2) }
        } else {
            EditDrawerContent(current, actions)
        }
    }
}
