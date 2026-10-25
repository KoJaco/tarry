package me.tarry.app.ui.schedule

import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek

/** What a [Schedule] looks like at a glance, for the Settings row subtitle. Not localized; see [formatUniform]. */
sealed interface ScheduleSummary {
    /** No active slot. */
    data object Off : ScheduleSummary

    /** Every active day has the same single contiguous range [startSlot] until [endSlotExclusive] (48 = midnight). */
    data class Uniform(val days: List<DayOfWeek>, val startSlot: Int, val endSlotExclusive: Int) : ScheduleSummary

    /** Anything else. */
    data object Custom : ScheduleSummary
}

fun summarize(schedule: Schedule): ScheduleSummary {
    val ranges = DayOfWeek.entries.mapNotNull { day ->
        val slots = schedule.daySlots(day)
        val first = slots.indexOfFirst { it }
        if (first < 0) return@mapNotNull null
        val last = slots.indexOfLast { it }
        // A hole between first and last means more than one range on that day.
        if (slots.subList(first, last + 1).any { !it }) return ScheduleSummary.Custom
        Triple(day, first, last + 1)
    }
    if (ranges.isEmpty()) return ScheduleSummary.Off
    val (_, start, end) = ranges.first()
    if (ranges.any { it.second != start || it.third != end }) return ScheduleSummary.Custom
    return ScheduleSummary.Uniform(ranges.map { it.first }, start, end)
}

/**
 * "Mon–Fri 07:00–17:00". Runs of two or more consecutive days collapse to "first–last"; other days are
 * comma separated ("Mon, Wed–Fri"). [time] receives a slot index 0..48.
 */
fun formatUniform(summary: ScheduleSummary.Uniform, dayName: (DayOfWeek) -> String, time: (Int) -> String): String {
    val runs = ArrayList<List<DayOfWeek>>()
    for (day in summary.days) {
        val tail = runs.lastOrNull()
        if (tail != null && tail.last().ordinal + 1 == day.ordinal) runs[runs.lastIndex] = tail + day else runs += listOf(day)
    }
    val days = runs.joinToString(", ") { run ->
        if (run.size == 1) dayName(run.first()) else "${dayName(run.first())}–${dayName(run.last())}"
    }
    return "$days ${time(summary.startSlot)}–${time(summary.endSlotExclusive)}"
}

/** This week's effective schedule at a glance; [hasOverride] when the week has its own schedule. */
data class WeekScheduleSummary(val summary: ScheduleSummary, val hasOverride: Boolean)
