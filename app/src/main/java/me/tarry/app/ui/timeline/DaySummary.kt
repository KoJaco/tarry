package me.tarry.app.ui.timeline

/** Totals for the summary card. Everything is clamped to the day. */
data class DaySummary(
    /** Sum of visit durations inside the day. */
    val trackedMs: Long,
    val visits: Int,
    val trips: Int,
    val gaps: Int,
    /** Visits with no job. */
    val needsReview: Int,
)

fun summarize(items: List<TimelineItem>, dayBounds: Pair<Long, Long>): DaySummary {
    val (dayStart, dayEnd) = dayBounds
    var tracked = 0L
    var visits = 0
    var trips = 0
    var gaps = 0
    var needsReview = 0
    for (item in items) {
        val ms = minOf(item.end, dayEnd) - maxOf(item.start, dayStart)
        if (ms <= 0) continue
        when (item) {
            is TimelineItem.Visit -> {
                tracked += ms
                visits++
                if (item.jobName == null) needsReview++
            }
            is TimelineItem.Trip -> trips++
            is TimelineItem.Gap -> gaps++
        }
    }
    return DaySummary(tracked, visits, trips, gaps, needsReview)
}
