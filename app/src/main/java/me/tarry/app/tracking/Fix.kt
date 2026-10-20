package me.tarry.app.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A location fix, free of Android types so capture logic runs on the plain JVM. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val timeMs: Long,
    val speedMps: Float? = null,
    val bearing: Float? = null,
)

/** Great-circle (haversine) distance in metres. */
internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

internal fun Fix.distanceTo(other: Fix): Double = distanceMeters(lat, lng, other.lat, other.lng)

private const val EARTH_RADIUS_M = 6_371_000.0
