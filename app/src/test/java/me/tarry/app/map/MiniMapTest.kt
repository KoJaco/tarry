package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

class MiniMapTest {
    private val key = MiniMapKey("p", GeoPoint(-33.0, 151.0), 75f, false, 800, 360)

    @Test
    fun keyChangesWithEveryInput() {
        assertThat(key).isEqualTo(key.copy())
        assertThat(key.copy(placeId = "q")).isNotEqualTo(key)
        assertThat(key.copy(centre = GeoPoint(-33.0001, 151.0))).isNotEqualTo(key)
        assertThat(key.copy(radiusM = 100f)).isNotEqualTo(key)
        assertThat(key.copy(isDark = true)).isNotEqualTo(key)
        assertThat(key.copy(widthPx = 801)).isNotEqualTo(key)
        assertThat(key.copy(heightPx = 361)).isNotEqualTo(key)
    }

    @Test
    fun fitRadiusLeavesMarginAndNeverCollapses() {
        val config = MiniMapConfig()
        assertThat(config.fitRadiusM(100f)).isWithin(1e-9).of(160.0)
        assertThat(config.fitRadiusM(0f)).isGreaterThan(0.0)
    }

    @Test
    fun miniMapStyleBakesTheCircleIntoTheStyleOnTop() {
        // Regression: adding the circle through Style.Builder crashed the snapshotter ("invalid native peer").
        val palette = MapStyle.Tokens.associateWith { 0xFF000000.toInt() }
        val base = MapStyle.build(File("src/main/assets/${MapStyle.TemplateAsset}").readText(), palette)
        val ring = listOf(GeoPoint(-33.0, 151.0), GeoPoint(-33.0, 151.1), GeoPoint(-33.1, 151.0), GeoPoint(-33.0, 151.0))
        val baseRoot = Json.parseToJsonElement(base).jsonObject
        val root = Json.parseToJsonElement(miniMapStyle(base, ring, "rgba(1,2,3,0.5)", "rgba(4,5,6,1.0)")).jsonObject

        val sources = root.getValue("sources").jsonObject
        assertThat(sources.keys).containsAtLeastElementsIn(baseRoot.getValue("sources").jsonObject.keys)
        val first = sources.getValue(MiniMapSourceId).jsonObject.getValue("data").jsonObject.getValue("geometry").jsonObject
            .getValue("coordinates").jsonArray[0].jsonArray[0].jsonArray
        assertThat(first.map { it.jsonPrimitive.content.toDouble() }).containsExactly(151.0, -33.0).inOrder() // lng, lat

        val layers = root.getValue("layers").jsonArray
        assertThat(layers.size).isEqualTo(baseRoot.getValue("layers").jsonArray.size + 2)
        assertThat(layers.takeLast(2).map { it.jsonObject.getValue("id").jsonPrimitive.content }).containsExactly("place-fill", "place-line").inOrder()
        assertThat(root.getValue("glyphs")).isEqualTo(baseRoot.getValue("glyphs"))
    }
}
