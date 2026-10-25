package me.tarry.app.ui.schedule

import me.tarry.core.data.model.Schedule
import java.time.DayOfWeek
import kotlin.math.abs
import kotlin.math.roundToInt

/** Flips one cell; the tap gesture. */
fun toggle(schedule: Schedule, day: DayOfWeek, slot: Int): Schedule =
    schedule.set(day, slot, !schedule.isActive(day, slot))

/**
 * One long-press-drag over the schedule grid. The first cell decides the mode (paint if it was off,
 * erase if it was on); every cell the drag passes through then gets that mode. Pure, no Compose.
 */
class PaintStroke private constructor(
    schedule: Schedule,
    /** True paints cells on, false erases them. */
    val paints: Boolean,
    private var dayIndex: Int,
    private var slot: Int,
) {
    /** The schedule with the stroke applied so far. */
    var schedule: Schedule = schedule
        private set

    /**
     * Applies the mode to every cell on the straight line (in day, slot space) from the previous cell to
     * ([day], [slot]), so a fast drag never skips cells, within a column or across columns.
     */
    fun moveTo(day: DayOfWeek, slot: Int) {
        val toDay = day.ordinal
        val steps = maxOf(abs(toDay - dayIndex), abs(slot - this.slot))
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val d = (dayIndex + (toDay - dayIndex) * t).roundToInt()
            val s = (this.slot + (slot - this.slot) * t).roundToInt()
            schedule = schedule.set(DayOfWeek.entries[d], s, paints)
        }
        dayIndex = toDay
        this.slot = slot
    }

    companion object {
        fun begin(schedule: Schedule, day: DayOfWeek, slot: Int): PaintStroke {
            val paints = !schedule.isActive(day, slot)
            return PaintStroke(schedule.set(day, slot, paints), paints, day.ordinal, slot)
        }
    }
}
