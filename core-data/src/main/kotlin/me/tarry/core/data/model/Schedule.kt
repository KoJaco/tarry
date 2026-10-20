package me.tarry.core.data.model

import java.time.DayOfWeek

/**
 * Immutable weekly schedule: 7 days x 48 half-hour slots = 336 bits (42 bytes).
 * Slot index = dayIndex * 48 + slotOfDay (Monday = 0); bit i lives in byte i/8, LSB first.
 */
class Schedule private constructor(private val bits: ByteArray) {

    fun isActive(dayOfWeek: DayOfWeek, slot: Int): Boolean {
        val i = index(dayOfWeek, slot)
        return (bits[i / 8].toInt() shr (i % 8)) and 1 == 1
    }

    fun set(dayOfWeek: DayOfWeek, slot: Int, active: Boolean): Schedule {
        val i = index(dayOfWeek, slot)
        val copy = bits.copyOf()
        val mask = 1 shl (i % 8)
        val old = copy[i / 8].toInt()
        copy[i / 8] = (if (active) old or mask else old and mask.inv()).toByte()
        return Schedule(copy)
    }

    fun toBytes(): ByteArray = bits.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Schedule && bits.contentEquals(other.bits))

    override fun hashCode(): Int = bits.contentHashCode()

    override fun toString(): String = "Schedule(${bits.joinToString("") { "%02x".format(it) }})"

    companion object {
        const val SLOTS_PER_DAY = 48
        const val BYTE_SIZE = 42

        val EMPTY: Schedule = Schedule(ByteArray(BYTE_SIZE))

        /** Monday to Friday, 07:00 (slot 14) to 17:00 (slot 33 is the last active; 17:00 is inactive). */
        val MON_FRI_7_TO_17: Schedule = run {
            var s = EMPTY
            for (d in DayOfWeek.MONDAY..DayOfWeek.FRIDAY) {
                for (slot in 14..33) s = s.set(d, slot, true)
            }
            s
        }

        fun fromBytes(bytes: ByteArray): Schedule {
            require(bytes.size == BYTE_SIZE) { "Schedule needs $BYTE_SIZE bytes, got ${bytes.size}" }
            return Schedule(bytes.copyOf())
        }

        private operator fun DayOfWeek.rangeTo(other: DayOfWeek): List<DayOfWeek> =
            (ordinal..other.ordinal).map { DayOfWeek.entries[it] }

        private fun index(dayOfWeek: DayOfWeek, slot: Int): Int {
            require(slot in 0 until SLOTS_PER_DAY) { "slot must be in 0..47, got $slot" }
            return dayOfWeek.ordinal * SLOTS_PER_DAY + slot
        }
    }
}
