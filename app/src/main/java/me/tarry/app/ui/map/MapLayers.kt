package me.tarry.app.ui.map

import me.tarry.app.map.GeoBounds
import me.tarry.app.map.GeoPoint
import me.tarry.app.map.MapConfig
import me.tarry.app.map.MapStyle
import me.tarry.app.map.circlePolygon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Small helpers that turn the pure `map/` types into MapLibre sources and layers. Every `set…` call adds its source and
 * layers the first time and only updates the data after that, so they are safe to call on every recomposition or
 * slider move. Colours are ARGB ints from `TarryTheme.colors` (never literals).
 */

/** Alpha of the `accent` fill inside a place radius circle (drawer mini map, radius sheet, pin picker). */
const val RadiusFillAlpha = 0.22f

fun GeoPoint.toLatLng(): LatLng = LatLng(lat, lng)

fun GeoPoint.toPoint(): Point = Point.fromLngLat(lng, lat)

fun GeoBounds.toLatLngBounds(): LatLngBounds = LatLngBounds.from(north, east, south, west)

/** `rgba(...)` for a MapLibre colour property; the same formatting the style template uses. */
fun mapColor(argb: Int): String = MapStyle.rgba(argb)

private fun Style.geoJson(id: String): GeoJsonSource =
    getSourceAs(id) ?: GeoJsonSource(id).also { addSource(it) }

/** A place's radius as a filled, outlined circle (sources and layers prefixed with [id]). */
fun Style.setRadiusCircle(id: String, centre: GeoPoint, radiusM: Double, fillArgb: Int, outlineArgb: Int, config: MapConfig = MapConfig()) {
    val ring = circlePolygon(centre, radiusM, config.circleSegments).map { it.toPoint() }
    geoJson("$id-src").setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
    if (getLayer("$id-fill") == null) {
        addLayer(FillLayer("$id-fill", "$id-src").withProperties(PropertyFactory.fillColor(mapColor(fillArgb))))
        addLayer(
            LineLayer("$id-line", "$id-src").withProperties(
                PropertyFactory.lineColor(mapColor(outlineArgb)),
                PropertyFactory.lineWidth(2f),
            ),
        )
    }
}

/** Small dots (e.g. visit positions in the radius preview). [radiusDp] is the dot radius. */
fun Style.setDots(id: String, points: List<GeoPoint>, colorArgb: Int, strokeArgb: Int, radiusDp: Float = 5f) {
    geoJson("$id-src").setGeoJson(FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(it.toPoint()) }))
    if (getLayer("$id-dots") == null) {
        addLayer(
            CircleLayer("$id-dots", "$id-src").withProperties(
                PropertyFactory.circleColor(mapColor(colorArgb)),
                PropertyFactory.circleRadius(radiusDp),
                PropertyFactory.circleStrokeColor(mapColor(strokeArgb)),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
    }
}

/** Bounds of a circle of [radiusM] around [centre], for fitting the camera to a radius. */
fun radiusBounds(centre: GeoPoint, radiusM: Double): GeoBounds {
    val ring = circlePolygon(centre, radiusM, 16)
    return GeoBounds(ring.minOf { it.lat }, ring.minOf { it.lng }, ring.maxOf { it.lat }, ring.maxOf { it.lng })
}
