package me.tarry.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.ui.permissions.PermissionsViewModel
import me.tarry.app.ui.permissions.rememberPermissionsActions

/**
 * Hilt-wired onboarding. Permissions reuse the Permissions screen's ViewModel and launchers; the platform is re-read on every
 * resume and after each dialog result, and a page moves on by itself when its permission turns granted. [onFinished] leaves
 * onboarding for the Timeline (Open Tarry).
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions by permissionsViewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionsViewModel.refresh() }
    AdvanceWhenGranted(state.page, state.permissionPageSatisfied(permissions)) { viewModel.next(permissions) }

    val permissionActions = rememberPermissionsActions(permissionsViewModel, onDone = onFinished)
    OnboardingContent(
        state = state,
        permissions = permissions,
        permissionActions = permissionActions,
        actions = OnboardingActions(
            onNext = { viewModel.next(permissions) },
            onBack = { viewModel.back(permissions) },
            onToggleCell = viewModel::toggleCell,
            onStroke = viewModel::setSchedule,
            onSaveHours = viewModel::saveHours,
            onNotNow = viewModel::skipPermissions,
            onOpenTarry = onFinished,
        ),
        modifier = modifier,
    )
}

/**
 * Calls [onAdvance] when [satisfied] turns true while [page] is showing, never on arrival at a page that is already
 * satisfied (Back must stay put there).
 */
@Composable
internal fun AdvanceWhenGranted(page: OnboardingPage, satisfied: Boolean, onAdvance: () -> Unit) {
    val advance by rememberUpdatedState(onAdvance)
    var satisfiedOnPage by remember(page) { mutableStateOf(satisfied) }
    LaunchedEffect(page, satisfied) {
        if (satisfied && !satisfiedOnPage) advance()
        satisfiedOnPage = satisfied
    }
}
