package me.tarry.app.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import me.tarry.app.R
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.components.TarryCard
import me.tarry.app.ui.theme.TarryTheme

/** The one near-end trial card on the Timeline: the day before the trial ends. Dismissing or unlocking ends it for good. */
@Composable
fun TrialEndCard(onUnlock: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    TarryCard(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s), verticalAlignment = Alignment.Top) {
            Text(
                stringResource(R.string.trial_card_title),
                style = MaterialTheme.typography.titleLarge,
                color = TarryTheme.colors.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconCircleButton(
                icon = R.drawable.ic_close,
                contentDescription = stringResource(R.string.trial_card_dismiss),
                onClick = onDismiss,
                containerColor = TarryTheme.colors.inset,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.m)) {
            Text(stringResource(R.string.trial_card_body), style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
            SecondaryButton(stringResource(R.string.trial_card_action), onUnlock)
        }
    }
}
