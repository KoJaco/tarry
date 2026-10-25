package me.tarry.app.ui.schedule

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.model.Schedule
import org.junit.Test
import java.time.DayOfWeek

class PaintStrokeTest {
    private fun Schedule.slots(day: DayOfWeek) = daySlots(day).withIndex().filter { it.value }.map { it.index }

    @Test
    fun beginOnOffCell_paintsIt() {
        val stroke = PaintStroke.begin(Schedule.EMPTY, DayOfWeek.MONDAY, 10)
        assertThat(stroke.paints).isTrue()
        assertThat(stroke.schedule.slots(DayOfWeek.MONDAY)).containsExactly(10)
    }

    @Test
    fun beginOnOnCell_erasesIt_andDragErases() {
        val start = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 10..20, true)
        val stroke = PaintStroke.begin(start, DayOfWeek.MONDAY, 12)
        assertThat(stroke.paints).isFalse()
        stroke.moveTo(DayOfWeek.MONDAY, 15)
        assertThat(stroke.schedule.slots(DayOfWeek.MONDAY)).containsExactly(10, 11, 16, 17, 18, 19, 20)
    }

    @Test
    fun fastDrag_interpolatesWithinColumn_forwardAndBackward() {
        val stroke = PaintStroke.begin(Schedule.EMPTY, DayOfWeek.TUESDAY, 10)
        stroke.moveTo(DayOfWeek.TUESDAY, 14)
        assertThat(stroke.schedule.slots(DayOfWeek.TUESDAY)).containsExactly(10, 11, 12, 13, 14)
        stroke.moveTo(DayOfWeek.TUESDAY, 8)
        assertThat(stroke.schedule.slots(DayOfWeek.TUESDAY)).containsExactly(8, 9, 10, 11, 12, 13, 14)
    }

    @Test
    fun dragAcrossColumns_fillsAStraightLine() {
        val stroke = PaintStroke.begin(Schedule.EMPTY, DayOfWeek.MONDAY, 10)
        stroke.moveTo(DayOfWeek.WEDNESDAY, 10)
        assertThat(stroke.schedule.slots(DayOfWeek.MONDAY)).containsExactly(10)
        assertThat(stroke.schedule.slots(DayOfWeek.TUESDAY)).containsExactly(10)
        assertThat(stroke.schedule.slots(DayOfWeek.WEDNESDAY)).containsExactly(10)
    }

    @Test
    fun diagonalDrag_hasNoGaps() {
        val stroke = PaintStroke.begin(Schedule.EMPTY, DayOfWeek.MONDAY, 0)
        stroke.moveTo(DayOfWeek.TUESDAY, 10)
        val cells = DayOfWeek.entries.flatMap { d -> stroke.schedule.slots(d).map { d.ordinal to it } }
        assertThat(cells).hasSize(11)
        assertThat(cells.first()).isEqualTo(0 to 0)
        assertThat(cells.last()).isEqualTo(1 to 10)
        // Consecutive painted cells are neighbours (no skipped slot rows).
        assertThat(cells.map { it.second }.zipWithNext { a, b -> b - a }.all { it in 0..1 }).isTrue()
    }

    @Test
    fun moveToSameCell_changesNothing() {
        val stroke = PaintStroke.begin(Schedule.EMPTY, DayOfWeek.FRIDAY, 5)
        stroke.moveTo(DayOfWeek.FRIDAY, 5)
        assertThat(stroke.schedule.slots(DayOfWeek.FRIDAY)).containsExactly(5)
    }

    @Test
    fun toggle_flipsAndFlipsBack() {
        val on = toggle(Schedule.EMPTY, DayOfWeek.SUNDAY, 47)
        assertThat(on.isActive(DayOfWeek.SUNDAY, 47)).isTrue()
        assertThat(toggle(on, DayOfWeek.SUNDAY, 47)).isEqualTo(Schedule.EMPTY)
    }
}
