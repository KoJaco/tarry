package me.tarry.app.ui.health

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.settings.labelRes
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.titleRes
import me.tarry.app.ui.shell.messageRes
import me.tarry.app.ui.theme.TarryTheme

/** What Tracking health can ask for. The stateful wrapper wires these to launchers, intents and navigation. */
data class TrackingHealthActions(
    /** A check that is not ok was tapped: take the user to its fix. */
    val onFix: (HealthCheckId) -> Unit,
    val onTestTracking: () -> Unit,
    /** The Denied card's fix: the Permissions screen. */
    val onFixPermission: () -> Unit,
)

private val StatusIconSize = 24.dp

/** The Settings row subtitle and the status card title for a [TrackingState]. */
fun TrackingState.healthStatusRes(): Int = when (this) {
    TrackingState.TRACKING -> R.string.health_status_working
    TrackingState.IDLE -> R.string.health_status_off
    TrackingState.PAUSED -> R.string.health_status_paused
    TrackingState.ATTENTION -> R.string.health_status_attention
}

/**
 * Stateless Tracking health: a status card, the checks (each with an icon and a word, never colour alone; a not-ok one opens
 * its fix), why tracking can stop, and Test tracking with its result card.
 */
@Composable
fun TrackingHealthContent(
    state: TrackingHealthUiState,
    actions: TrackingHealthActions,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
        Text(
            stringResource(R.string.health_title),
            style = MaterialTheme.typography.headlineMedium,
            color = TarryTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        StatusCard(state)
        SectionHeader(stringResource(R.string.health_section_checks), Modifier.padding(top = space.m))
        ChecksGroup(state, actions)
        SectionHeader(stringResource(R.string.health_section_why), Modifier.padding(top = space.m))
        Text(
            stringResource(R.string.health_why_body),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
            modifier = Modifier.testTag("health_why"),
        )
        SectionHeader(stringResource(R.string.health_section_test), Modifier.padding(top = space.m))
        Text(
            stringResource(R.string.health_test_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = TarryTheme.colors.muted,
        )
        SecondaryButton(
            text = stringResource(if (state.testRunning) R.string.health_test_running else R.string.health_test_button),
            onClick = actions.onTestTracking,
            enabled = !state.testRunning,
            modifier = Modifier.fillMaxWidth().testTag("health_test_button"),
        )
        TestResult(state, actions)
    }
}

@Composable
private fun StatusCard(state: TrackingHealthUiState) {
    val colors = TarryTheme.colors
    val attention = state.trackingState == TrackingState.ATTENTION
    TarryCard(modifier = Modifier.fillMaxWidth().testTag("health_status")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
            Icon(
                painter = painterResource(if (attention) R.drawable.ic_warning else R.drawable.ic_check),
                contentDescription = null,
                tint = if (attention) colors.alert else colors.success,
                modifier = Modifier.size(StatusIconSize),
            )
            Text(
                stringResource(state.trackingState.healthStatusRes()),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
            )
        }
        Text(
            text = stringResource(
                when (state.trackingState) {
                    TrackingState.TRACKING -> R.string.health_status_working_body
                    TrackingState.IDLE -> R.string.health_status_off_body
                    TrackingState.PAUSED -> R.string.health_status_paused_body
                    // The same sentence the attention sheet shows (shared, not duplicated).
                    TrackingState.ATTENTION -> state.attentionCause.messageRes()
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            // Starts where the title text starts, after the icon.
            modifier = Modifier.padding(start = StatusIconSize + TarryTheme.space.s, top = TarryTheme.space.xs),
        )
        Text(
            text = if (state.lowBatterySaverActive) {
                stringResource(R.string.battery_health_row_low)
            } else {
                stringResource(R.string.battery_health_row, stringResource(state.powerMode.labelRes()))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier
                .padding(start = StatusIconSize + TarryTheme.space.s, top = TarryTheme.space.xs)
                .testTag("health_battery_use"),
        )
    }
}

@Composable
private fun ChecksGroup(state: TrackingHealthUiState, actions: TrackingHealthActions) {
    // Strings are read here: the group's row builder is not composable.
    val texts = state.checks.map { check ->
        val title = stringResource(check.id.titleRes())
        val word = stringResource(check.stateRes())
        val description = stringResource(
            if (check.ok) R.string.health_check_description_ok else R.string.health_check_description_fix, title, word,
        )
        Triple(title, word, description)
    }
    SettingsGroup(modifier = Modifier.testTag("health_checks")) {
        state.checks.forEachIndexed { index, check ->
            val (title, word, description) = texts[index]
            row(
                title = title,
                onClick = if (check.ok) null else ({ actions.onFix(check.id) }),
                icon = if (check.ok) R.drawable.ic_check else R.drawable.ic_warning,
                subtitle = word,
                contentDescription = description,
            )
        }
    }
}

@Composable
private fun TestResult(state: TrackingHealthUiState, actions: TrackingHealthActions) {
    val test = state.test
    if (test == TestFixState.Idle) return
    // The card appears below the button, which can be off screen at large text: scroll it into view as it changes.
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(test) { requester.bringIntoView() }
    TarryCard(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("health_test_result"),
    ) {
        val body = MaterialTheme.typography.bodyMedium
        val ink = TarryTheme.colors.ink
        Row(horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
            if (test != TestFixState.Running) {
                val ok = test is TestFixState.Success
                // The words carry the meaning; the icon repeats it.
                Icon(
                    painter = painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning),
                    contentDescription = null,
                    tint = if (ok) TarryTheme.colors.success else TarryTheme.colors.alert,
                    modifier = Modifier.size(StatusIconSize).testTag(if (ok) "health_test_icon_ok" else "health_test_icon_attention"),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                when (test) {
                    TestFixState.Idle -> Unit
                    TestFixState.Running -> Text(stringResource(R.string.health_test_running_body), style = body, color = ink)
                    is TestFixState.Success -> {
                        Text(
                            stringResource(R.string.health_test_success, test.accuracyM, test.ageSeconds),
                            style = MaterialTheme.typography.titleSmall,
                            color = ink,
                        )
                        Text(
                            text = if (test.blockedBy == null) {
                                stringResource(R.string.health_test_can_start)
                            } else {
                                stringResource(test.blockedBy.messageRes())
                            },
                            style = body,
                            color = TarryTheme.colors.muted,
                            modifier = Modifier.padding(top = TarryTheme.space.xs),
                        )
                    }
                    is TestFixState.Timeout -> Text(stringResource(R.string.health_test_timeout, test.seconds), style = body, color = ink)
                    TestFixState.Denied -> {
                        Text(stringResource(R.string.health_test_denied), style = body, color = ink)
                        SecondaryButton(
                            text = stringResource(R.string.health_test_denied_fix),
                            onClick = actions.onFixPermission,
                            modifier = Modifier.padding(top = TarryTheme.space.s).testTag("health_test_fix"),
                        )
                    }
                }
            }
        }
    }
}

private fun HealthCheckId.titleRes(): Int = when (this) {
    HealthCheckId.PRECISE_LOCATION -> PermissionStep.LOCATION.titleRes()
    HealthCheckId.BACKGROUND_LOCATION -> PermissionStep.BACKGROUND_LOCATION.titleRes()
    HealthCheckId.LOCATION_SERVICES -> R.string.health_check_location_services
    HealthCheckId.PHYSICAL_ACTIVITY -> PermissionStep.ACTIVITY_RECOGNITION.titleRes()
    HealthCheckId.NOTIFICATIONS -> PermissionStep.NOTIFICATIONS.titleRes()
    HealthCheckId.EXACT_ALARMS -> PermissionStep.EXACT_ALARMS.titleRes()
    HealthCheckId.BATTERY -> R.string.health_check_battery
}

/** The word that goes with the icon: permissions are Allowed, services On, battery Unrestricted. */
private fun HealthCheck.stateRes(): Int = when (id) {
    HealthCheckId.LOCATION_SERVICES -> if (ok) R.string.health_state_on else R.string.health_state_off
    HealthCheckId.BATTERY -> if (ok) R.string.health_state_unrestricted else R.string.health_state_optimised
    else -> if (ok) R.string.health_state_allowed else R.string.health_state_not_allowed
}
