package me.tarry.core.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DayOfWeek

class ScheduleTest {
    @Test
    fun empty_isInactiveEverywhere() {
        for (d in DayOfWeek.entries) for (slot in 0 until 48) {
            assertThat(Schedule.EMPTY.isActive(d, slot)).isFalse()
        }
    }

    @Test
    fun set_thenIsActive_onlyAffectsThatSlot() {
        val s = Schedule.EMPTY.set(DayOfWeek.WEDNESDAY, 10, true)
        assertThat(s.isActive(DayOfWeek.WEDNESDAY, 10)).isTrue()
        assertThat(s.isActive(DayOfWeek.WEDNESDAY, 9)).isFalse()
        assertThat(s.isActive(DayOfWeek.WEDNESDAY, 11)).isFalse()
        assertThat(s.isActive(DayOfWeek.TUESDAY, 10)).isFalse()
        assertThat(s.set(DayOfWeek.WEDNESDAY, 10, false).isActive(DayOfWeek.WEDNESDAY, 10)).isFalse()
    }

    @Test
    fun everyBitCanBeSetIndependently() {
        for (d in DayOfWeek.entries) for (slot in 0 until 48) {
            val s = Schedule.EMPTY.set(d, slot, true)
            val active = DayOfWeek.entries.flatMap { dd -> (0 until 48).map { dd to it } }
                .filter { (dd, ss) -> s.isActive(dd, ss) }
            assertThat(active).containsExactly(d to slot)
        }
    }

    @Test
    fun set_returnsNewInstance_originalUnchanged() {
        val original = Schedule.EMPTY
        val changed = original.set(DayOfWeek.MONDAY, 0, true)
        assertThat(changed).isNotSameInstanceAs(original)
        assertThat(original.isActive(DayOfWeek.MONDAY, 0)).isFalse()
        assertThat(changed).isNotEqualTo(original)
    }

    @Test
    fun toBytes_returnsDefensiveCopy() {
        val s = Schedule.EMPTY
        s.toBytes()[0] = 0x7F
        assertThat(s.isActive(DayOfWeek.MONDAY, 0)).isFalse()
    }

    @Test
    fun fromBytes_copiesInput() {
        val bytes = ByteArray(Schedule.BYTE_SIZE)
        val s = Schedule.fromBytes(bytes)
        bytes[0] = 1
        assertThat(s.isActive(DayOfWeek.MONDAY, 0)).isFalse()
    }

    @Test
    fun bytesRoundTrip() {
        val s = Schedule.EMPTY
            .set(DayOfWeek.SUNDAY, 47, true)
            .set(DayOfWeek.MONDAY, 0, true)
            .set(DayOfWeek.THURSDAY, 23, true)
        val bytes = s.toBytes()
        assertThat(bytes).hasLength(42)
        val back = Schedule.fromBytes(bytes)
        assertThat(back).isEqualTo(s)
        assertThat(back.hashCode()).isEqualTo(s.hashCode())
        assertThat(Schedule.fromBytes(Schedule.MON_FRI_7_TO_17.toBytes())).isEqualTo(Schedule.MON_FRI_7_TO_17)
    }

    @Test
    fun invalidSlot_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { Schedule.EMPTY.isActive(DayOfWeek.MONDAY, -1) }
        assertThrows(IllegalArgumentException::class.java) { Schedule.EMPTY.isActive(DayOfWeek.MONDAY, 48) }
        assertThrows(IllegalArgumentException::class.java) { Schedule.EMPTY.set(DayOfWeek.MONDAY, 48, true) }
        assertThrows(IllegalArgumentException::class.java) { Schedule.EMPTY.set(DayOfWeek.MONDAY, -1, true) }
    }

    @Test
    fun invalidByteSize_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { Schedule.fromBytes(ByteArray(41)) }
        assertThrows(IllegalArgumentException::class.java) { Schedule.fromBytes(ByteArray(43)) }
        assertThrows(IllegalArgumentException::class.java) { Schedule.fromBytes(ByteArray(0)) }
    }

    @Test
    fun preset_weekdayBoundaries() {
        val p = Schedule.MON_FRI_7_TO_17
        assertThat(p.isActive(DayOfWeek.MONDAY, 14)).isTrue() // 07:00
        assertThat(p.isActive(DayOfWeek.MONDAY, 33)).isTrue() // 16:30
        assertThat(p.isActive(DayOfWeek.MONDAY, 34)).isFalse() // 17:00
        assertThat(p.isActive(DayOfWeek.MONDAY, 13)).isFalse() // 06:30
        assertThat(p.isActive(DayOfWeek.FRIDAY, 14)).isTrue()
        assertThat(p.isActive(DayOfWeek.FRIDAY, 34)).isFalse()
    }

    @Test
    fun preset_weekendIsInactive() {
        for (d in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) for (slot in 0 until 48) {
            assertThat(Schedule.MON_FRI_7_TO_17.isActive(d, slot)).isFalse()
        }
    }

    @Test
    fun withSlots_setsAndClearsOnlyTheRangeOnThatDay() {
        val on = Schedule.EMPTY.withSlots(DayOfWeek.TUESDAY, 4..6, true)
        assertThat(on.daySlots(DayOfWeek.TUESDAY).withIndex().filter { it.value }.map { it.index }).containsExactly(4, 5, 6)
        assertThat(on.daySlots(DayOfWeek.MONDAY)).doesNotContain(true)
        val off = on.withSlots(DayOfWeek.TUESDAY, 5..5, false)
        assertThat(off.isActive(DayOfWeek.TUESDAY, 5)).isFalse()
        assertThat(off.isActive(DayOfWeek.TUESDAY, 4)).isTrue()
        assertThat(on.isActive(DayOfWeek.TUESDAY, 5)).isTrue()
    }

    @Test
    fun withSlots_emptyRange_isNoOp_andOutOfRangeIsRejected() {
        assertThat(Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 5..4, true)).isEqualTo(Schedule.EMPTY)
        assertThrows(IllegalArgumentException::class.java) { Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 40..48, true) }
    }

    @Test
    fun copyDay_overwritesTargets_andLeavesOthers() {
        val src = Schedule.EMPTY.withSlots(DayOfWeek.MONDAY, 14..33, true)
            .withSlots(DayOfWeek.TUESDAY, 0..2, true)
        val copied = src.copyDay(DayOfWeek.MONDAY, setOf(DayOfWeek.TUESDAY, DayOfWeek.SUNDAY))
        assertThat(copied.daySlots(DayOfWeek.TUESDAY)).isEqualTo(src.daySlots(DayOfWeek.MONDAY))
        assertThat(copied.daySlots(DayOfWeek.SUNDAY)).isEqualTo(src.daySlots(DayOfWeek.MONDAY))
        assertThat(copied.daySlots(DayOfWeek.WEDNESDAY)).doesNotContain(true)
        assertThat(copied.daySlots(DayOfWeek.MONDAY)).isEqualTo(src.daySlots(DayOfWeek.MONDAY))
    }

    @Test
    fun clearDay_clearsOnlyThatDay() {
        val cleared = Schedule.MON_FRI_7_TO_17.clearDay(DayOfWeek.WEDNESDAY)
        assertThat(cleared.daySlots(DayOfWeek.WEDNESDAY)).doesNotContain(true)
        assertThat(cleared.daySlots(DayOfWeek.THURSDAY)).isEqualTo(Schedule.MON_FRI_7_TO_17.daySlots(DayOfWeek.THURSDAY))
    }

    @Test
    fun daySlots_has48EntriesMatchingIsActive() {
        val slots = Schedule.MON_FRI_7_TO_17.daySlots(DayOfWeek.MONDAY)
        assertThat(slots).hasSize(48)
        assertThat(slots.count { it }).isEqualTo(20)
        assertThat(slots[14]).isTrue()
        assertThat(slots[34]).isFalse()
    }

    @Test
    fun preset_hasExpectedActiveSlotCount() {
        val count = DayOfWeek.entries.sumOf { d -> (0 until 48).count { Schedule.MON_FRI_7_TO_17.isActive(d, it) } }
        assertThat(count).isEqualTo(5 * 20)
    }
}
