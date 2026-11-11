package me.tarry.app.ui.health

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.ui.permissions.PermissionIntents
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.PermissionsViewModel
import me.tarry.app.ui.permissions.rememberPermissionsActions

/**
 * Hilt-wired Tracking health. Re-reads the platform on every resume (the user may be back from a system settings page). A
 * runtime permission or the background-location disclosure opens the Permissions screen ([onOpenPermissions]); the exact-alarm,
 * battery and location-services fixes open their system pages directly.
 */
@Composable
fun TrackingHealthScreen(
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrackingHealthViewModel = hiltViewModel(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Also clears a resolved health issue and refreshes the toggle state, as the Permissions screen does.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionsViewModel.refresh()
        viewModel.refresh()
    }
    val permissionActions = rememberPermissionsActions(permissionsViewModel, onDone = {})

    TrackingHealthContent(
        state = state,
        actions = TrackingHealthActions(
            onFix = { id ->
                when (id) {
                    HealthCheckId.LOCATION_SERVICES -> openLocationSettings(context)
                    HealthCheckId.EXACT_ALARMS -> permissionActions.onGrant(PermissionStep.EXACT_ALARMS)
                    HealthCheckId.BATTERY -> permissionActions.onGrant(PermissionStep.BATTERY_OPTIMISATION)
                    HealthCheckId.PRECISE_LOCATION,
                    HealthCheckId.BACKGROUND_LOCATION,
                    HealthCheckId.PHYSICAL_ACTIVITY,
                    HealthCheckId.NOTIFICATIONS,
                    -> onOpenPermissions()
                }
            },
            onTestTracking = viewModel::testTracking,
            onFixPermission = onOpenPermissions,
        ),
        modifier = modifier,
    )
}

private fun openLocationSettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(PermissionIntents.appSettings(context))
    }
}
