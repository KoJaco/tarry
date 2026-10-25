package me.tarry.app.ui.map

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import me.tarry.app.map.MapConfig
import me.tarry.app.map.MapStyle
import me.tarry.app.map.MiniMapConfig
import me.tarry.app.map.MiniMapKey
import me.tarry.app.ui.theme.TarryTheme
import me.tarry.app.ui.timeline.formatDistance
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import me.tarry.app.map.circlePolygon
import me.tarry.app.map.miniMapStyle

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
            val fill = colors.accent.copy(alpha = RadiusFillAlpha).toArgb()
            val outline = colors.accentStrong.toArgb()
            val palette = colors.mapPalette()
            DisposableEffect(key) {
                MapLibreInit.ensure(context)
                val ring = circlePolygon(centre, radiusM.toDouble(), MapConfig().circleSegments)
                val styleJson = miniMapStyle(MapStyle.build(context.styleTemplate(), palette), ring, mapColor(fill), mapColor(outline))
                val builder = Style.Builder().fromJson(styleJson)
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
            // The snapshot carries MapLibre's own credit line (bottom right), so none is drawn over it here.
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(config.heightDp.dp),
                )
            }
        }
    }
}
