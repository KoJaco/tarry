package me.tarry.app.ui.drawer

/**
 * What the edit drawer opens on. The timeline produces these (block taps, the gap sheet, the "+" button); the drawer
 * consumes them. Times are UTC epoch ms.
 */
sealed interface EditTarget {
    data class Visit(val id: String) : EditTarget

    data class Trip(val id: String) : EditTarget

    /** Create mode: a manual visit over `[start, end)`, prefilled from a gap, a trip ([contextTripId]) or a free slot. */
    data class NewVisit(val start: Long, val end: Long, val contextTripId: String? = null) : EditTarget

    /** A job block: edit mode. */
    data class Job(val id: String) : EditTarget

    /** Create mode: a job block over `[start, end)`, optionally with [jobId] preselected. */
    data class NewJob(val start: Long, val end: Long, val jobId: String? = null) : EditTarget
}
