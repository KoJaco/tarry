package me.tarry.app.tracking

import me.tarry.core.data.model.Schedule
import java.time.LocalDate

/** A schedule save handed to [TrackingController.updateSchedule]. Week starts are the Monday of the week. */
sealed interface ScheduleChange {
    /** Replaces the every-week schedule. */
    data class SaveTemplate(val schedule: Schedule) : ScheduleChange

    /** Replaces the schedule of one week only. */
    data class SaveWeek(val weekStart: LocalDate, val schedule: Schedule) : ScheduleChange

    /** Drops one week's own schedule, so the every-week schedule applies again. */
    data class ClearWeek(val weekStart: LocalDate) : ScheduleChange
}
