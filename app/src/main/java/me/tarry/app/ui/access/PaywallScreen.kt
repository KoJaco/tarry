package me.tarry.app.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.tarry.app.R
import me.tarry.app.ui.components.SecondaryButton
import me.tarry.app.ui.theme.TarryTheme

/**
 * Placeholder for the purchase screen (8b.1): says what is locked and what stays open. 8b.2 replaces it with the real
 * purchase. Full screen like onboarding; [onClose] returns to where the user came from.
 */
@Composable
fun PaywallScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val space = TarryTheme.space
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = space.gutter, vertical = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
        Text(stringResource(R.string.paywall_title), style = MaterialTheme.typography.headlineMedium, color = TarryTheme.colors.ink)
        Text(stringResource(R.string.paywall_placeholder), style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
        Text(stringResource(R.string.paywall_data_promise), style = MaterialTheme.typography.bodyLarge, color = TarryTheme.colors.ink)
        SecondaryButton(text = stringResource(R.string.paywall_close), onClick = onClose)
    }
}
