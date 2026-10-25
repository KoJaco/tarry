package me.tarry.app.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
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

/**
 * The ring's current scale as a lazy reader, so tests can assert it does not animate under reduced motion. It is a
 * lambda so that publishing it does not read the animation value during composition.
 */
internal val PulseRingScale = SemanticsPropertyKey<() -> Float>("PulseRingScale")
private var SemanticsPropertyReceiver.pulseRingScale by PulseRingScale

private const val PulseMaxScale = 1.35f
private const val PulseStaticScale = 1.15f
private const val PulseDurationMs = 1600

/**
 * Tracking toggle pill in one of four states, each different in shape, glyph and label as well as
 * colour. Tapping calls [onClick]; long-pressing (with haptics) calls [onLongClick].
 *
 * Labels follow tokens.md. The spoken description would append the time left on a pause ("· 45 m"),
 * but [TrackingState] carries no pause end yet; add it once that is plumbed through.
 */
@Composable
fun TrackingToggle(
    state: TrackingState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TarryTheme.colors
    val shape = TarryTheme.shapes.pill
    val stateLabel = stringResource(state.labelRes())
    val description = stringResource(state.descriptionRes())
    val longClickLabel = stringResource(R.string.tracking_toggle_long_click_label)
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
                    onLongClickLabel = longClickLabel,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .padding(horizontal = if (state == TrackingState.IDLE) TarryTheme.space.l else TarryTheme.space.s, vertical = TarryTheme.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s, Alignment.CenterHorizontally),
        ) {
            StateMark(state)
            FitLabel(
                text = stateLabel,
                color = content,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Smallest fraction of the label style [FitLabel] will shrink to while looking for a size where no word splits. */
private const val MinLabelFactor = 0.4f
private const val LabelShrinkStep = 0.9f

/**
 * The toggle label. It may wrap, but only between words: if the slot is too narrow for the longest word at the
 * current size (large font scales in a one-third slot), the text shrinks a step at a time until no line ends
 * mid-word, instead of splitting the word.
 */
@Composable
private fun FitLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelLarge
    val fontScale = LocalDensity.current.fontScale
    var factor by remember(text, fontScale) { mutableFloatStateOf(1f) }
    Text(
        text = text,
        style = style.copy(fontSize = style.fontSize * factor, lineHeight = style.lineHeight * factor),
        color = color,
        textAlign = TextAlign.Center,
        onTextLayout = { layout ->
            if (layout.splitsAWord(text) && factor > MinLabelFactor) {
                factor = maxOf(MinLabelFactor, factor * LabelShrinkStep)
            }
        },
        modifier = modifier,
    )
}

/** True when a line other than the last ends in the middle of a word. */
private fun TextLayoutResult.splitsAWord(text: String): Boolean =
    (0 until lineCount - 1).any { line ->
        val end = getLineEnd(line)
        end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
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

/**
 * The animation value is read only inside `graphicsLayer {}` and the semantics lambda, so a frame invalidates the
 * draw layer and never recomposes this function.
 */
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
    Box(
        modifier = modifier
            .testTag(PulseRingTag)
            .semantics {
                pulseRingScale = { if (reduced) PulseStaticScale else 1f + (PulseMaxScale - 1f) * progress.value }
            }
            .graphicsLayer {
                val scale = if (reduced) PulseStaticScale else 1f + (PulseMaxScale - 1f) * progress.value
                scaleX = scale
                scaleY = scale
                alpha = if (reduced) 1f else 1f - progress.value
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
        TrackingState.entries.forEach { TrackingToggle(state = it, onClick = {}, onLongClick = {}) }
    }
}
