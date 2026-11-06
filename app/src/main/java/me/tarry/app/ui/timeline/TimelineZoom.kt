package me.tarry.app.ui.timeline

/** Tunables for the timeline zoom. */
object TimelineZoomConfig {
    const val MinFactor = 1f
    const val MaxFactor = 3f

    /** Below this factor the minor ticks are 15 min apart. */
    const val TenMinuteFrom = 1.5f

    /** From this factor the minor ticks are 5 min apart. */
    const val FiveMinuteFrom = 2.5f
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
