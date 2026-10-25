package me.tarry.app.tracking

import me.tarry.core.data.model.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The override of [weekStart] (Monday) as it was before a week save or clear; [overrideBefore] null means there was none.
 */
data class WeekBefore(val weekStart: LocalDate, val overrideBefore: Schedule?)

/**
 * One schedule save, kept in a short history so that "an edit counts from the moment it is saved" holds for the whole
 * past, not just the current week: newly painted past slots never become Gaps, removed past slots still count.
 *
 * Every edit records the template as it was before the save ([templateBefore]) even when only a week override changed,
 * plus [weekBefore] when the save touched a week override (SaveWeek or ClearWeek).
 *
 * The schedule in force at an instant is reconstructed from the current schedules and the edits after that instant
 * (see [ScheduleInputs.scheduleAt]). The history is pruned by age and count (`TrackingConfig.scheduleEditRetentionDays`
 * and `scheduleEditMax`); time older than the oldest kept edit is judged by the schedule as it was before that edit
 * (in practice the current schedule once the history has aged out).
 */
data class ScheduleEdit(val atMs: Long, val templateBefore: Schedule, val weekBefore: WeekBefore? = null) {
    companion object {
        /**
         * [history] (ascending) with [edit] appended, dropping edits older than [retentionDays] before [edit] and then
         * the oldest ones beyond [max].
         */
        fun append(history: List<ScheduleEdit>, edit: ScheduleEdit, retentionDays: Long, max: Int): List<ScheduleEdit> {
            val oldest = edit.atMs - retentionDays * MS_PER_DAY
            return (history + edit).filter { it.atMs >= oldest }.sortedBy { it.atMs }.takeLast(max)
        }

        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
    }
}

/** Start of half-hour [slot] on [date] (slot 48 is the next midnight), in wall-clock [zone] semantics. */
internal fun slotInstant(date: LocalDate, slot: Int, zone: ZoneId): Instant =
    if (slot >= Schedule.SLOTS_PER_DAY) {
        date.plusDays(1).atStartOfDay(zone).toInstant()
    } else {
        date.atTime(slot / 2, (slot % 2) * 30).atZone(zone).toInstant()
    }
