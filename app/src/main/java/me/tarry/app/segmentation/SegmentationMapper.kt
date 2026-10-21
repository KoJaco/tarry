package me.tarry.app.segmentation

import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.TripMode
import me.tarry.segmentation.Activity
import me.tarry.segmentation.GapCause as SegGapCause
import me.tarry.segmentation.TripMode as SegTripMode

/** Entity enums to and from the pure `:segmentation` enums. Names match one to one except where noted. */

fun ActivityType.toSegmentation(): Activity = when (this) {
    ActivityType.STILL -> Activity.STILL
    ActivityType.WALKING -> Activity.WALKING
    ActivityType.IN_VEHICLE -> Activity.IN_VEHICLE
    ActivityType.ON_BICYCLE -> Activity.ON_BICYCLE
    ActivityType.UNKNOWN -> Activity.UNKNOWN
}

fun TripMode.toSegmentation(): SegTripMode = when (this) {
    TripMode.VEHICLE -> SegTripMode.VEHICLE
    TripMode.WALK -> SegTripMode.WALK
    TripMode.UNKNOWN -> SegTripMode.UNKNOWN
}

fun SegTripMode.toEntity(): TripMode = when (this) {
    SegTripMode.VEHICLE -> TripMode.VEHICLE
    SegTripMode.WALK -> TripMode.WALK
    SegTripMode.UNKNOWN -> TripMode.UNKNOWN
}

/** The causes segmentation owns; every other cause belongs to the health monitor and maps to null. */
fun GapCause.toSegmentationOrNull(): SegGapCause? = when (this) {
    GapCause.NO_SESSION -> SegGapCause.NO_SESSION
    GapCause.NO_FIX -> SegGapCause.NO_FIX
    GapCause.TRACKING_STOPPED, GapCause.SERVICE_KILLED, GapCause.PERMISSION_LOST, GapCause.LOCATION_OFF -> null
}

fun SegGapCause.toEntity(): GapCause = when (this) {
    SegGapCause.NO_SESSION -> GapCause.NO_SESSION
    SegGapCause.NO_FIX -> GapCause.NO_FIX
}
