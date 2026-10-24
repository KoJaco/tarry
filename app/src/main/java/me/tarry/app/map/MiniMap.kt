package me.tarry.app.map

/** Tunables for the drawer's static place mini map (AGENTS.md rule 5: no scattered literals). */
data class MiniMapConfig(
    val heightDp: Int = 120,
    /** The camera shows a circle of radius * this around the place, so the radius circle sits inside with margin. */
    val fitFactor: Double = 1.6,
    /** In-memory bitmap cache budget. */
    val cacheBytes: Int = 4 * 1024 * 1024,
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
