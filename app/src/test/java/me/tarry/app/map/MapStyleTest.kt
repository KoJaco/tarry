package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class MapStyleTest {
    private val template = File("src/main/assets/${MapStyle.TemplateAsset}").readText()

    // Distinct per token so a swapped mapping would show.
    private val palette = MapStyle.Tokens.withIndex().associate { (i, token) -> token to (0xFF000000.toInt() or (i + 1)) }

    @Test
    fun templateHasNoColourLiterals() {
        // Colours come from tokens.md through placeholders only (no hex, rgb or hsl strings).
        assertThat(Regex(""""\s*(#[0-9a-fA-F]{3,8}|rgba?\(|hsla?\()""").containsMatchIn(template)).isFalse()
    }

    @Test
    fun templateUsesExactlyTheKnownTokens() {
        assertThat(MapStyle.placeholders(template)).isEqualTo(MapStyle.Tokens)
    }

    @Test
    fun buildResolvesEveryPlaceholder() {
        val style = MapStyle.build(template, palette)
        assertThat(style).doesNotContain("{{")
        assertThat(style).contains("\"rgba(0,0,1,1.0)\"")
    }

    @Test
    fun templateKeepsOpenFreeMapSourcesAndOnlyThoseHosts() {
        val hosts = Regex("""https://([^/"]+)""").findAll(template).map { it.groupValues[1] }.toSet()
        assertThat(hosts).containsExactly("tiles.openfreemap.org")
        assertThat(template).contains("https://tiles.openfreemap.org/planet")
    }

    @Test
    fun missingTokenThrows() {
        assertThrows(IllegalArgumentException::class.java) { MapStyle.build("{\"c\":\"{{nope}}\"}", palette) }
    }

    @Test
    fun rgbaFormatsAlpha() {
        assertThat(MapStyle.rgba(0xFFF3EFE8.toInt())).isEqualTo("rgba(243,239,232,1.0)")
        assertThat(MapStyle.rgba(0x80FFFFFF.toInt())).isEqualTo("rgba(255,255,255,0.5)")
    }
}
