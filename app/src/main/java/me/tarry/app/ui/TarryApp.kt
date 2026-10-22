package me.tarry.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import me.tarry.app.ui.shell.ShellStatusViewModel
import me.tarry.app.ui.shell.UndoSnackbarEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException
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
import me.tarry.app.ui.navigation.PermissionsRoute
import me.tarry.app.ui.navigation.SessionsRoute
import me.tarry.app.ui.navigation.SettingsRoute
import me.tarry.app.ui.navigation.TimelineRoute
import me.tarry.app.ui.navigation.navigateTopLevel
import me.tarry.app.ui.permissions.PermissionsScreen
import me.tarry.app.ui.sessions.SessionsScreen
import me.tarry.app.ui.settings.SettingsScreen
import me.tarry.app.ui.shell.ShellViewModel
import me.tarry.app.ui.shell.TarryBottomBar
import me.tarry.app.ui.timeline.TimelineScreen

/** Hilt-wired root. Owns the back stack and connects ViewModels to [TarryAppContent]. */
@Composable
fun TarryApp(
    shellViewModel: ShellViewModel = hiltViewModel(),
    statusViewModel: ShellStatusViewModel = hiltViewModel(),
) {
    val backStack = rememberNavBackStack(TimelineRoute)
    val snackbarHostState = remember { SnackbarHostState() }
    UndoSnackbarEffect(statusViewModel.undoEvents, snackbarHostState, statusViewModel::undo)
    val trackingState by shellViewModel.trackingState.collectAsStateWithLifecycle()
    val trackingDetails by shellViewModel.trackingDetails.collectAsStateWithLifecycle()
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val openPermissions = { if (backStack.lastOrNull() != PermissionsRoute) backStack.add(PermissionsRoute) }
    var initialChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialChecked) {
            initialChecked = true
            if (shellViewModel.needsInitialPermissions()) openPermissions()
        }
    }
    TarryAppContent(
        trackingState = trackingState,
        backStack = backStack,
        onNavigate = { backStack.navigateTopLevel(it) },
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        onToggleClick = { sheetVisible = true },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        settingsContent = {
            SettingsScreen(
                onOpenPermissions = { openPermissions() },
                onOpenSessions = { if (backStack.lastOrNull() != SessionsRoute) backStack.add(SessionsRoute) },
            )
        },
        permissionsContent = {
            PermissionsScreen(onDone = { if (backStack.lastOrNull() == PermissionsRoute) backStack.removeAt(backStack.lastIndex) })
        },
        sessionsContent = { SessionsScreen() },
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
                onOpenLocationSettings = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        openAppSettings()
                    }
                },
                onOpenPermissionsScreen = { openPermissions() },
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
    timelineContent: @Composable () -> Unit = { TimelineScreen() },
    permissionsContent: @Composable () -> Unit = {},
    sessionsContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
) {
    val topLevel = backStack.lastOrNull { it == TimelineRoute || it == SettingsRoute }
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        bottomBar = {
            TarryBottomBar(
                trackingState = trackingState,
                timelineSelected = topLevel != SettingsRoute,
                settingsSelected = topLevel == SettingsRoute,
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
                entry<TimelineRoute> { timelineContent() }
                entry<SettingsRoute> { settingsContent() }
                entry<PermissionsRoute> { permissionsContent() }
                entry<SessionsRoute> { sessionsContent() }
            },
        )
    }
}
