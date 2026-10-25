package me.tarry.app.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Tunables for the drawer's static place mini map (AGENTS.md rule 5: no scattered literals). */
data class MiniMapConfig(
    val heightDp: Int = 120,
    /** The camera shows a circle of radius * this around the place, so the radius circle sits inside with margin. */
    val fitFactor: Double = 1.6,
    /** In-memory bitmap cache budget. */
    // A full-width header bitmap is ~1–1.5 MB on a Pixel; 16 MB keeps a day's worth of places.
    val cacheBytes: Int = 16 * 1024 * 1024,
) {
    /** Radius of the circle the camera is fitted to. */
    fun fitRadiusM(radiusM: Float): Double = radiusM.coerceAtLeast(1f) * fitFactor
}

/** Everything a rendered mini map depends on; two equal keys give the same bitmap. */
data class MiniMapKey(
    val placeId: String,
    val centre: GeoPoint,
    val radiusM: Float,
    val isDark: Boolean,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * The style for a place mini map: [style] with the place's radius [ring] baked in as a source plus fill and outline layers
 * drawn on top. Baked into the JSON rather than added through `Style.Builder.withSource`: a snapshotter given an inline
 * style finishes loading it inside its own constructor, before its native peer is set, and adding a source there
 * crashes ("invalid native peer", MapLibre 13.7.1).
 */
fun miniMapStyle(style: String, ring: List<GeoPoint>, fillColor: String, outlineColor: String): String {
    val root = Json.parseToJsonElement(style).jsonObject
    val polygon = buildJsonObject {
        put("type", "Feature")
        put("properties", buildJsonObject {})
        put("geometry", buildJsonObject {
            put("type", "Polygon")
            put("coordinates", buildJsonArray { add(buildJsonArray { ring.forEach { add(buildJsonArray { add(it.lng); add(it.lat) }) } }) })
        })
    }
    val source = buildJsonObject {
        put("type", "geojson")
        put("data", polygon)
    }
    fun layer(id: String, type: String, paint: JsonObject) = buildJsonObject {
        put("id", id)
        put("type", type)
        put("source", MiniMapSourceId)
        put("paint", paint)
    }
    val sources = JsonObject(root.getValue("sources").jsonObject + (MiniMapSourceId to source))
    val layers = JsonArray(
        root.getValue("layers").jsonArray +
            layer("place-fill", "fill", buildJsonObject { put("fill-color", fillColor) }) +
            layer("place-line", "line", buildJsonObject { put("line-color", outlineColor); put("line-width", 2) }),
    )
    return JsonObject(root + ("sources" to sources) + ("layers" to layers)).toString()
}

const val MiniMapSourceId = "place-src"
