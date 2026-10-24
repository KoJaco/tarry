package me.tarry.segmentation

/**
 * Pure segmentation types. `:app` maps Room entities to and from these; nothing here knows about
 * Android or `:core-data`. All times are UTC epoch milliseconds.
 */

/** Mirrors `ActivityType` in `:core-data`. */
enum class Activity { STILL, WALKING, IN_VEHICLE, ON_BICYCLE, UNKNOWN }

/** Mirrors `TripMode` in `:core-data`. */
enum class TripMode { VEHICLE, WALK, UNKNOWN }

/** The gap causes segmentation owns. Health-monitor gaps arrive as [SegmentationInput.healthGaps]. */
enum class GapCause { NO_SESSION, NO_FIX }

/** Half-open interval `[startMs, endMs)`. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "endMs ($endMs) < startMs ($startMs)" }
    }

    val durationMs: Long get() = endMs - startMs

    fun overlaps(other: TimeRange): Boolean = startMs < other.endMs && other.startMs < endMs

    operator fun contains(timeMs: Long): Boolean = timeMs >= startMs && timeMs < endMs
}

/**
 * One accepted location ping. [sessionId] is null only for pings that predate sessions. [speedMps] is the speed the
 * fix reported (GPS), null when the provider gave none.
 */
data class Ping(
    val timeMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val activity: Activity? = null,
    val sessionId: Long? = null,
    val speedMps: Double? = null,
)

/**
 * An existing, non-deleted place. Merged places are passed so the caller can see they never match.
 * [weightMs] is the total duration of the place's visits *outside* the run's range; it weights the current
 * centroid when the run moves it ([PlaceCentroidUpdate]).
 */
data class KnownPlace(
    val id: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Float,
    val weightMs: Long = 0,
    val merged: Boolean = false,
    val ignored: Boolean = false,
)

/**
 * Everything one run needs. [range] is the time being (re)segmented: output never extends beyond it.
 * [frozen] holds the ranges of frozen visits and trips (fixed boundaries, never overlapped or changed).
 * [excluded] holds deleted-visit ranges whose pings are ignored. [windows] are the effective scheduled
 * windows and [sessions] the capture sessions, both clipped or not to [range]. [healthGaps] are gaps the
 * health monitor already recorded; segmentation never emits a gap overlapping one.
 */
data class SegmentationInput(
    val range: TimeRange,
    val pings: List<Ping>,
    val frozen: List<TimeRange> = emptyList(),
    val excluded: List<TimeRange> = emptyList(),
    val places: List<KnownPlace> = emptyList(),
    val windows: List<TimeRange> = emptyList(),
    val sessions: List<TimeRange> = emptyList(),
    val healthGaps: List<TimeRange> = emptyList(),
)

/** Where a stay was assigned: an existing place, or the [index] into [SegmentationResult.newPlaces]. */
sealed interface PlaceRef {
    data class Existing(val id: String) : PlaceRef
    data class New(val index: Int) : PlaceRef
}

/**
 * A detected stay (a visit once stored). [confidence] is in 0..1 and lower when [bridged] across a signal gap.
 * [mayBeOngoing] is true when the stay runs to the last ping of the run, so the next run must re-evaluate it.
 */
data class Stay(
    val range: TimeRange,
    val lat: Double,
    val lng: Double,
    val place: PlaceRef,
    val confidence: Float,
    val bridged: Boolean,
    val mayBeOngoing: Boolean = false,
)

/** Movement between two stays. [fromStay]/[toStay] index into [SegmentationResult.stays]; null at a run edge or a frozen boundary. */
data class Trip(
    val range: TimeRange,
    val fromStay: Int?,
    val toStay: Int?,
    val distanceM: Double,
    val mode: TripMode,
)

data class Gap(val range: TimeRange, val cause: GapCause)

/** A place to create, at the duration-weighted centroid of the stays assigned to it, with the default radius. */
data class NewPlace(val lat: Double, val lng: Double, val radiusM: Double)

/** A new centroid for an existing place, as a duration-weighted mean including its new stays. */
data class PlaceCentroidUpdate(val placeId: String, val lat: Double, val lng: Double)

/** The output of one run, in time order within each list. */
data class SegmentationResult(
    val stays: List<Stay>,
    val trips: List<Trip>,
    val gaps: List<Gap>,
    val newPlaces: List<NewPlace>,
    val placeUpdates: List<PlaceCentroidUpdate>,
)
