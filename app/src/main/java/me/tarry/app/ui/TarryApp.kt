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
import me.tarry.app.ui.shell.CustomDurationActions
import me.tarry.app.ui.shell.CustomDurationSheet
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
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.app.ui.catalog.JobsScreen
import me.tarry.app.ui.navigation.JobsRoute
import me.tarry.app.ui.navigation.PlaceDetailRoute
import me.tarry.app.ui.navigation.PlacesRoute
import me.tarry.app.ui.navigation.ScheduleRoute
import me.tarry.app.ui.schedule.ScheduleScreen
import me.tarry.app.ui.places.PlaceDetailScreen
import me.tarry.app.ui.places.PlacesScreen
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
import me.tarry.app.ui.timeline.SelectionBar
import me.tarry.app.ui.timeline.TimelineScreen
import me.tarry.app.ui.timeline.TimelineViewModel

/** Hilt-wired root. Owns the back stack and connects ViewModels to [TarryAppContent]. */
@Composable
fun TarryApp(
    shellViewModel: ShellViewModel = hiltViewModel(),
    statusViewModel: ShellStatusViewModel = hiltViewModel(),
    // Owned here, not by the Timeline entry, so the shell can swap its bottom bar while visits are selected.
    timelineViewModel: TimelineViewModel = hiltViewModel(),
) {
    val backStack = rememberNavBackStack(TimelineRoute)
    val snackbarHostState = remember { SnackbarHostState() }
    UndoSnackbarEffect(statusViewModel.undoEvents, snackbarHostState, statusViewModel::undo)
    val trackingState by shellViewModel.trackingState.collectAsStateWithLifecycle()
    val trackingDetails by shellViewModel.trackingDetails.collectAsStateWithLifecycle()
    val selection by timelineViewModel.selection.collectAsStateWithLifecycle()
    // Selection belongs to the Timeline screen: leaving it (Settings, Permissions, Sessions) ends it.
    val onTimeline = backStack.lastOrNull() == TimelineRoute
    LaunchedEffect(onTimeline) { if (!onTimeline) timelineViewModel.clearSelection() }
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var customSheetVisible by rememberSaveable { mutableStateOf(false) }
    // ATTENTION has no custom duration: drop the request so the sheet cannot pop up once tracking recovers.
    LaunchedEffect(trackingState) { if (trackingState == TrackingState.ATTENTION) customSheetVisible = false }
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
        // ATTENTION has nothing to time: its long-press opens the same sheet as a tap.
        onToggleLongClick = { if (trackingState == TrackingState.ATTENTION) sheetVisible = true else customSheetVisible = true },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        selectionBar = if (selection.isNotEmpty() && onTimeline) {
            {
                SelectionBar(
                    count = selection.size,
                    onIgnorePlace = timelineViewModel::bulkIgnorePlace,
                    onCancel = timelineViewModel::clearSelection,
                )
            }
        } else {
            null
        },
        timelineContent = { TimelineScreen(viewModel = timelineViewModel) },
        settingsContent = {
            SettingsScreen(
                onOpenPermissions = { openPermissions() },
                onOpenSessions = { if (backStack.lastOrNull() != SessionsRoute) backStack.add(SessionsRoute) },
                onOpenSchedule = { if (backStack.lastOrNull() != ScheduleRoute) backStack.add(ScheduleRoute) },
                onOpenJobs = { if (backStack.lastOrNull() != JobsRoute) backStack.add(JobsRoute) },
                onOpenPlaces = { if (backStack.lastOrNull() != PlacesRoute) backStack.add(PlacesRoute) },
            )
        },
        permissionsContent = {
            PermissionsScreen(onDone = { if (backStack.lastOrNull() == PermissionsRoute) backStack.removeAt(backStack.lastIndex) })
        },
        sessionsContent = { SessionsScreen() },
        scheduleContent = {
            ScheduleScreen(onClose = { if (backStack.lastOrNull() == ScheduleRoute) backStack.removeAt(backStack.lastIndex) })
        },
        jobsContent = { JobsScreen() },
        placesContent = { PlacesScreen(onOpenPlace = { if (backStack.lastOrNull() != PlaceDetailRoute(it)) backStack.add(PlaceDetailRoute(it)) }) },
        placeDetailContent = { id ->
            PlaceDetailScreen(
                placeId = id,
                onClose = { if (backStack.lastOrNull() == PlaceDetailRoute(id)) backStack.removeAt(backStack.lastIndex) },
            )
        },
    )
    if (customSheetVisible && trackingState != TrackingState.ATTENTION) {
        CustomDurationSheet(
            state = trackingState,
            details = trackingDetails,
            stepMin = shellViewModel.customDurationStepMin,
            maxMin = shellViewModel.customDurationMaxMin,
            actions = CustomDurationActions(
                onStart = { shellViewModel.start(ManualOverrideEndMode.DURATION, it) },
                onPause = shellViewModel::pauseFor,
                onStartUntil = shellViewModel::startUntil,
                onPauseUntil = shellViewModel::pauseUntil,
            ),
            onDismiss = { customSheetVisible = false },
        )
    }
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
    scheduleContent: @Composable () -> Unit = {},
    jobsContent: @Composable () -> Unit = {},
    placesContent: @Composable () -> Unit = {},
    placeDetailContent: @Composable (String) -> Unit = {},
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    /** Replaces the bottom navigation while visits are selected for a bulk edit (null = the normal bar). */
    selectionBar: (@Composable () -> Unit)? = null,
    onToggleLongClick: () -> Unit = {},
) {
    val topLevel = backStack.lastOrNull { it == TimelineRoute || it == SettingsRoute }
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        bottomBar = selectionBar ?: {
            TarryBottomBar(
                trackingState = trackingState,
                timelineSelected = topLevel != SettingsRoute,
                settingsSelected = topLevel == SettingsRoute,
                onTimelineClick = { onNavigate(TimelineRoute) },
                onSettingsClick = { onNavigate(SettingsRoute) },
                onToggleClick = onToggleClick,
                onToggleLongClick = onToggleLongClick,
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
                entry<ScheduleRoute> { scheduleContent() }
                entry<JobsRoute> { jobsContent() }
                entry<PlacesRoute> { placesContent() }
                entry<PlaceDetailRoute> { placeDetailContent(it.id) }
            },
        )
    }
}
