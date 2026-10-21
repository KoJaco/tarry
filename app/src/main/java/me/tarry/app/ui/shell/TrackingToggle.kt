package me.tarry.app.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.components.PreviewSurface
import me.tarry.app.ui.components.TarryPreviews
import me.tarry.app.ui.components.rememberReducedMotion
import me.tarry.app.ui.theme.TarryTheme

/** Test tag of the pulse ring (present only while TRACKING). */
const val PulseRingTag = "trackingPulseRing"

/** The ring's current scale, exposed so tests can assert it does not animate under reduced motion. */
internal val PulseRingScale = SemanticsPropertyKey<Float>("PulseRingScale")
private var SemanticsPropertyReceiver.pulseRingScale by PulseRingScale

private const val PulseMaxScale = 1.35f
private const val PulseStaticScale = 1.15f
private const val PulseDurationMs = 1600

/**
 * Tracking toggle pill in one of four states, each different in shape, glyph and label as well as
 * colour. Tapping or long-pressing (with haptics) calls [onClick].
 *
 * Labels follow tokens.md. The spoken description would append the time left on a pause ("· 45 m"),
 * but [TrackingState] carries no pause end yet; add it once that is plumbed through.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackingToggle(
    state: TrackingState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TarryTheme.colors
    val shape = TarryTheme.shapes.pill
    val stateLabel = stringResource(state.labelRes())
    val description = stringResource(state.descriptionRes())
    val haptics = LocalHapticFeedback.current

    val container: Color
    val content: Color
    when (state) {
        TrackingState.TRACKING -> {
            container = colors.accent
            content = colors.onAccent
        }
        TrackingState.IDLE -> {
            container = Color.Transparent
            content = colors.ink
        }
        TrackingState.PAUSED -> {
            container = colors.inset
            content = colors.ink
        }
        TrackingState.ATTENTION -> {
            container = colors.ink
            content = colors.onInk
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (state == TrackingState.TRACKING) {
            PulseRing(color = colors.pulse, modifier = Modifier.matchParentSize())
        }
        Row(
            modifier = Modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(container)
                .then(if (state == TrackingState.IDLE) Modifier.border(1.5.dp, colors.ink, shape) else Modifier)
                .semantics { contentDescription = description }
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                )
                .padding(horizontal = TarryTheme.space.m, vertical = TarryTheme.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s, Alignment.CenterHorizontally),
        ) {
            StateMark(state)
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Dot or glyph that differs per state: filled dot, hollow dot, pause glyph, alert dot. */
@Composable
private fun StateMark(state: TrackingState) {
    val colors = TarryTheme.colors
    when (state) {
        TrackingState.TRACKING ->
            Box(Modifier.size(10.dp).background(colors.onAccent, CircleShape))
        TrackingState.IDLE ->
            Box(Modifier.size(10.dp).border(2.dp, colors.ink, CircleShape))
        TrackingState.PAUSED ->
            Icon(
                painter = painterResource(R.drawable.ic_pause),
                contentDescription = null,
                tint = colors.accentStrong,
                modifier = Modifier.size(18.dp),
            )
        TrackingState.ATTENTION ->
            Box(Modifier.size(12.dp).background(colors.alertDot, CircleShape))
    }
}

@Composable
private fun PulseRing(color: Color, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val progress: State<Float> = if (reduced) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = PulseDurationMs, easing = LinearEasing), RepeatMode.Restart),
            label = "pulseProgress",
        )
    }
    val scale = if (reduced) PulseStaticScale else 1f + (PulseMaxScale - 1f) * progress.value
    val alpha = if (reduced) 1f else 1f - progress.value
    Box(
        modifier = modifier
            .testTag(PulseRingTag)
            .semantics { pulseRingScale = scale }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .border(3.dp, color, TarryTheme.shapes.pill),
    )
}

private fun TrackingState.descriptionRes(): Int = when (this) {
    TrackingState.TRACKING -> R.string.tracking_toggle_description_tracking
    TrackingState.IDLE -> R.string.tracking_toggle_description_idle
    TrackingState.PAUSED -> R.string.tracking_toggle_description_paused
    TrackingState.ATTENTION -> R.string.tracking_toggle_description_attention
}

private fun TrackingState.labelRes(): Int = when (this) {
    TrackingState.TRACKING -> R.string.tracking_state_tracking
    TrackingState.IDLE -> R.string.tracking_state_idle
    TrackingState.PAUSED -> R.string.tracking_state_paused
    TrackingState.ATTENTION -> R.string.tracking_state_attention
}

@TarryPreviews
@Composable
private fun TrackingTogglePreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(16.dp)) {
        TrackingState.entries.forEach { TrackingToggle(state = it, onClick = {}) }
    }
}
