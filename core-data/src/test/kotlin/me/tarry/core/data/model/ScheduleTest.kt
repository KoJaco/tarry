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
    fun preset_hasExpectedActiveSlotCount() {
        val count = DayOfWeek.entries.sumOf { d -> (0 until 48).count { Schedule.MON_FRI_7_TO_17.isActive(d, it) } }
        assertThat(count).isEqualTo(5 * 20)
    }
}
