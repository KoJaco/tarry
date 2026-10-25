package me.tarry.app.tracking

import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The "edits count from save" marker: a schedule saved at [atMs] only applies from then on, so slots painted
 * for earlier today never count as scheduled (no Gap for the past).
 *
 * @property weekStart Monday of the week containing [atMs].
 * @property before that week's effective bitmap (override, else template) as it really was in force before the save.
 */
data class ScheduleEdit(val atMs: Long, val weekStart: LocalDate, val before: Schedule) {
    companion object {
        /**
         * The marker for a save at [atMs]. [effectiveBefore] is the week's effective bitmap just before the save.
         * If [prev] is for the same week, slots whose wall-clock end is at or before `prev.atMs` keep
         * `prev.before` (that part of the week was already protected), so repeated saves lose nothing.
         */
        fun record(prev: ScheduleEdit?, effectiveBefore: Schedule, weekStart: LocalDate, atMs: Long, zone: ZoneId): ScheduleEdit {
            if (prev == null || prev.weekStart != weekStart) return ScheduleEdit(atMs, weekStart, effectiveBefore)
            var merged = effectiveBefore
            for (day in DayOfWeek.entries) {
                val date = weekStart.plusDays(day.ordinal.toLong())
                for (slot in 0 until Schedule.SLOTS_PER_DAY) {
                    if (slotInstant(date, slot + 1, zone).toEpochMilli() <= prev.atMs) {
                        merged = merged.set(day, slot, prev.before.isActive(day, slot))
                    }
                }
            }
            return ScheduleEdit(atMs, weekStart, merged)
        }
    }
}

/** Start of half-hour [slot] on [date] (slot 48 is the next midnight), in wall-clock [zone] semantics. */
internal fun slotInstant(date: LocalDate, slot: Int, zone: ZoneId): Instant =
    if (slot >= Schedule.SLOTS_PER_DAY) {
        date.plusDays(1).atStartOfDay(zone).toInstant()
    } else {
        date.atTime(slot / 2, (slot % 2) * 30).atZone(zone).toInstant()
    }
