package me.tarry.app.ui.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hilt-wired permission flow. Re-checks the platform on every resume (the user may come back from a system
 * settings page) and after each runtime-dialog result. A runtime request that returns without a grant is not
 * repeated (the system may no longer show it); the next tap opens the app's settings page instead.
 */
@Composable
fun PermissionsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Steps already requested once, as comma-separated names so it survives rotation.
    var requested by rememberSaveable { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
    }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }

    fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(PermissionIntents.appSettings(context))
        }
    }

    fun requestRuntime(step: PermissionStep, launch: () -> Unit) {
        if (step.name in requested.split(',')) {
            openSettings(PermissionIntents.appSettings(context))
        } else {
            requested = "$requested,${step.name}"
            launch()
        }
    }

    val actions = remember(viewModel, context) {
        PermissionsActions(
            onGrant = { step ->
                when (step) {
                    PermissionStep.LOCATION -> requestRuntime(step) {
                        multiple.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }
                    PermissionStep.BACKGROUND_LOCATION -> viewModel.showBackgroundDisclosure()
                    PermissionStep.ACTIVITY_RECOGNITION ->
                        requestRuntime(step) { single.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
                    PermissionStep.NOTIFICATIONS ->
                        requestRuntime(step) { single.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    PermissionStep.EXACT_ALARMS -> openSettings(PermissionIntents.exactAlarms(context))
                    PermissionStep.BATTERY_OPTIMISATION -> openSettings(PermissionIntents.batteryOptimisation(context))
                }
            },
            onChange = { step ->
                openSettings(
                    when (step) {
                        PermissionStep.NOTIFICATIONS -> PermissionIntents.notificationSettings(context)
                        PermissionStep.EXACT_ALARMS -> PermissionIntents.exactAlarms(context)
                        PermissionStep.BATTERY_OPTIMISATION -> PermissionIntents.batteryOptimisation(context)
                        else -> PermissionIntents.appSettings(context)
                    },
                )
            },
            onSkip = viewModel::skip,
            onConfirmBackgroundDisclosure = {
                viewModel.hideBackgroundDisclosure()
                // The system sends the user to the "Allow all the time" settings page (API 30+) or a dialog (API 29).
                requestRuntime(PermissionStep.BACKGROUND_LOCATION) {
                    single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onDismissBackgroundDisclosure = viewModel::hideBackgroundDisclosure,
            onDone = onDone,
        )
    }
    PermissionsContent(state, actions, modifier)
}
