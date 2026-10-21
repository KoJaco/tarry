package me.tarry.segmentation.trace

import me.tarry.segmentation.Activity
import me.tarry.segmentation.Ping
import me.tarry.segmentation.TimeRange
import me.tarry.segmentation.geo.haversineMeters
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

typealias Trace = List<Ping>

/** Harbour-side Sydney, used as the default synthetic origin. */
const val SYDNEY_LAT = -33.8568
const val SYDNEY_LNG = 151.2153
val SYDNEY_ORIGIN: Pair<Double, Double> = SYDNEY_LAT to SYDNEY_LNG

private const val EARTH_RADIUS_M = 6_371_000.0
private const val METERS_PER_DEG_LAT = 111_194.9

/** Local flat-earth offset; fine under 50 km. */
fun offsetMeters(lat: Double, lng: Double, northM: Double, eastM: Double): Pair<Double, Double> {
    val dLat = northM / METERS_PER_DEG_LAT
    val dLng = eastM / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))
    return (lat + dLat) to (lng + dLng)
}

/** Epoch ms of a local date and time in [zone]. */
fun atLocal(date: LocalDate, time: LocalTime, zone: String = "Australia/Sydney"): Long =
    date.atTime(time).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

/** The time span a trace covers: first ping to one ms after the last. Empty trace gives an empty range at 0. */
fun Trace.range(): TimeRange =
    if (isEmpty()) TimeRange(0, 0) else TimeRange(minOf { it.timeMs }, maxOf { it.timeMs } + 1)

/** Deterministic, seeded trace builder. Same seed and same calls give identical pings. */
class TraceBuilder(
    seed: Long,
    startLat: Double = SYDNEY_LAT,
    startLng: Double = SYDNEY_LNG,
    startTimeMs: Long,
) {
    private val random = Random(seed)
    private val pings = mutableListOf<Ping>()
    private var lat = startLat
    private var lng = startLng
    private var timeMs = startTimeMs
    private var sessionId: Long = 1

    val currentTimeMs: Long get() = timeMs
    val currentLat: Double get() = lat
    val currentLng: Double get() = lng

    fun session(id: Long) = apply { sessionId = id }

    fun stay(
        durationMs: Long,
        intervalMs: Long = 60_000,
        jitterM: Double = 10.0,
        accuracyM: Float = 20f,
        activity: Activity = Activity.STILL,
    ) = apply {
        require(intervalMs > 0 && durationMs >= 0)
        var t = 0L
        while (t < durationMs) {
            val angle = random.nextDouble() * 360.0
            val radius = random.nextDouble() * jitterM
            val (pLat, pLng) = destination(lat, lng, angle, radius)
            add(timeMs + t, pLat, pLng, noisyAccuracy(accuracyM), activity)
            t += intervalMs
        }
        timeMs += durationMs
    }

    fun move(
        toLat: Double,
        toLng: Double,
        speedMps: Double,
        intervalMs: Long = 20_000,
        mode: Activity = Activity.IN_VEHICLE,
        accuracyM: Float = 25f,
    ) = apply {
        require(speedMps > 0 && intervalMs > 0)
        val distance = haversineMeters(lat, lng, toLat, toLng)
        val stepM = speedMps * intervalMs / 1000.0
        val steps = maxOf(1, (distance / stepM).roundToInt())
        for (k in 1..steps) {
            val f = k.toDouble() / steps
            val pLat = if (k == steps) toLat else lat + (toLat - lat) * f
            val pLng = if (k == steps) toLng else lng + (toLng - lng) * f
            add(timeMs + k * intervalMs, pLat, pLng, accuracyM, mode)
        }
        timeMs += steps * intervalMs
        lat = toLat
        lng = toLng
    }

    fun moveBy(
        bearingDeg: Double,
        distanceM: Double,
        speedMps: Double,
        intervalMs: Long = 20_000,
        mode: Activity = Activity.IN_VEHICLE,
        accuracyM: Float = 25f,
    ) = apply {
        val (toLat, toLng) = destination(lat, lng, bearingDeg, distanceM)
        move(toLat, toLng, speedMps, intervalMs, mode, accuracyM)
    }

    /** Advances time with no pings. */
    fun gap(durationMs: Long) = apply { timeMs += durationMs }

    /**
     * Low-accuracy outliers offset [offsetM] from the current point in random directions. Each spike is
     * 30 s after the previous one and advances the cursor, so spikes never share a timestamp with other pings.
     */
    fun spikes(count: Int, accuracyM: Float = 100f, offsetM: Double = 150.0) = apply {
        repeat(count) {
            val bearing = random.nextDouble() * 360.0
            val (pLat, pLng) = destination(lat, lng, bearing, offsetM)
            add(timeMs, pLat, pLng, accuracyM, Activity.STILL)
            timeMs += SPIKE_INTERVAL_MS
        }
    }

    fun build(): Trace = pings.sortedBy { it.timeMs }

    private fun add(t: Long, pLat: Double, pLng: Double, acc: Float, activity: Activity?) {
        pings += Ping(t, pLat, pLng, acc, activity, sessionId)
    }

    private fun noisyAccuracy(base: Float): Float = base * (1f + (random.nextFloat() * 2f - 1f) * 0.25f)

    private fun destination(fromLat: Double, fromLng: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(fromLat)
        val lambda1 = Math.toRadians(fromLng)
        val phi2 = asin(sin(phi1) * cos(d) + cos(phi1) * sin(d) * cos(b))
        val lambda2 = lambda1 + atan2(sin(b) * sin(d) * cos(phi1), cos(d) - sin(phi1) * sin(phi2))
        return Math.toDegrees(phi2) to Math.toDegrees(lambda2)
    }

    private companion object {
        const val SPIKE_INTERVAL_MS = 30_000L
    }
}
