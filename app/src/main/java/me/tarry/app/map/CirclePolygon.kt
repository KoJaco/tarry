package me.tarry.app.map

import me.tarry.segmentation.geo.EARTH_RADIUS_M
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A closed ring (last == first, so `segments + 1` points) approximating a circle of [radiusM] around [centre]. */
fun circlePolygon(
    centre: GeoPoint,
    radiusM: Double,
    segments: Int = MapConfig().circleSegments,
): List<GeoPoint> {
    require(segments >= 3) { "segments must be >= 3" }
    val lat1 = Math.toRadians(centre.lat)
    val lng1 = Math.toRadians(centre.lng)
    val ang = radiusM / EARTH_RADIUS_M
    val ring = ArrayList<GeoPoint>(segments + 1)
    for (i in 0 until segments) {
        val brg = 2 * Math.PI * i / segments
        val lat2 = asin(sin(lat1) * cos(ang) + cos(lat1) * sin(ang) * cos(brg))
        val lng2 = lng1 + atan2(sin(brg) * sin(ang) * cos(lat1), cos(ang) - sin(lat1) * sin(lat2))
        ring += GeoPoint(Math.toDegrees(lat2), ((Math.toDegrees(lng2) + 540.0) % 360.0) - 180.0)
    }
    ring += ring[0]
    return ring
}
