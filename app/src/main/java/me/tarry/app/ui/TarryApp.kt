package me.tarry.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import me.tarry.app.ui.shell.TrackingSheet
import me.tarry.app.ui.shell.TrackingSheetActions
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.navigation.SettingsRoute
import me.tarry.app.ui.navigation.TimelineRoute
import me.tarry.app.ui.navigation.navigateTopLevel
import me.tarry.app.ui.settings.SettingsScreen
import me.tarry.app.ui.shell.ShellViewModel
import me.tarry.app.ui.shell.TarryBottomBar
import me.tarry.app.ui.timeline.TimelineScreen

/** Hilt-wired root. Owns the back stack and connects ViewModels to [TarryAppContent]. */
@Composable
fun TarryApp(shellViewModel: ShellViewModel = hiltViewModel()) {
    val backStack = rememberNavBackStack(TimelineRoute)
    val trackingState by shellViewModel.trackingState.collectAsStateWithLifecycle()
    val trackingDetails by shellViewModel.trackingDetails.collectAsStateWithLifecycle()
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    TarryAppContent(
        trackingState = trackingState,
        backStack = backStack,
        onNavigate = { backStack.navigateTopLevel(it) },
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        onToggleClick = { sheetVisible = true },
        settingsContent = { SettingsScreen() },
    )
    if (sheetVisible) {
        val openAppSettings = { context.startActivity(appSettingsIntent(context)) }
        TrackingSheet(
            state = trackingState,
            details = trackingDetails,
            startDurationsMin = shellViewModel.startDurationsMin,
            actions = TrackingSheetActions(
                onStart = shellViewModel::start,
                onPause = shellViewModel::pause,
                onResume = shellViewModel::resume,
                onStop = shellViewModel::stop,
                onOpenLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                // TODO(2a-T9): navigate to PermissionsRoute instead.
                onOpenPermissionsScreen = openAppSettings,
                onOpenAppSettings = openAppSettings,
            ),
            onDismiss = { sheetVisible = false },
        )
    }
}

private fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/** Stateless shell (no Hilt) so it can be Compose-tested directly. */
@Composable
fun TarryAppContent(
    trackingState: TrackingState,
    backStack: List<NavKey>,
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    onToggleClick: () -> Unit,
    settingsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            TarryBottomBar(
                trackingState = trackingState,
                timelineSelected = backStack.lastOrNull() != SettingsRoute,
                settingsSelected = backStack.lastOrNull() == SettingsRoute,
                onTimelineClick = { onNavigate(TimelineRoute) },
                onSettingsClick = { onNavigate(SettingsRoute) },
                onToggleClick = onToggleClick,
            )
        },
    ) { innerPadding ->
        NavDisplay(
            backStack = backStack,
            onBack = onBack,
            modifier = Modifier.padding(innerPadding),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<TimelineRoute> { TimelineScreen() }
                entry<SettingsRoute> { settingsContent() }
            },
        )
    }
}
