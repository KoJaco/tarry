package me.tarry.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.permissions.BackgroundDisclosureDialog
import me.tarry.app.ui.permissions.PermissionStep
import me.tarry.app.ui.permissions.PermissionsActions
import me.tarry.app.ui.permissions.PermissionsUiState
import me.tarry.app.ui.permissions.bodyRes
import me.tarry.app.ui.permissions.degradedRes
import me.tarry.app.ui.permissions.iconRes
import me.tarry.app.ui.permissions.titleRes
import me.tarry.app.ui.schedule.ScheduleGrid
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.timeline.keepWhole
import me.tarry.app.ui.timeline.rememberTimeFormats
import me.tarry.app.ui.timeline.weekdayLabel
import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** What onboarding can ask for. The stateful wrapper wires these to the ViewModel and the shell's navigation. */
data class OnboardingActions(
    val onNext: () -> Unit,
    val onBack: () -> Unit,
    val onToggleCell: (DayOfWeek, Int) -> Unit,
    val onStroke: (Schedule) -> Unit,
    val onSaveHours: () -> Unit,
    /** "Not now" on the location page: finish onboarding without the permission. */
    val onNotNow: () -> Unit,
    /** "Open Tarry" on the Done page. */
    val onOpenTarry: () -> Unit,
)

/** Height of the schedule grid on its page: the grid needs a bounded height, and the page scrolls around it. */
private val ScheduleGridHeight = 340.dp
private val LogoSize = 64.dp
private val PermissionIconSize = 32.dp

/**
 * Stateless onboarding: one page at a time, a step indicator on top, and the page's buttons pinned below the scrolling
 * content so they stay reachable at any font size. Back goes to the previous page (the first page leaves Back to the system).
 */
@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    permissions: PermissionsUiState,
    permissionActions: PermissionsActions,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    BackHandler(enabled = state.canGoBack, onBack = actions.onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TarryTheme.colors.paper)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = space.gutter, vertical = space.l),
            verticalArrangement = Arrangement.spacedBy(space.m),
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_indicator, state.index + 1, state.pages.size),
                style = MaterialTheme.typography.labelMedium,
                color = TarryTheme.colors.muted,
                modifier = Modifier.testTag("onboarding_step"),
            )
            when (val page = state.page) {
                OnboardingPage.Welcome -> WelcomeBody()
                is OnboardingPage.Permission -> PermissionBody(page.step)
                OnboardingPage.Schedule -> ScheduleBody(state, actions)
                OnboardingPage.Done -> DoneBody(state.outcome)
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = space.gutter).padding(bottom = space.l, top = space.s),
            verticalArrangement = Arrangement.spacedBy(space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PageButtons(state, permissionActions, actions)
        }
    }
    if (permissions.showBackgroundDisclosure) {
        BackgroundDisclosureDialog(permissionActions.onConfirmBackgroundDisclosure, permissionActions.onDismissBackgroundDisclosure)
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = TarryTheme.colors.ink,
        modifier = Modifier.semantics { heading() }.testTag("onboarding_title"),
    )
}

@Composable
private fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted, modifier = modifier)
}

@Composable
private fun WelcomeBody() {
    // Decorative: the headline below names what Tarry does.
    Image(
        painter = painterResource(R.drawable.tarry_logo),
        contentDescription = null,
        modifier = Modifier.size(LogoSize).testTag("onboarding_logo"),
    )
    PageTitle(stringResource(R.string.onboarding_welcome_headline))
    BodyText(stringResource(R.string.onboarding_welcome_body))
    Text(
        text = stringResource(R.string.onboarding_welcome_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = TarryTheme.colors.ink,
        modifier = Modifier.testTag("onboarding_privacy"),
    )
}

@Composable
private fun PermissionBody(step: PermissionStep) {
    Icon(
        painter = painterResource(step.iconRes()),
        contentDescription = null,
        tint = TarryTheme.colors.ink,
        modifier = Modifier.size(PermissionIconSize),
    )
    PageTitle(stringResource(step.titleRes()))
    BodyText(stringResource(step.bodyRes()))
    // Say what is lost if declined (ui-rules.md section 2).
    BodyText(stringResource(R.string.onboarding_permission_skip_lost, stringResource(step.degradedRes())))
}

@Composable
private fun ScheduleBody(state: OnboardingUiState, actions: OnboardingActions) {
    PageTitle(stringResource(R.string.onboarding_schedule_title))
    BodyText(stringResource(R.string.onboarding_schedule_body))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScheduleGridHeight)
            .clip(TarryTheme.shapes.card)
            .background(TarryTheme.colors.card)
            .testTag("onboarding_grid"),
    ) {
        ScheduleGrid(
            schedule = state.schedule,
            onToggle = actions.onToggleCell,
            onStroke = actions.onStroke,
            onDayHeaderClick = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun DoneBody(outcome: DoneOutcome?) {
    val title = when (outcome) {
        DoneOutcome.TrackingNow -> stringResource(R.string.onboarding_done_tracking_now)
        is DoneOutcome.Next -> stringResource(R.string.onboarding_done_next, nextWindowText(outcome))
        else -> stringResource(R.string.onboarding_done_no_hours)
    }
    PageTitle(title)
    val noHours = outcome == null || outcome == DoneOutcome.NoHours
    BodyText(stringResource(if (noHours) R.string.onboarding_done_no_hours_body else R.string.onboarding_done_body))
}

/** "Mon 7:00 am": the weekday and time of the next window, in the user's language and 12/24-hour setting. */
@Composable
private fun nextWindowText(next: DoneOutcome.Next): String {
    val zone = ZoneId.of(next.zoneId)
    val formats = rememberTimeFormats()
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    return nextWindowLabel(next.startMs, zone, locale) { formats.time(next.startMs, zone) }
}

internal fun nextWindowLabel(startMs: Long, zone: ZoneId, locale: Locale, time: () -> String): String {
    val day = Instant.ofEpochMilli(startMs).atZone(zone).dayOfWeek
    return "${weekdayLabel(day, locale, narrow = false)} ${time()}".keepWhole()
}

@Composable
private fun PageButtons(state: OnboardingUiState, permissionActions: PermissionsActions, actions: OnboardingActions) {
    when (val page = state.page) {
        OnboardingPage.Welcome -> PrimaryAction(R.string.onboarding_get_started, actions.onNext)
        is OnboardingPage.Permission -> {
            val step = page.step
            val intentStep = step == PermissionStep.EXACT_ALARMS || step == PermissionStep.BATTERY_OPTIMISATION
            PrimaryAction(
                if (intentStep) R.string.onboarding_permission_open_settings else R.string.onboarding_permission_allow,
                { permissionActions.onGrant(step) },
            )
            val name = stringResource(step.titleRes())
            if (step.skippable) {
                val skip = stringResource(R.string.permissions_action_skip_description, name)
                TertiaryButton(
                    text = stringResource(R.string.permissions_action_skip),
                    onClick = actions.onNext,
                    modifier = Modifier.semantics { contentDescription = skip }.testTag("onboarding_skip"),
                )
            } else {
                val notNow = stringResource(R.string.onboarding_permission_not_now_description, name)
                TertiaryButton(
                    text = stringResource(R.string.onboarding_permission_not_now),
                    onClick = actions.onNotNow,
                    modifier = Modifier.semantics { contentDescription = notNow }.testTag("onboarding_not_now"),
                )
            }
        }
        OnboardingPage.Schedule -> {
            // Beside the button, not under the tall grid, so a failed save is never scrolled out of view.
            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.onboarding_schedule_save_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TarryTheme.colors.alert,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("onboarding_save_failed"),
                )
            }
            PrimaryAction(R.string.onboarding_schedule_save, actions.onSaveHours, enabled = !state.saving)
        }
        OnboardingPage.Done -> PrimaryAction(R.string.onboarding_done_open, actions.onOpenTarry)
    }
}

@Composable
private fun PrimaryAction(textRes: Int, onClick: () -> Unit, enabled: Boolean = true) {
    PrimaryButton(
        text = stringResource(textRes),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("onboarding_primary"),
    )
}
