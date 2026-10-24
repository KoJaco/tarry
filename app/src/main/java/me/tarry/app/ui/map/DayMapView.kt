package me.tarry.app.ui.map

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.map.DayMap
import me.tarry.app.map.GeoBounds
import me.tarry.app.map.GeoPoint
import me.tarry.app.map.MapConfig
import me.tarry.app.map.VisitPin
import me.tarry.app.ui.theme.JobPalette
import me.tarry.app.ui.theme.TarryColors
import me.tarry.app.ui.theme.TarryTheme
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import java.time.LocalDate

private const val RouteId = "day-route"
private const val PinsId = "day-pins"
private const val MarkerId = "day-marker"
private const val PinCircleLayer = "$PinsId-circle"
private const val PinFont = "Noto Sans Bold" // served by the OpenFreeMap glyph endpoint the style already uses
private const val SinglePlaceZoom = 16.0
/** A box smaller than this (degrees) is effectively one place: zoom to it instead of fitting. */
private const val TinySpanDeg = 0.001

/**
 * The Map tab: the day's route (broken at gaps) and numbered visit pins in job colours. Tapping a pin (48 dp touch box)
 * opens that visit's drawer. The camera fits the day once per [date]; later data (new pings while tracking) doesn't
 * move it. Banners say why the route is missing or that the day is empty. Everything here is also reachable from the
 * Day tab, so the map is one described node for TalkBack.
 */
@Composable
fun DayMapView(
    dayMap: DayMap?,
    date: LocalDate,
    contentDescription: String,
    onOpenVisit: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The scrubber's position on the route (null: none at that time, e.g. inside a break); the camera follows it. */
    marker: GeoPoint? = null,
    config: MapConfig = MapConfig(),
) {
    val colors = TarryTheme.colors
    val dark = TarryTheme.isDark
    var loaded by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var fittedFor by remember { mutableStateOf<LocalDate?>(null) }
    var markerSeenFor by remember { mutableStateOf<LocalDate?>(null) }
    val openVisit by rememberUpdatedState(onOpenVisit)
    val touchPx = with(LocalDensity.current) { config.pinTouchDp.dp.toPx() }
    val fitPaddingPx = with(LocalDensity.current) { 56.dp.toPx() }.toInt()

    Box(modifier) {
        TarryMap(
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            onStyleLoaded = { map, style ->
                if (loaded?.first !== map) {
                    map.addOnMapClickListener { latLng ->
                        val p = map.projection.toScreenLocation(latLng)
                        val half = touchPx / 2
                        val hit = map.queryRenderedFeatures(RectF(p.x - half, p.y - half, p.x + half, p.y + half), PinCircleLayer)
                        // Overlapping pins: the one nearest the tap, not whichever the query lists first.
                        val nearest = hit.minByOrNull { f ->
                            val at = (f.geometry() as? org.maplibre.geojson.Point)?.let { map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(it.latitude(), it.longitude())) }
                            if (at == null) Float.MAX_VALUE else (at.x - p.x) * (at.x - p.x) + (at.y - p.y) * (at.y - p.y)
                        }
                        val id = nearest?.getStringProperty("id") ?: return@addOnMapClickListener false
                        openVisit(id)
                        true
                    }
                }
                loaded = map to style
            },
        )
        val current = loaded
        LaunchedEffect(current, dayMap, colors, dark) {
            val (map, style) = current ?: return@LaunchedEffect
            if (!style.isFullyLoaded || dayMap == null) return@LaunchedEffect
            style.setRoute(dayMap.route, colors)
            style.setPins(dayMap.pins, colors, dark)
            if (fittedFor != date) {
                dayMap.bounds?.let { map.fitTo(it, fitPaddingPx) }
                fittedFor = date
            }
        }
        LaunchedEffect(current, marker, colors) {
            val (map, style) = current ?: return@LaunchedEffect
            if (!style.isFullyLoaded) return@LaunchedEffect
            style.setMarker(marker, colors)
            // The day's first marker position must not undo the day fit; follow it only once the user scrubs.
            if (marker != null && markerSeenFor == date) map.moveCamera(CameraUpdateFactory.newLatLng(marker.toLatLng()))
            markerSeenFor = date
        }
        val banner = when {
            dayMap == null -> null
            dayMap.routeMissing -> stringResource(R.string.map_route_missing)
            dayMap.bounds == null -> stringResource(R.string.map_nothing_recorded)
            else -> null
        }
        if (banner != null) {
            Text(
                text = banner,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(TarryTheme.space.m)
                    .background(colors.card, TarryTheme.shapes.block)
                    .padding(TarryTheme.space.m)
                    .testTag("map_banner"),
            )
        }
    }
}

private fun MapLibreMap.fitTo(bounds: GeoBounds, paddingPx: Int) {
    val tiny = bounds.north - bounds.south < TinySpanDeg && bounds.east - bounds.west < TinySpanDeg
    val update = if (tiny) {
        CameraUpdateFactory.newLatLngZoom(
            org.maplibre.android.geometry.LatLng((bounds.north + bounds.south) / 2, (bounds.east + bounds.west) / 2),
            SinglePlaceZoom,
        )
    } else {
        CameraUpdateFactory.newLatLngBounds(bounds.toLatLngBounds(), paddingPx)
    }
    moveCamera(update)
}

/** Route and pins always sit below the scrubber marker, whichever was added first. */
private fun Style.addBelowMarker(layer: org.maplibre.android.style.layers.Layer) {
    if (getLayer("$MarkerId-dot") != null) addLayerBelow(layer, "$MarkerId-dot") else addLayer(layer)
}

private fun Style.source(id: String): GeoJsonSource = getSourceAs(id) ?: GeoJsonSource(id).also { addSource(it) }

private fun Style.setRoute(route: List<List<GeoPoint>>, colors: TarryColors) {
    val lines = route.map { seg -> Feature.fromGeometry(LineString.fromLngLats(seg.map { it.toPoint() })) }
    source("$RouteId-src").setGeoJson(FeatureCollection.fromFeatures(lines))
    val layer = getLayer("$RouteId-line") as LineLayer?
        ?: LineLayer("$RouteId-line", "$RouteId-src").also { addBelowMarker(it) }
    layer.setProperties(
        PropertyFactory.lineColor(mapColor(colors.ink.toArgb())),
        PropertyFactory.lineWidth(4f),
        PropertyFactory.lineOpacity(0.85f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
}

/** The scrubber position: an `accent` dot ringed in `ink`, drawn above the pins. */
private fun Style.setMarker(marker: GeoPoint?, colors: TarryColors) {
    val features = listOfNotNull(marker?.let { Feature.fromGeometry(it.toPoint()) })
    source("$MarkerId-src").setGeoJson(FeatureCollection.fromFeatures(features))
    if (getLayer("$MarkerId-dot") == null) {
        addLayer(
            CircleLayer("$MarkerId-dot", "$MarkerId-src").withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(mapColor(colors.accent.toArgb())),
                PropertyFactory.circleStrokeColor(mapColor(colors.ink.toArgb())),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }
}

/** Pins carry their own colours as properties so job colours need no per-job layers. */
private fun Style.setPins(pins: List<VisitPin>, colors: TarryColors, dark: Boolean) {
    val features = pins.map { pin ->
        val job = pin.jobId?.let { JobPalette.lookup(pin.jobColorArgb, it).colors(dark) }
        Feature.fromGeometry(pin.position.toPoint()).apply {
            addStringProperty("id", pin.visitId)
            addStringProperty("n", pin.number.toString())
            addStringProperty("fill", mapColor((job?.container ?: colors.card).toArgb()))
            addStringProperty("stroke", mapColor((job?.border ?: colors.outline).toArgb()))
            addStringProperty("text", mapColor((job?.text ?: colors.ink).toArgb()))
        }
    }
    source("$PinsId-src").setGeoJson(FeatureCollection.fromFeatures(features))
    if (getLayer(PinCircleLayer) == null) {
        addBelowMarker(
            CircleLayer(PinCircleLayer, "$PinsId-src").withProperties(
                PropertyFactory.circleRadius(13f),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("fill"))),
                PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("stroke"))),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        addBelowMarker(
            SymbolLayer("$PinsId-label", "$PinsId-src").withProperties(
                PropertyFactory.textField(Expression.get("n")),
                PropertyFactory.textFont(arrayOf(PinFont)),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor(Expression.toColor(Expression.get("text"))),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ),
        )
    }
}
