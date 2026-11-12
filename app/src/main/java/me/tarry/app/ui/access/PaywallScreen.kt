package me.tarry.app.ui.access

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.tarry.app.R
import me.tarry.app.access.AccessState
import me.tarry.app.access.OfferState
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.TarryTheme

/** Hilt-wired purchase screen. Full screen like onboarding; [onClose] returns to where the user came from. */
@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PaywallViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    PaywallContent(
        ui = ui,
        onUnlock = { activity?.let(viewModel::unlock) },
        onRestore = viewModel::restore,
        onRetry = viewModel::retry,
        onClose = onClose,
        modifier = modifier,
    )
}

/**
 * Stateless purchase screen: what Tarry costs, what it gives, and what stays open when locked. One ink button; the
 * rest is quiet. The scrolling part and the buttons are apart so the buttons stay in reach at large font sizes.
 */
@Composable
fun PaywallContent(
    ui: PaywallUi,
    onUnlock: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = TarryTheme.space
    val owned = ui.access == AccessState.Owned
    val pending = ui.access is AccessState.Pending
    val locked = ui.access == AccessState.TrialEnded || ui.access is AccessState.Revoked
    val offer = ui.offer
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        IconCircleButton(
            icon = R.drawable.ic_close,
            contentDescription = stringResource(R.string.paywall_close),
            onClick = onClose,
            modifier = Modifier.padding(horizontal = space.gutter, vertical = space.s),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = space.gutter, vertical = space.s),
            verticalArrangement = Arrangement.spacedBy(space.m),
        ) {
            Text(
                stringResource(R.string.paywall_title),
                style = MaterialTheme.typography.headlineMedium,
                color = TarryTheme.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.paywall_heading), style = MaterialTheme.typography.titleMedium, color = TarryTheme.colors.ink)
            statusLine(ui)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted) }
            if (owned) {
                Text(stringResource(R.string.paywall_owned), style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(space.s)) {
                    Tick(R.string.paywall_tick_tracking)
                    Tick(R.string.paywall_tick_jobs)
                    Tick(R.string.paywall_tick_backup)
                    Tick(R.string.paywall_tick_updates)
                }
                when {
                    pending -> Text(stringResource(R.string.paywall_pending), style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink)
                    offer is OfferState.Available -> Text(
                        stringResource(R.string.paywall_price_line, offer.formattedPrice),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TarryTheme.colors.ink,
                    )
                    offer is OfferState.Unavailable -> Text(
                        stringResource(R.string.paywall_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TarryTheme.colors.ink,
                    )
                    else -> Text(stringResource(R.string.paywall_price_loading), style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.muted)
                }
                Text(stringResource(R.string.paywall_privacy), style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
            }
            if (locked) {
                Text(stringResource(R.string.paywall_data_promise), style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = space.gutter, vertical = space.m),
            verticalArrangement = Arrangement.spacedBy(space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = when {
                ui.launchFailed -> stringResource(R.string.paywall_error)
                ui.restore == RestoreResult.Found -> stringResource(R.string.paywall_restore_found)
                ui.restore == RestoreResult.NotFound && !owned -> stringResource(R.string.paywall_restore_not_found)
                else -> null
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.ink) }
            when {
                owned -> PrimaryButton(stringResource(R.string.paywall_done), onClose, Modifier.fillMaxWidth())
                pending -> Unit
                offer is OfferState.Available -> PrimaryButton(
                    stringResource(R.string.paywall_unlock_for, offer.formattedPrice),
                    onUnlock,
                    Modifier.fillMaxWidth(),
                )
                else -> PrimaryButton(stringResource(R.string.paywall_unlock_plain), onUnlock, Modifier.fillMaxWidth(), enabled = false)
            }
            if (!owned && !pending && offer is OfferState.Unavailable) {
                SecondaryButton(stringResource(R.string.paywall_try_again), onRetry, Modifier.fillMaxWidth())
            }
            if (!owned) TertiaryButton(stringResource(R.string.paywall_restore), onRestore, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun statusLine(ui: PaywallUi): String? = when (ui.access) {
    is AccessState.Trial -> ui.daysLeft?.let { pluralStringResource(R.plurals.access_trial_days_left, it, it) }
    AccessState.TrialEnded -> stringResource(R.string.paywall_status_trial_ended)
    is AccessState.Revoked -> stringResource(R.string.paywall_status_revoked)
    else -> null
}

/** A decorative tick: the text carries the meaning. */
@Composable
private fun Tick(@StringRes textRes: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m), verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = TarryTheme.colors.ink,
            modifier = Modifier.size(24.dp),
        )
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink, modifier = Modifier.weight(1f))
    }
}
