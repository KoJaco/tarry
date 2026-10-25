package me.tarry.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.tarry.app.map.GeoPoint
import me.tarry.app.segmentation.radiusDots
import me.tarry.app.ui.map.RadiusFillAlpha
import me.tarry.app.ui.map.TarryMap
import me.tarry.app.ui.map.radiusBounds
import me.tarry.app.ui.map.setDots
import me.tarry.app.ui.map.setRadiusCircle
import me.tarry.app.ui.map.toLatLngBounds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
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
    val sliderState = pluralStringResource(R.plurals.place_radius_state, radius.roundToInt(), radius.roundToInt())
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
        if (input != null) RadiusMapPreview(input, radius, counts)
        Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.xs)) {
            if (counts == null) {
                Text(stringResource(R.string.place_radius_loading), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            } else {
                CountLines(counts)
            }
            if (radius < SMALL_RADIUS_HINT_M) {
                Text(
                    stringResource(R.string.place_radius_small_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onAlertSoft,
                    modifier = Modifier
                        .background(colors.alertSoft, TarryTheme.shapes.small)
                        .padding(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.xs),
                )
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

private val MapHeight = 200.dp
private val MapFitPadding = 16.dp
private const val MAP_PREVIEW_ID = "radius"

/**
 * A non-interactive map under the slider: the radius circle follows [radius] live (data update only, the style is not
 * reloaded) and the camera fits the largest allowed radius once per centre so it never jumps while sliding. Dark dots
 * are visits that would be this place's at this radius, muted dots are not, hollow dots are edited visits (frozen).
 */
@Composable
private fun RadiusMapPreview(input: RadiusPreviewInput, radius: Float, counts: RadiusCounts?) {
    val colors = TarryTheme.colors
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val centre = remember(input.centre) { GeoPoint(input.centre.lat, input.centre.lng) }
    val dots = remember(input, radius) { radiusDots(input, radius) }
    val fitPadding = with(LocalDensity.current) { MapFitPadding.roundToPx() }
    val description = if (counts == null) {
        stringResource(R.string.place_radius_map_description_loading, radius.roundToInt())
    } else {
        val inside = counts.stay + counts.join
        pluralStringResource(R.plurals.place_radius_map_description, inside, radius.roundToInt(), inside)
    }
    LaunchedEffect(map, centre) {
        map?.moveCamera(
            CameraUpdateFactory.newLatLngBounds(radiusBounds(centre, PlaceLimits.RADIUS_MAX_M.toDouble()).toLatLngBounds(), fitPadding),
        )
    }
    LaunchedEffect(style, centre, radius, dots, colors) {
        val s = style ?: return@LaunchedEffect
        // A theme switch replaces the style; never touch one that is not (or no longer) loaded.
        if (!s.isFullyLoaded) return@LaunchedEffect
        s.setRadiusCircle(MAP_PREVIEW_ID, centre, radius.toDouble(), colors.accent.copy(alpha = RadiusFillAlpha).toArgb(), colors.accentStrong.toArgb())
        s.setDots("$MAP_PREVIEW_ID-out", dots.outside, colors.muted.toArgb(), colors.paper.toArgb())
        s.setDots("$MAP_PREVIEW_ID-frozen", dots.frozen, colors.paper.toArgb(), colors.ink.toArgb())
        s.setDots("$MAP_PREVIEW_ID-in", dots.inside, colors.ink.toArgb(), colors.paper.toArgb())
    }
    TarryMap(
        contentDescription = description,
        modifier = Modifier
            .fillMaxWidth()
            .height(MapHeight)
            .clip(TarryTheme.shapes.small),
        interactive = false,
        onStyleLoaded = { m, st ->
            map = m
            style = st
        },
    )
}

private fun snap(v: Float): Float =
    ((v / PlaceLimits.RADIUS_STEP_M).roundToInt() * PlaceLimits.RADIUS_STEP_M).coerceIn(PlaceLimits.RADIUS_MIN_M, PlaceLimits.RADIUS_MAX_M)
