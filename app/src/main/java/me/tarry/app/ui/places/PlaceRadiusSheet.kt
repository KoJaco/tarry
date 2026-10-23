package me.tarry.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import me.tarry.app.R
import me.tarry.app.segmentation.RadiusCounts
import me.tarry.app.segmentation.RadiusPreviewInput
import me.tarry.app.segmentation.radiusCounts
import me.tarry.app.ui.catalog.SheetFrame
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.PrimaryButton
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.core.data.repository.PlaceLimits
import kotlin.math.roundToInt

/** Below this radius a long stay can fall out of the circle and split. */
private const val SMALL_RADIUS_HINT_M = 60f
private val SliderSteps = ((PlaceLimits.RADIUS_MAX_M - PlaceLimits.RADIUS_MIN_M) / PlaceLimits.RADIUS_STEP_M).roundToInt() - 1

/**
 * Radius editor: a slider with -/+ buttons (a non-drag way to change it, WCAG 2.5.7) and live counts of what the radius
 * would do to detected history. [input] is null while it loads. Nothing is written until Save; Cancel discards.
 */
@Composable
fun PlaceRadiusSheet(
    initialRadiusM: Float,
    input: RadiusPreviewInput?,
    onSave: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var radius by rememberSaveable { mutableFloatStateOf(initialRadiusM) }
    val counts = remember(input, radius) { input?.let { radiusCounts(it, radius) } }
    val colors = TarryTheme.colors
    val valueText = stringResource(R.string.place_radius_value, radius.roundToInt())
    val sliderLabel = stringResource(R.string.place_radius_slider_label)
    val sliderState = stringResource(R.string.place_radius_state, radius.roundToInt())
    SheetFrame(stringResource(R.string.place_radius_title), onDismiss) {
        Text(valueText, style = MaterialTheme.typography.headlineMedium, color = colors.ink)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
        ) {
            IconCircleButton(
                icon = R.drawable.ic_minus,
                contentDescription = stringResource(R.string.place_radius_decrease),
                onClick = { radius = (radius - PlaceLimits.RADIUS_STEP_M).coerceAtLeast(PlaceLimits.RADIUS_MIN_M) },
                enabled = radius > PlaceLimits.RADIUS_MIN_M,
            )
            Slider(
                value = radius,
                onValueChange = { radius = snap(it) },
                valueRange = PlaceLimits.RADIUS_MIN_M..PlaceLimits.RADIUS_MAX_M,
                steps = SliderSteps,
                colors = SliderDefaults.colors(
                    thumbColor = colors.ink,
                    activeTrackColor = colors.ink,
                    inactiveTrackColor = colors.outline,
                    activeTickColor = colors.onInk,
                    inactiveTickColor = colors.paper,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = sliderLabel
                        stateDescription = sliderState
                    },
            )
            IconCircleButton(
                icon = R.drawable.ic_plus,
                contentDescription = stringResource(R.string.place_radius_increase),
                onClick = { radius = (radius + PlaceLimits.RADIUS_STEP_M).coerceAtMost(PlaceLimits.RADIUS_MAX_M) },
                enabled = radius < PlaceLimits.RADIUS_MAX_M,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
            if (counts == null) {
                Text(stringResource(R.string.place_radius_loading), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            } else {
                CountLines(counts)
            }
            if (radius < SMALL_RADIUS_HINT_M) {
                Text(stringResource(R.string.place_radius_small_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onAlertSoft)
            }
            Text(stringResource(R.string.place_radius_defaults_note), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        PrimaryButton(stringResource(R.string.place_radius_save), onClick = { onSave(radius) }, modifier = Modifier.fillMaxWidth())
        TertiaryButton(stringResource(R.string.place_radius_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CountLines(counts: RadiusCounts) {
    val style = MaterialTheme.typography.bodyMedium
    val ink = TarryTheme.colors.ink
    Text(pluralStringResource(R.plurals.place_radius_stay, counts.stay, counts.stay), style = style, color = ink)
    Text(pluralStringResource(R.plurals.place_radius_leave, counts.leave, counts.leave), style = style, color = ink)
    if (counts.join > 0) Text(pluralStringResource(R.plurals.place_radius_join, counts.join, counts.join), style = style, color = ink)
    if (counts.frozen > 0) Text(pluralStringResource(R.plurals.place_radius_frozen, counts.frozen, counts.frozen), style = style, color = ink)
}

private fun snap(v: Float): Float =
    ((v / PlaceLimits.RADIUS_STEP_M).roundToInt() * PlaceLimits.RADIUS_STEP_M).coerceIn(PlaceLimits.RADIUS_MIN_M, PlaceLimits.RADIUS_MAX_M)
