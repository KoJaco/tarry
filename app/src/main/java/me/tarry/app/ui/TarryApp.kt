package me.tarry.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import me.tarry.app.ui.shell.ShellStatusViewModel
import me.tarry.app.ui.shell.UndoSnackbarEffect
import me.tarry.app.ui.summary.SummaryExportSnackbarEffect
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
import android.util.Log
import kotlinx.coroutines.CancellationException
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
import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.TrackingState
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.app.ui.catalog.JobsScreen
import me.tarry.app.ui.about.AboutScreen
import me.tarry.app.ui.about.LicencesScreen
import me.tarry.app.ui.navigation.AboutRoute
import me.tarry.app.ui.navigation.JobsRoute
import me.tarry.app.ui.navigation.LicencesRoute
import me.tarry.app.ui.navigation.PlaceDetailRoute
import me.tarry.app.ui.navigation.PlacesRoute
import me.tarry.app.ui.navigation.ScheduleRoute
import me.tarry.app.ui.schedule.ScheduleScreen
import me.tarry.app.ui.places.PlaceDetailScreen
import me.tarry.app.ui.places.PlacesScreen
import me.tarry.app.ui.navigation.OnboardingRoute
import me.tarry.app.ui.navigation.PaywallRoute
import me.tarry.app.ui.access.PaywallScreen
import me.tarry.app.ui.navigation.PermissionsRoute
import me.tarry.app.ui.onboarding.OnboardingScreen
import me.tarry.app.ui.theme.TarryTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import me.tarry.app.ui.navigation.SessionsRoute
import me.tarry.app.ui.navigation.SettingsRoute
import me.tarry.app.ui.navigation.SummaryRoute
import me.tarry.app.ui.navigation.TimelineRoute
import me.tarry.app.ui.navigation.TrackingHealthRoute
import me.tarry.app.ui.health.TrackingHealthScreen
import me.tarry.app.ui.navigation.navigateTopLevel
import me.tarry.app.ui.permissions.PermissionsScreen
import me.tarry.app.ui.sessions.SessionsScreen
import me.tarry.app.ui.settings.SettingsScreen
import me.tarry.app.ui.shell.ShellViewModel
import me.tarry.app.ui.shell.TarryBottomBar
import me.tarry.app.R
import androidx.compose.ui.res.stringResource
import me.tarry.app.ui.drawer.JobPickerSheet
import me.tarry.app.ui.drawer.NameDialog
import me.tarry.app.summary.SummaryViewModel
import me.tarry.app.ui.summary.SummaryScreen
import me.tarry.app.ui.timeline.SelectionBar
import me.tarry.app.ui.timeline.TimelineScreen
import me.tarry.app.ui.timeline.TimelineViewModel

/** The multi-select bar's "Assign job" flow: pick a job, or name a new one first. */
private enum class AssignStep { NONE, PICK, NEW }

/** Hilt-wired root. Owns the back stack and connects ViewModels to [TarryAppContent]. */
@Composable
fun TarryApp(
    /** The review-reminder notification was tapped: go to today's Timeline with Needs review on, then call [onOpenReviewHandled]. */
    openReview: Boolean = false,
    onOpenReviewHandled: () -> Unit = {},
    /** The trial-ended alert was tapped: open the paywall, then call [onOpenPaywallHandled]. */
    openPaywall: Boolean = false,
    onOpenPaywallHandled: () -> Unit = {},
    shellViewModel: ShellViewModel = hiltViewModel(),
    statusViewModel: ShellStatusViewModel = hiltViewModel(),
    // Owned here, not by the Timeline entry, so the shell can swap its bottom bar while visits are selected.
    timelineViewModel: TimelineViewModel = hiltViewModel(),
    // Also owned here, so the chosen range and grouping survive a trip to the timeline and back.
    summaryViewModel: SummaryViewModel = hiltViewModel(),
) {
    val backStack = rememberNavBackStack(TimelineRoute)
    val snackbarHostState = remember { SnackbarHostState() }
    UndoSnackbarEffect(statusViewModel.undoEvents, snackbarHostState, statusViewModel::undo)
    // Collected here, not by the Summary screen, so an export finishing on another tab is still announced.
    SummaryExportSnackbarEffect(summaryViewModel.events, snackbarHostState)
    val trackingState by shellViewModel.trackingState.collectAsStateWithLifecycle()
    val trackingDetails by shellViewModel.trackingDetails.collectAsStateWithLifecycle()
    val selection by timelineViewModel.selection.collectAsStateWithLifecycle()
    var assignStep by rememberSaveable { mutableStateOf(AssignStep.NONE) }
    val jobOptions by timelineViewModel.jobOptions.collectAsStateWithLifecycle()
    // Selection belongs to the Timeline screen: leaving it (Settings, Permissions, Sessions) ends it.
    val onTimeline = backStack.lastOrNull() == TimelineRoute
    LaunchedEffect(onTimeline) { if (!onTimeline) timelineViewModel.clearSelection() }
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var customSheetVisible by rememberSaveable { mutableStateOf(false) }
    // ATTENTION has no custom duration: drop the request so the sheet cannot pop up once tracking recovers.
    LaunchedEffect(trackingState) { if (trackingState == TrackingState.ATTENTION) customSheetVisible = false }
    val context = LocalContext.current
    val openPermissions = { if (backStack.lastOrNull() != PermissionsRoute) backStack.add(PermissionsRoute) }
    val openPaywallScreen = { if (backStack.lastOrNull() != PaywallRoute) backStack.add(PaywallRoute) }
    LaunchedEffect(openPaywall) {
        if (openPaywall) {
            openPaywallScreen()
            onOpenPaywallHandled()
        }
    }
    LaunchedEffect(Unit) { shellViewModel.openPaywall.collect { openPaywallScreen() } }
    LaunchedEffect(openReview) {
        if (openReview) {
            backStack.navigateTopLevel(TimelineRoute)
            timelineViewModel.showNeedsReviewToday()
            onOpenReviewHandled()
        }
    }
    // Resolved once per process: a fresh install replaces the stack with onboarding before anything else is drawn.
    var onboardingResolved by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!onboardingResolved) {
            if (resolveNeedsOnboarding(shellViewModel::needsOnboarding)) {
                backStack.clear()
                backStack.add(OnboardingRoute)
            }
            onboardingResolved = true
        }
    }
    if (!onboardingResolved) {
        Box(Modifier.fillMaxSize().background(TarryTheme.colors.paper))
        return
    }
    TarryAppContent(
        trackingState = trackingState,
        attentionCause = trackingDetails.attentionCause,
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
                    onAssignJob = { assignStep = AssignStep.PICK },
                    onIgnorePlace = timelineViewModel::bulkIgnorePlace,
                    onCancel = timelineViewModel::clearSelection,
                )
            }
        } else {
            null
        },
        timelineContent = { TimelineScreen(viewModel = timelineViewModel) },
        summaryContent = {
            SummaryScreen(
                onOpenDay = { date ->
                    timelineViewModel.selectDate(date)
                    backStack.navigateTopLevel(TimelineRoute)
                },
                viewModel = summaryViewModel,
            )
        },
        settingsContent = {
            SettingsScreen(
                trackingState = trackingState,
                onOpenTrackingHealth = { if (backStack.lastOrNull() != TrackingHealthRoute) backStack.add(TrackingHealthRoute) },
                onOpenSessions = { if (backStack.lastOrNull() != SessionsRoute) backStack.add(SessionsRoute) },
                onOpenSchedule = { if (backStack.lastOrNull() != ScheduleRoute) backStack.add(ScheduleRoute) },
                onOpenJobs = { if (backStack.lastOrNull() != JobsRoute) backStack.add(JobsRoute) },
                onOpenPlaces = { if (backStack.lastOrNull() != PlacesRoute) backStack.add(PlacesRoute) },
                onOpenAbout = { if (backStack.lastOrNull() != AboutRoute) backStack.add(AboutRoute) },
                snackbarHostState = snackbarHostState,
            )
        },
        paywallContent = {
            PaywallScreen(onClose = { if (backStack.lastOrNull() == PaywallRoute) backStack.removeAt(backStack.lastIndex) })
        },
        onboardingContent = {
            OnboardingScreen(onFinished = { backStack.navigateTopLevel(TimelineRoute) })
        },
        trackingHealthContent = {
            TrackingHealthScreen(onOpenPermissions = { openPermissions() })
        },
        permissionsContent = {
            PermissionsScreen(onDone = { if (backStack.lastOrNull() == PermissionsRoute) backStack.removeAt(backStack.lastIndex) })
        },
        sessionsContent = { SessionsScreen() },
        scheduleContent = {
            ScheduleScreen(onClose = { if (backStack.lastOrNull() == ScheduleRoute) backStack.removeAt(backStack.lastIndex) })
        },
        jobsContent = { JobsScreen() },
        aboutContent = {
            AboutScreen(
                onOpenLicences = { if (backStack.lastOrNull() != LicencesRoute) backStack.add(LicencesRoute) },
                snackbarHostState = snackbarHostState,
            )
        },
        licencesContent = { LicencesScreen() },
        placesContent = { PlacesScreen(onOpenPlace = { if (backStack.lastOrNull() != PlaceDetailRoute(it)) backStack.add(PlaceDetailRoute(it)) }) },
        placeDetailContent = { id ->
            PlaceDetailScreen(
                placeId = id,
                onClose = { if (backStack.lastOrNull() == PlaceDetailRoute(id)) backStack.removeAt(backStack.lastIndex) },
            )
        },
    )
    if (selection.isEmpty() && assignStep != AssignStep.NONE) assignStep = AssignStep.NONE
    if (assignStep == AssignStep.PICK) {
        JobPickerSheet(
            title = stringResource(R.string.selection_assign_job_title),
            noneLabel = null,
            jobs = jobOptions,
            selectedId = null,
            onSelect = { id ->
                assignStep = AssignStep.NONE
                id?.let(timelineViewModel::assignJob)
            },
            onNewJob = { assignStep = AssignStep.NEW },
            onDismiss = { assignStep = AssignStep.NONE },
        )
    }
    if (assignStep == AssignStep.NEW) {
        NameDialog(
            title = stringResource(R.string.edit_job_new_title),
            label = stringResource(R.string.edit_job_name_label),
            onConfirm = {
                assignStep = AssignStep.NONE
                timelineViewModel.createJobAndAssign(it)
            },
            onDismiss = { assignStep = AssignStep.NONE },
        )
    }
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
                onOpenPaywall = { openPaywallScreen() },
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
    summaryContent: @Composable () -> Unit = {},
    timelineContent: @Composable () -> Unit = { TimelineScreen() },
    onboardingContent: @Composable () -> Unit = {},
    paywallContent: @Composable () -> Unit = {},
    trackingHealthContent: @Composable () -> Unit = {},
    permissionsContent: @Composable () -> Unit = {},
    sessionsContent: @Composable () -> Unit = {},
    scheduleContent: @Composable () -> Unit = {},
    jobsContent: @Composable () -> Unit = {},
    aboutContent: @Composable () -> Unit = {},
    licencesContent: @Composable () -> Unit = {},
    placesContent: @Composable () -> Unit = {},
    placeDetailContent: @Composable (String) -> Unit = {},
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    /** Replaces the bottom navigation while visits are selected for a bulk edit (null = the normal bar). */
    selectionBar: (@Composable () -> Unit)? = null,
    onToggleLongClick: () -> Unit = {},
    /** Why the toggle is in ATTENTION; TRIAL_ENDED turns the pill into "Unlock". */
    attentionCause: AttentionCause? = null,
) {
    val topLevel = backStack.lastOrNull { it == TimelineRoute || it == SettingsRoute || it == SummaryRoute }
    // Onboarding and the paywall are full screen: no bottom bar and no tracking toggle.
    val fullScreen = backStack.lastOrNull().let { it == OnboardingRoute || it == PaywallRoute }
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        bottomBar = if (fullScreen) {
            {}
        } else selectionBar ?: {
            TarryBottomBar(
                trackingState = trackingState,
                attentionCause = attentionCause,
                timelineSelected = topLevel != SettingsRoute && topLevel != SummaryRoute,
                summarySelected = topLevel == SummaryRoute,
                onSummaryClick = { onNavigate(SummaryRoute) },
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
                entry<SummaryRoute> { summaryContent() }
                entry<OnboardingRoute> { onboardingContent() }
                entry<PaywallRoute> { paywallContent() }
                entry<TrackingHealthRoute> { trackingHealthContent() }
                entry<PermissionsRoute> { permissionsContent() }
                entry<SessionsRoute> { sessionsContent() }
                entry<ScheduleRoute> { scheduleContent() }
                entry<JobsRoute> { jobsContent() }
                entry<AboutRoute> { aboutContent() }
                entry<LicencesRoute> { licencesContent() }
                entry<PlacesRoute> { placesContent() }
                entry<PlaceDetailRoute> { placeDetailContent(it.id) }
            },
        )
    }
}

/**
 * Runs the start-up onboarding check. A failing check (a DataStore or Room read error) must not crash the app before the
 * first frame: it is logged and treated as "no onboarding", so the app opens normally and the gate still resolves.
 */
internal suspend fun resolveNeedsOnboarding(check: suspend () -> Boolean): Boolean = try {
    check()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e("TarryApp", "Onboarding check failed; continuing without onboarding", e)
    false
}
