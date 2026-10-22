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

    /** Number of pings added so far; the index of the next ping. */
    val pingCount: Int get() = pings.size

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

    /**
     * Noisy fixes scattered within [radiusM] of the cursor (the cursor does not move), each with an accuracy drawn
     * from [accuracyM]. Models walking about, or a bad-signal stretch, near one point.
     */
    fun wander(
        durationMs: Long,
        intervalMs: Long = 20_000,
        radiusM: Double,
        accuracyM: ClosedFloatingPointRange<Float> = 30f..98f,
        activity: Activity? = Activity.WALKING,
    ) = apply {
        require(intervalMs > 0 && durationMs >= 0)
        var t = 0L
        while (t < durationMs) {
            val (pLat, pLng) = destination(lat, lng, random.nextDouble() * 360.0, random.nextDouble() * radiusM)
            add(timeMs + t, pLat, pLng, drawAccuracy(accuracyM), activity)
            t += intervalMs
        }
        timeMs += durationMs
    }

    /**
     * Fixes around the cursor within [jitterM], except every [hopEvery]th, which hops [hopMinM]..[hopMaxM] away
     * (never two hops in a row). Models a boat on the water: a stay with large, isolated position errors.
     */
    fun hops(
        durationMs: Long,
        intervalMs: Long = 20_000,
        jitterM: Double = 30.0,
        hopMinM: Double = 100.0,
        hopMaxM: Double = 270.0,
        hopEvery: Int = 5,
        accuracyM: ClosedFloatingPointRange<Float> = 30f..98f,
        activity: Activity? = Activity.STILL,
    ) = apply {
        require(intervalMs > 0 && durationMs >= 0 && hopEvery >= 2)
        var t = 0L
        var n = 0
        while (t < durationMs) {
            n++
            val distance = if (n % hopEvery == 0) hopMinM + random.nextDouble() * (hopMaxM - hopMinM)
            else random.nextDouble() * jitterM
            val (pLat, pLng) = destination(lat, lng, random.nextDouble() * 360.0, distance)
            add(timeMs + t, pLat, pLng, drawAccuracy(accuracyM), activity)
            t += intervalMs
        }
        timeMs += durationMs
    }

    /**
     * Stale cached fixes: [count] pings carrying the exact lat/lng of the earlier ping at [pingIndex] (index into
     * the pings added so far), [intervalMs] apart, at [accuracyM].
     */
    fun repeatEarlier(pingIndex: Int, count: Int, intervalMs: Long = 60_000, accuracyM: Float = 100f) = apply {
        val source = pings[pingIndex]
        repeat(count) { k -> add(timeMs + k * intervalMs, source.lat, source.lng, accuracyM, Activity.STILL) }
        timeMs += count * intervalMs
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

    private fun drawAccuracy(range: ClosedFloatingPointRange<Float>): Float =
        range.start + random.nextFloat() * (range.endInclusive - range.start)

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
