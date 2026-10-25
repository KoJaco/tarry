package me.tarry.app.map

/**
 * Builds the MapLibre style JSON from the bundled template ([TemplateAsset], derived from OpenFreeMap Positron). The
 * template holds no colour literals: every colour is a `{{token}}` placeholder named after a `docs/design/tokens.md`
 * token, filled here from [palette] (token name to ARGB). Pure Kotlin so it is JVM-testable.
 */
object MapStyle {
    const val TemplateAsset = "map/style.json"

    /** Every token the template uses; [build] needs a colour for each. */
    val Tokens = setOf("paper", "card", "inset", "ink", "muted", "line", "outline", "mapWater", "mapGreen")

    private val Placeholder = Regex("""\{\{(\w+)\}\}""")

    /** Replaces each placeholder with an `rgba(...)` colour. Throws on a token [palette] lacks. */
    fun build(template: String, palette: Map<String, Int>): String =
        Placeholder.replace(template) { match ->
            val token = match.groupValues[1]
            val argb = requireNotNull(palette[token]) { "No colour for map style token '$token'" }
            rgba(argb)
        }

    /** `rgba(r,g,b,a)` with alpha rounded to two decimals, the form the style spec accepts. */
    fun rgba(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val alpha = Math.round(a / 255.0 * 100) / 100.0
        return "rgba($r,$g,$b,$alpha)"
    }

    /** Placeholders in [template], for tests and debugging. */
    fun placeholders(template: String): Set<String> = Placeholder.findAll(template).map { it.groupValues[1] }.toSet()
}
