package me.tarry.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.ui.theme.TarryTheme

private const val PlaceholderAlpha = 0.6f
private const val PlaceholderPulseLow = 0.35f

/** A short Nunito line plus one muted sentence on what will fill the screen. No illustration. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(TarryTheme.space.xxl),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = TarryTheme.colors.ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TarryTheme.colors.muted)
    }
}

/**
 * `card` placeholder blocks at 60% alpha. They pulse gently only when motion is allowed
 * (see [rememberReducedMotion]); otherwise they are static.
 */
@Composable
fun LoadingBlocks(
    modifier: Modifier = Modifier,
    count: Int = 3,
    blockHeight: Dp = 72.dp,
) {
    val description = stringResource(R.string.component_loading)
    val alpha: State<Float> = if (rememberReducedMotion()) {
        remember { mutableFloatStateOf(PlaceholderAlpha) }
    } else {
        rememberInfiniteTransition(label = "loading").animateFloat(
            initialValue = PlaceholderAlpha,
            targetValue = PlaceholderPulseLow,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
            label = "loadingAlpha",
        )
    }
    Column(
        modifier = modifier.semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        repeat(count) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(blockHeight)
                    .graphicsLayer { this.alpha = alpha.value }
                    .clip(TarryTheme.shapes.card)
                    .background(TarryTheme.colors.card),
            ) {}
        }
    }
}

/** `alertSoft` card: icon, the cause, and one action. */
@Composable
fun ErrorCard(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TarryTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TarryTheme.shapes.card)
            .background(c.alertSoft)
            .padding(TarryTheme.space.l),
        verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_error),
                contentDescription = null,
                tint = c.onAlertSoft,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = c.onAlertSoft,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = c.onAlertSoft,
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp)
                .clip(TarryTheme.shapes.pill)
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = TarryTheme.space.l, vertical = TarryTheme.space.m),
        )
    }
}

@TarryPreviews
@Composable
private fun StatesPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EmptyState("Nothing yet today", "Visits and trips appear here as Tarry records them.")
        LoadingBlocks(count = 2)
        ErrorCard("Could not load the day.", "Try again", {})
    }
}
