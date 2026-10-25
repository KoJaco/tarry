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
import me.tarry.app.map.MarkerFollow
import me.tarry.app.map.TripRoute
import me.tarry.app.map.circlePolygon
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.geojson.Polygon
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
private const val AreasId = "day-areas"
private const val RouteWidth = 4f
private const val PinCircleLayer = "$PinsId-circle"
private const val PinFont = "Noto Sans Bold" // served by the OpenFreeMap glyph endpoint the style already uses
private const val SinglePlaceZoom = 16.0
/** A box smaller than this (degrees) is effectively one place: zoom to it instead of fitting. */
private const val TinySpanDeg = 0.001
private val BannerTop = 48.dp
/** Margin around the day when fitting it, inside the attribution strip and the bottom overlay (a pin is 26 dp across). */
private val FitPadding = 32.dp
private const val BreakEndRadius = 4f
private const val FadedRouteOpacity = 0.3f

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
    /** Height of whatever floats over the map's bottom (the scrubber): the camera centres in the space above it. */
    bottomInsetPx: Int = 0,
    /** The scrubber's time: the trip or visit it is on is drawn solid and full strength, the rest faded. */
    activeTime: Long? = null,
    config: MapConfig = MapConfig(),
) {
    val colors = TarryTheme.colors
    val dark = TarryTheme.isDark
    var loaded by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var fittedFor by remember { mutableStateOf<LocalDate?>(null) }
    val markerFollow = remember { MarkerFollow() }
    val openVisit by rememberUpdatedState(onOpenVisit)
    val touchPx = with(LocalDensity.current) { config.pinTouchDp.dp.toPx() }
    val fitPaddingPx = with(LocalDensity.current) { FitPadding.toPx() }.toInt()
    val topInsetPx = with(LocalDensity.current) { MapAttributionClearance.toPx() }.toDouble()

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
        // Keys rather than the raw time, so layers refresh when the emphasis moves, not on every drag frame.
        val activeTrip = activeTime?.let { t -> dayMap?.routes?.firstOrNull { t >= it.start && t < it.end }?.start }
        val activeVisit = activeTime?.let { t -> dayMap?.pins?.firstOrNull { t >= it.start && t < it.end }?.visitId }
        LaunchedEffect(current, dayMap, colors, dark, activeTrip, activeVisit) {
            val (map, style) = current ?: return@LaunchedEffect
            if (!style.isFullyLoaded || dayMap == null) return@LaunchedEffect
            // Added bottom-up on first use: visit areas, then trips, then pins (all below the marker).
            style.setVisitAreas(dayMap.pins, activeVisit, colors, dark, config)
            style.setRoutes(dayMap.routes, activeTrip, colors)
            style.setPins(dayMap.pins, colors, dark)
            if (fittedFor != date) {
                dayMap.bounds?.let { map.fitTo(it, fitPaddingPx, topInsetPx.toInt(), bottomInsetPx) }
                fittedFor = date
            }
        }
        LaunchedEffect(current, bottomInsetPx, topInsetPx) {
            val (map, _) = current ?: return@LaunchedEffect
            // Centre between the attribution strip and whatever floats over the bottom.
            map.moveCamera(CameraUpdateFactory.paddingTo(0.0, topInsetPx, 0.0, bottomInsetPx.toDouble()))
        }
        LaunchedEffect(current, marker, colors) {
            val (map, style) = current ?: return@LaunchedEffect
            if (!style.isFullyLoaded) return@LaunchedEffect
            style.setMarker(marker, colors)
            if (marker != null && markerFollow.follow(date, marker)) map.moveCamera(CameraUpdateFactory.newLatLng(marker.toLatLng()))
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
                    // Below the attribution, which sits in the top-right corner.
                    .padding(top = BannerTop)
                    .padding(TarryTheme.space.m)
                    .background(colors.card, TarryTheme.shapes.block)
                    .padding(TarryTheme.space.m)
                    .testTag("map_banner"),
            )
        }
    }
}

/**
 * Fits [bounds] inside the map less the attribution strip ([topPx]) and the bottom overlay ([bottomPx]), then restores
 * the camera padding the marker centring uses. MapLibre adds a bounds fit's padding to the camera's current padding, so
 * that is cleared first. The fit's extra padding is equal top and bottom, so restoring the padding doesn't move the view.
 */
private fun MapLibreMap.fitTo(bounds: GeoBounds, paddingPx: Int, topPx: Int, bottomPx: Int) {
    val tiny = bounds.north - bounds.south < TinySpanDeg && bounds.east - bounds.west < TinySpanDeg
    val update = if (tiny) {
        CameraUpdateFactory.newLatLngZoom(
            org.maplibre.android.geometry.LatLng((bounds.north + bounds.south) / 2, (bounds.east + bounds.west) / 2),
            SinglePlaceZoom,
        )
    } else {
        CameraUpdateFactory.newLatLngBounds(bounds.toLatLngBounds(), paddingPx, topPx + paddingPx, paddingPx, bottomPx + paddingPx)
    }
    moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
    moveCamera(update)
    moveCamera(CameraUpdateFactory.paddingTo(0.0, topPx.toDouble(), 0.0, bottomPx.toDouble()))
}

/** Route and pins always sit below the scrubber marker, whichever was added first. */
private fun Style.addBelowMarker(layer: org.maplibre.android.style.layers.Layer) {
    if (getLayer("$MarkerId-dot") != null) addLayerBelow(layer, "$MarkerId-dot") else addLayer(layer)
}

private fun Style.source(id: String): GeoJsonSource = getSourceAs(id) ?: GeoJsonSource(id).also { addSource(it) }

private fun List<List<GeoPoint>>.toLines() =
    FeatureCollection.fromFeatures(map { seg -> Feature.fromGeometry(LineString.fromLngLats(seg.map { it.toPoint() })) })

/**
 * The trip the scrubber is on ([activeTrip], its start) at full strength; every other trip faded, so where the user is
 * reads at a glance. Only recorded stretches are lines: an unrecorded one (no fixes, e.g. a ferry leg) is a break,
 * with a small hollow dot at the fix either side.
 */
private fun Style.setRoutes(routes: List<TripRoute>, activeTrip: Long?, colors: TarryColors) {
    val (active, others) = routes.partition { it.start == activeTrip }
    source("$RouteId-src").setGeoJson(active.flatMap { it.solid }.toLines())
    source("$RouteId-rest-src").setGeoJson(others.flatMap { it.solid }.toLines())
    source("$RouteId-ends-src").setGeoJson(
        FeatureCollection.fromFeatures(
            routes.flatMap { r ->
                r.breakEnds.map { Feature.fromGeometry(it.toPoint()).apply { addBooleanProperty("active", r.start == activeTrip) } }
            },
        ),
    )
    val ink = mapColor(colors.ink.toArgb())
    val rest = getLayer("$RouteId-rest") as LineLayer?
        ?: LineLayer("$RouteId-rest", "$RouteId-rest-src").also { addBelowMarker(it) }
    rest.setProperties(
        PropertyFactory.lineColor(ink),
        PropertyFactory.lineWidth(RouteWidth * 0.75f),
        PropertyFactory.lineOpacity(FadedRouteOpacity),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    val solid = getLayer("$RouteId-line") as LineLayer?
        ?: LineLayer("$RouteId-line", "$RouteId-src").also { addBelowMarker(it) }
    solid.setProperties(
        PropertyFactory.lineColor(ink),
        PropertyFactory.lineWidth(RouteWidth),
        PropertyFactory.lineOpacity(0.85f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    val ends = getLayer("$RouteId-ends") as CircleLayer?
        ?: CircleLayer("$RouteId-ends", "$RouteId-ends-src").also { addBelowMarker(it) }
    val endOpacity = Expression.switchCase(Expression.get("active"), Expression.literal(1f), Expression.literal(FadedRouteOpacity))
    ends.setProperties(
        PropertyFactory.circleRadius(BreakEndRadius),
        PropertyFactory.circleColor(mapColor(colors.paper.toArgb())),
        PropertyFactory.circleStrokeColor(ink),
        PropertyFactory.circleStrokeWidth(2f),
        PropertyFactory.circleOpacity(endOpacity),
        PropertyFactory.circleStrokeOpacity(endOpacity),
    )
}

/** Each pinned visit as its place's radius, tinted in the job colour: a stay reads as one area, not ping jitter. */
private fun Style.setVisitAreas(pins: List<VisitPin>, activeVisit: String?, colors: TarryColors, dark: Boolean, config: MapConfig) {
    val features = pins.mapNotNull { pin ->
        val radius = pin.radiusM ?: return@mapNotNull null
        val job = pin.jobId?.let { JobPalette.lookup(pin.jobColorArgb, it).colors(dark) }
        val ring = circlePolygon(pin.position, radius.toDouble(), config.circleSegments).map { it.toPoint() }
        Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
            addStringProperty("fill", mapColor((job?.container ?: colors.accent.copy(alpha = RadiusFillAlpha)).toArgb()))
            addStringProperty("stroke", mapColor((job?.border ?: colors.accentStrong).toArgb()))
            addBooleanProperty("active", pin.visitId == activeVisit)
        }
    }
    source("$AreasId-src").setGeoJson(FeatureCollection.fromFeatures(features))
    if (getLayer("$AreasId-fill") == null) {
        addBelowMarker(
            FillLayer("$AreasId-fill", "$AreasId-src").withProperties(
                PropertyFactory.fillColor(Expression.toColor(Expression.get("fill"))),
                PropertyFactory.fillOpacity(Expression.switchCase(Expression.get("active"), Expression.literal(0.6f), Expression.literal(0.3f))),
            ),
        )
        addBelowMarker(
            LineLayer("$AreasId-line", "$AreasId-src").withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineOpacity(Expression.switchCase(Expression.get("active"), Expression.literal(1f), Expression.literal(0.45f))),
            ),
        )
    }
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
