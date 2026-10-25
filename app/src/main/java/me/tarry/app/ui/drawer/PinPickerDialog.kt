package me.tarry.app.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.tarry.app.R
import me.tarry.app.map.GeoPoint
import me.tarry.app.map.MapConfig
import me.tarry.app.map.PickPlace
import me.tarry.app.map.PinPickerConfig
import me.tarry.app.map.PlacePick
import me.tarry.app.map.circlePolygon
import me.tarry.app.map.formatPinCoordinates
import me.tarry.app.map.nearbyPlaces
import me.tarry.app.map.startCentre
import me.tarry.app.ui.components.IconCircleButton
import me.tarry.app.ui.components.SectionHeader
import me.tarry.app.ui.components.SettingsGroup
import me.tarry.app.ui.map.RadiusFillAlpha
import me.tarry.app.ui.map.TarryMap
import me.tarry.app.ui.map.mapColor
import me.tarry.app.ui.map.setDots
import me.tarry.app.ui.map.toLatLng
import me.tarry.app.ui.map.toPoint
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.timeline.formatDistance
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Polygon

private val PinSize = 48.dp

/** Where the glyph's tip sits, as a fraction of its height (the `ic_location_pin` path ends at 837 of 960). */
private const val PinTipFraction = 0.87f
private val ListMaxHeight = 280.dp
private val MapMinHeight = 200.dp
private const val PlaceSource = "pk-places"

/**
 * Full-screen "Pick on map": the user pans the map under a fixed centre pin (no tiny marker to drag), then taps either
 * "New place here" or a known place within snapping distance of the pin. Every choice is a 64 dp row, so the map is
 * never the only way in (the drawer's place search stays) and the list scrolls at large font scales.
 */
@Composable
fun PinPickerDialog(
    places: List<PlaceOption>,
    chosenCentre: GeoPoint?,
    onPick: (PlacePick) -> Unit,
    onDismiss: () -> Unit,
    config: PinPickerConfig = PinPickerConfig(),
) {
    val pickPlaces = remember(places) {
        places.mapNotNull { p -> p.centre?.let { PickPlace(p.id, p.label, it, p.lastVisitAt, p.radiusM) } }
    }
    val start = remember { startCentre(chosenCentre, pickPlaces) }
    var centre by remember { mutableStateOf(start ?: config.fallbackCentre) }
    var loaded by remember { mutableStateOf<Style?>(null) }
    val colors = TarryTheme.colors
    val currentPlaces by rememberUpdatedState(pickPlaces)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.paper)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TarryTheme.space.gutter, vertical = TarryTheme.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TarryTheme.space.m),
            ) {
                IconCircleButton(
                    icon = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.edit_pin_close),
                    onClick = onDismiss,
                )
                Text(
                    stringResource(R.string.edit_pin_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Box(Modifier.weight(1f).heightIn(min = MapMinHeight).fillMaxWidth()) {
                // TarryMap draws a placeholder itself when LocalMapsEnabled is false (JVM tests).
                TarryMap(
                    contentDescription = stringResource(R.string.edit_pin_map_description),
                    modifier = Modifier.fillMaxSize(),
                    onStyleLoaded = { map, style ->
                        if (loaded == null) {
                            val at = start ?: config.fallbackCentre
                            map.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(at.toLatLng(), if (start != null) config.startZoom else config.fallbackZoom),
                            )
                            map.addOnCameraIdleListener { map.cameraPosition.target?.let { centre = GeoPoint(it.latitude, it.longitude) } }
                        }
                        loaded = style
                    },
                )
                Icon(
                    painter = painterResource(R.drawable.ic_location_pin),
                    contentDescription = stringResource(R.string.edit_pin_marker_description),
                    tint = colors.ink,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = -PinSize * (PinTipFraction - 0.5f))
                        .size(PinSize)
                        .testTag("pin_picker_marker"),
                )
            }
            val style = loaded
            val fill = colors.accent.copy(alpha = RadiusFillAlpha).toArgb()
            val outline = colors.accentStrong.toArgb()
            val dot = colors.accentStrong.toArgb()
            val stroke = colors.paper.toArgb()
            LaunchedEffect(style, pickPlaces, colors) {
                if (style != null && style.isFullyLoaded) style.showPlaces(currentPlaces, fill, outline, dot, stroke)
            }
            val nearby = nearbyPlaces(centre, pickPlaces, config.snapM)
            Column(
                Modifier
                    .heightIn(max = ListMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TarryTheme.space.gutter, vertical = TarryTheme.space.s),
                verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s),
            ) {
                val newHere = stringResource(R.string.edit_pin_new_here)
                SettingsGroup {
                    row(
                        title = newHere,
                        subtitle = formatPinCoordinates(centre.lat, centre.lng),
                        icon = R.drawable.ic_plus,
                        onClick = { onPick(PlacePick.New(centre.lat, centre.lng)) },
                    )
                }
                SectionHeader(stringResource(R.string.edit_pin_nearby_header), Modifier.semantics { heading() })
                if (nearby.isEmpty()) {
                    Text(
                        stringResource(R.string.edit_pin_nearby_none, formatDistance(config.snapM.toFloat())),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                } else {
                    val rows = nearby.map { it.place.label to stringResource(R.string.edit_pin_nearby_distance, formatDistance(it.distanceM.toFloat())) }
                    SettingsGroup {
                        nearby.forEachIndexed { i, n ->
                            row(
                                title = rows[i].first,
                                subtitle = rows[i].second,
                                icon = R.drawable.ic_location_pin,
                                onClick = { onPick(PlacePick.Known(n.place.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Known places as dots and radius circles (one source each, replaced on every call). */
private fun Style.showPlaces(places: List<PickPlace>, fillArgb: Int, outlineArgb: Int, dotArgb: Int, strokeArgb: Int) {
    val rings = places.map { p ->
        Feature.fromGeometry(Polygon.fromLngLats(listOf(circlePolygon(p.centre, p.radiusM.toDouble(), MapConfig().circleSegments).map { it.toPoint() })))
    }
    val source = getSourceAs<GeoJsonSource>(PlaceSource) ?: GeoJsonSource(PlaceSource).also { addSource(it) }
    source.setGeoJson(FeatureCollection.fromFeatures(rings))
    if (getLayer("$PlaceSource-fill") == null) {
        addLayer(FillLayer("$PlaceSource-fill", PlaceSource).withProperties(PropertyFactory.fillColor(mapColor(fillArgb))))
        addLayer(
            LineLayer("$PlaceSource-line", PlaceSource).withProperties(
                PropertyFactory.lineColor(mapColor(outlineArgb)),
                PropertyFactory.lineWidth(1.5f),
            ),
        )
    }
    setDots("pk-centres", places.map { it.centre }, dotArgb, strokeArgb)
}
