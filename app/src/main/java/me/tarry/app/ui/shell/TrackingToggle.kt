package me.tarry.app.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.theme.TarryTheme

private val ToggleSize = 64.dp
private val BadgeSize = 14.dp

/** Large circular tracking toggle. Renders [state]; tapping calls [onClick]. */
@Composable
fun TrackingToggle(
    state: TrackingState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val tarry = TarryTheme.colors
    val stateLabel = stringResource(state.labelRes())
    val description = stringResource(R.string.tracking_toggle_description, stateLabel)

    val container: Color
    val content: Color
    when (state) {
        TrackingState.TRACKING -> {
            container = colors.primary
            content = colors.onPrimary
        }
        TrackingState.PAUSED -> {
            container = tarry.inset
            content = tarry.accentStrong
        }
        TrackingState.IDLE, TrackingState.ATTENTION -> {
            container = colors.surface
            content = colors.onSurface
        }
    }
    val outline = state == TrackingState.IDLE || state == TrackingState.ATTENTION

    // The pulse ring may be clipped by the bar Surface until phase 3 styling.
    Box(modifier = modifier.size(ToggleSize), contentAlignment = Alignment.Center) {
        if (state == TrackingState.TRACKING) {
            PulseRing(color = colors.primary)
        }
        Box(
            modifier = Modifier
                .size(ToggleSize)
                .clip(CircleShape)
                .background(container)
                .then(
                    if (outline) Modifier.border(BorderStroke(2.dp, colors.outline), CircleShape)
                    else Modifier
                )
                .semantics { contentDescription = description }
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_location_pin),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(32.dp),
            )
        }
        if (state == TrackingState.ATTENTION) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(BadgeSize)
                    .background(colors.error, CircleShape)
                    .border(2.dp, colors.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun PulseRing(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseProgress",
    )
    Box(
        modifier = Modifier
            .size(ToggleSize)
            .scale(1f + 0.5f * progress.value)
            .alpha(0.5f * (1f - progress.value))
            .border(3.dp, color, CircleShape),
    )
}

private fun TrackingState.labelRes(): Int = when (this) {
    TrackingState.TRACKING -> R.string.tracking_state_tracking
    TrackingState.IDLE -> R.string.tracking_state_idle
    TrackingState.PAUSED -> R.string.tracking_state_paused
    TrackingState.ATTENTION -> R.string.tracking_state_attention
}
