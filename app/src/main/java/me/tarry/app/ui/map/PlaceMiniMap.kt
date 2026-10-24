package me.tarry.app.ui.map

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import me.tarry.app.R
import me.tarry.app.map.GeoPoint
import me.tarry.app.map.MapStyle
import me.tarry.app.map.MiniMapConfig
import me.tarry.app.map.MiniMapKey
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.timeline.formatDistance
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Polygon
import me.tarry.app.map.circlePolygon

private val MiniMapConfigDefault = MiniMapConfig()

private val bitmapCache = object : LruCache<MiniMapKey, Bitmap>(MiniMapConfigDefault.cacheBytes) {
    override fun sizeOf(key: MiniMapKey, value: Bitmap): Int = value.byteCount
}

/**
 * A static, non-interactive map of a place and its radius for the edit drawer header (no route). Rendered once by
 * [MapSnapshotter] while in composition (so foreground only), cached in memory, and cancelled on leaving composition.
 * A plain `inset` box of the same size stands in while loading or if the snapshot fails, so layout never shifts.
 */
@Composable
fun PlaceMiniMap(
    placeId: String,
    placeLabel: String,
    centre: GeoPoint,
    radiusM: Float,
    modifier: Modifier = Modifier,
    config: MiniMapConfig = MiniMapConfigDefault,
) {
    val context = LocalContext.current
    val colors = TarryTheme.colors
    val density = LocalDensity.current
    val shape = TarryTheme.shapes.card
    val description = stringResource(R.string.map_place_mini_description, placeLabel, formatDistance(radiusM))
    BoxWithConstraints(modifier.fillMaxWidth().height(config.heightDp.dp)) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { config.heightDp.dp.roundToPx() }
        val key = MiniMapKey(placeId, centre, radiusM, TarryTheme.isDark, widthPx, heightPx)
        var bitmap by remember(key) { mutableStateOf(bitmapCache.get(key)) }
        if (bitmap == null && widthPx > 0) {
            val fill = colors.accent.copy(alpha = 0.22f).toArgb()
            val outline = colors.accentStrong.toArgb()
            val palette = colors.mapPalette()
            DisposableEffect(key) {
                MapLibreInit.ensure(context)
                val styleJson = MapStyle.build(context.styleTemplate(), palette)
                val ring = circlePolygon(centre, radiusM.toDouble(), 64).map { it.toPoint() }
                val builder = Style.Builder()
                    .fromJson(styleJson)
                    .withSource(GeoJsonSource("place-src", Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))))
                    .withLayer(FillLayer("place-fill", "place-src").withProperties(PropertyFactory.fillColor(mapColor(fill))))
                    .withLayer(
                        LineLayer("place-line", "place-src").withProperties(
                            PropertyFactory.lineColor(mapColor(outline)),
                            PropertyFactory.lineWidth(2f),
                        ),
                    )
                val ratio = density.density
                val options = MapSnapshotter.Options((widthPx / ratio).toInt().coerceAtLeast(1), config.heightDp)
                    .withPixelRatio(ratio)
                    .withStyleBuilder(builder)
                    .withRegion(radiusBounds(centre, config.fitRadiusM(radiusM)).toLatLngBounds())
                    .withLogo(false)
                val snapshotter = MapSnapshotter(context, options)
                snapshotter.start(
                    { snapshot ->
                        bitmapCache.put(key, snapshot.bitmap)
                        bitmap = snapshot.bitmap
                    },
                    { _ -> /* keep the placeholder */ },
                )
                onDispose { snapshotter.cancel() }
            }
        }
        val frame = Modifier.fillMaxWidth().height(config.heightDp.dp).clip(shape)
        Box(frame.background(colors.inset).clearAndSetSemantics { contentDescription = description }) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(config.heightDp.dp),
                )
                Text(
                    text = stringResource(R.string.map_attribution_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.ink,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(TarryTheme.space.xs)
                        .background(colors.paper.copy(alpha = 0.85f), TarryTheme.shapes.pill)
                        .padding(horizontal = TarryTheme.space.s, vertical = 2.dp),
                )
            }
        }
    }
}
