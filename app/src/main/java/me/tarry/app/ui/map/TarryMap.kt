package me.tarry.app.ui.map

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.tarry.app.R
import me.tarry.app.map.MapStyle
import me.tarry.app.ui.components.TertiaryButton
import me.tarry.app.ui.theme.TarryColors
import me.tarry.app.ui.theme.TarryTheme
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private val MinTouch = 48.dp

/** Height of the top strip the attribution takes; maps keep their content clear of it. */
internal val MapAttributionClearance = MinTouch

/** The credit line grows with the font scale only up to this, so it stays one line over the map (the dialog it opens scales fully). */
private const val AttributionMaxFontScale = 1.3f

/** Multiplier for the credit line's sp size so its rendered scale is the user's, capped at [AttributionMaxFontScale]. */
internal fun attributionFontFactor(fontScale: Float): Float = if (fontScale <= AttributionMaxFontScale) 1f else AttributionMaxFontScale / fontScale

/** Token name to ARGB for [MapStyle.build]; the names match `docs/design/tokens.md` and the template placeholders. */
internal fun TarryColors.mapPalette(): Map<String, Int> = mapOf(
    "paper" to paper.toArgb(),
    "card" to card.toArgb(),
    "inset" to inset.toArgb(),
    "ink" to ink.toArgb(),
    "muted" to muted.toArgb(),
    "line" to line.toArgb(),
    "outline" to outline.toArgb(),
    "mapWater" to mapWater.toArgb(),
    "mapGreen" to mapGreen.toArgb(),
)

@Volatile private var cachedTemplate: String? = null

/** The bundled style template, read from assets once per process (it is ~44 KB and every map and snapshot needs it). */
internal fun Context.styleTemplate(): String =
    cachedTemplate ?: assets.open(MapStyle.TemplateAsset).bufferedReader().use { it.readText() }.also { cachedTemplate = it }

/**
 * A MapLibre map in the Tarry theme, with the required OpenFreeMap / OpenMapTiles / OSM attribution. The MapView
 * follows the host lifecycle and is destroyed when this leaves composition, so it only renders in the foreground.
 * [onStyleLoaded] runs each time the style (re)loads, e.g. after a light/dark switch, so callers add their own
 * sources and layers there. [contentDescription] is required: the map is one opaque node to TalkBack, and everything
 * on it must also be reachable without it (the timeline).
 */
@Composable
fun TarryMap(
    contentDescription: String,
    modifier: Modifier = Modifier,
    /** False for previews (radius sheet): no pan, zoom, rotate or tilt, so the sheet's own gestures win. */
    interactive: Boolean = true,
    onStyleLoaded: (MapLibreMap, Style) -> Unit = { _, _ -> },
) {
    if (!LocalMapsEnabled.current) {
        Box(modifier.background(TarryTheme.colors.inset).semantics { this.contentDescription = contentDescription })
        return
    }
    TarryMapView(contentDescription, modifier, interactive, onStyleLoaded)
}

@Composable
private fun TarryMapView(
    contentDescription: String,
    modifier: Modifier,
    interactive: Boolean,
    onStyleLoaded: (MapLibreMap, Style) -> Unit,
) {
    val context = LocalContext.current
    val colors = TarryTheme.colors
    val template = remember { context.styleTemplate() }
    val styleJson = remember(template, colors) { MapStyle.build(template, colors.mapPalette()) }
    val currentOnStyleLoaded by rememberUpdatedState(onStyleLoaded)
    val compassTopPx = with(LocalDensity.current) { (MinTouch + TarryTheme.space.xs).roundToPx() }
    val compassEndPx = with(LocalDensity.current) { TarryTheme.space.s.roundToPx() }
    val mapView = remember {
        MapLibreInit.ensure(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .logoEnabled(false)
            // Drawn in Compose instead (MapAttribution): MapLibre's "i" button is below the 48 dp touch target.
            .attributionEnabled(false)
        MapView(context, options).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Walk the view down to destroyed whatever state the host is in.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, styleJson) {
        mapView.getMapAsync { map ->
            map.uiSettings.setAllGesturesEnabled(interactive)
            // The compass (shown once the map is rotated) shares the top-right corner with the attribution: sit below it.
            map.uiSettings.setCompassMargins(0, compassTopPx, compassEndPx, 0)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style -> currentOnStyleLoaded(map, style) }
        }
    }
    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .testTag("map")
                .semantics { this.contentDescription = contentDescription },
        )
        // Top right, clear of the overlays callers put on the bottom edge (the day scrubber).
        MapAttribution(Modifier.align(Alignment.TopEnd))
    }
}

/** The compact credit line over the map; tapping it opens the credits with links (OSM attribution guidelines). */
@Composable
private fun MapAttribution(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val c = TarryTheme.colors
    val action = stringResource(R.string.map_attribution_action)
    Box(
        modifier = modifier
            .heightIn(min = MinTouch)
            .widthIn(min = MinTouch)
            .clickable(onClickLabel = action, role = Role.Button) { open = true }
            .padding(TarryTheme.space.xs),
        contentAlignment = Alignment.TopEnd,
    ) {
        Text(
            text = stringResource(R.string.map_attribution_short),
            style = MaterialTheme.typography.labelSmall.let { it.copy(fontSize = it.fontSize * attributionFontFactor(LocalDensity.current.fontScale)) },
            color = c.ink,
            modifier = Modifier
                .background(c.paper.copy(alpha = 0.85f), TarryTheme.shapes.pill)
                .padding(horizontal = TarryTheme.space.s, vertical = TarryTheme.space.xs),
        )
    }
    if (open) {
        val links = TextLinkStyles(SpanStyle(color = c.ink, textDecoration = TextDecoration.Underline))
        val credits = listOf(
            R.string.map_attribution_openfreemap to "https://openfreemap.org",
            R.string.map_attribution_openmaptiles to "https://www.openmaptiles.org/",
            R.string.map_attribution_osm to "https://www.openstreetmap.org/copyright",
        )
        AlertDialog(
            onDismissRequest = { open = false },
            shape = TarryTheme.shapes.container,
            containerColor = c.card,
            title = { Text(stringResource(R.string.map_attribution_title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(TarryTheme.space.s)) {
                    credits.forEach { (label, url) ->
                        val text = stringResource(label)
                        Text(
                            text = buildAnnotatedString { withLink(LinkAnnotation.Url(url, links)) { append(text) } },
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.ink,
                            modifier = Modifier.heightIn(min = MinTouch),
                        )
                    }
                }
            },
            confirmButton = { TertiaryButton(stringResource(R.string.map_attribution_close), onClick = { open = false }) },
        )
    }
}
