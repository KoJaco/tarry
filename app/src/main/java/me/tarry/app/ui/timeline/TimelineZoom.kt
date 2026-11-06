package me.tarry.app.ui.timeline

/** Tunables for the timeline zoom. */
object TimelineZoomConfig {
    const val MinFactor = 1f
    const val MaxFactor = 3f

    /** Below this factor the minor ticks are 15 min apart. */
    const val TenMinuteFrom = 1.5f

    /** From this factor the minor ticks are 5 min apart. */
    const val FiveMinuteFrom = 2.5f

    /** At 5-minute ticks only marks this many minutes apart (the quarters) are labelled. */
    const val QuarterLabelEvery = 15
}

/** The three settled zoom levels. [snapMinutes] is the step job-block drags snap to at this level. */
enum class ZoomLevel(val factor: Float, val tickMinutes: Int, val snapMinutes: Int) {
    Q15(1f, 15, 15),
    Q10(2f, 10, 10),
    Q5(3f, 5, 5),
    ;

    companion object {
        /** The level for a stored step in minutes (15, 10 or 5); anything else falls back to [Q15]. */
        fun fromMinutes(minutes: Int): ZoomLevel = entries.firstOrNull { it.snapMinutes == minutes } ?: Q15
    }
}

/** Minutes between minor ticks at a (possibly mid-pinch) zoom [factor]. */
fun tickMinutesFor(factor: Float): Int = when {
    factor < TimelineZoomConfig.TenMinuteFrom -> 15
    factor < TimelineZoomConfig.FiveMinuteFrom -> 10
    else -> 5
}

/** The level whose factor is closest to [factor]; ties go to the lower zoom. */
fun nearestLevel(factor: Float): ZoomLevel =
    ZoomLevel.entries.minBy { kotlin.math.abs(it.factor - factor) }

/**
 * The scroll offset that keeps the time under [focalInContent] (px or dp from the content top, at the old scale) fixed
 * when the zoom changes. The caller clamps the result to the scroll range.
 */
fun anchoredScroll(oldScroll: Int, focalInContent: Float, oldFactor: Float, newFactor: Float): Int =
    (oldScroll + focalInContent * (newFactor / oldFactor - 1f)).let { Math.round(it) }

/** The next level up (finer steps, bigger canvas), or null at the limit. */
fun ZoomLevel.zoomedIn(): ZoomLevel? = ZoomLevel.entries.getOrNull(ordinal + 1)

/** The next level down (coarser steps, smaller canvas), or null at the limit. */
fun ZoomLevel.zoomedOut(): ZoomLevel? = ZoomLevel.entries.getOrNull(ordinal - 1)

/**
 * Where the pinned zoom buttons sit, in px from the canvas top: [inset] below the viewport top while the canvas is on
 * screen, never above the canvas top, never past the point where the buttons ([height]) would leave the canvas bottom.
 * [scroll] is the page scroll offset and [canvasTopInContent] the canvas top in the scrolled content, both in px.
 * [minTop] is the resting position while the canvas top is on screen; a negative value lifts the buttons into the
 * card's top padding (only at rest: once pinned to the viewport they sit [inset] below its top as before).
 */
fun pinnedTop(scroll: Int, canvasTopInContent: Float, inset: Float, canvasHeight: Float, height: Float, minTop: Float = 0f): Float =
    (scroll + inset - canvasTopInContent).coerceAtMost(canvasHeight - height).coerceAtLeast(minTop)

/**
 * Whether the minor mark [minuteOfHour] minutes past the hour carries a time label at the given [tickMinutes] level:
 * every mark at 10-minute ticks (:10 to :50), only the quarters at 5-minute ticks (:15, :30, :45), none at 15.
 */
fun minorMarkLabelled(tickMinutes: Int, minuteOfHour: Int): Boolean = when (tickMinutes) {
    10 -> true
    5 -> minuteOfHour % TimelineZoomConfig.QuarterLabelEvery == 0
    else -> false
}
