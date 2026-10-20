package me.tarry.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedule_template")
data class ScheduleTemplate(
    @PrimaryKey val id: Int = 0,
    val slots: ByteArray,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ScheduleTemplate && id == other.id &&
            updatedAt == other.updatedAt && slots.contentEquals(other.slots))

    override fun hashCode(): Int =
        31 * (31 * id + updatedAt.hashCode()) + slots.contentHashCode()
}

@Entity(tableName = "schedule_override")
data class ScheduleOverride(
    @PrimaryKey val weekStart: String,
    val slots: ByteArray,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ScheduleOverride && weekStart == other.weekStart &&
            updatedAt == other.updatedAt && slots.contentEquals(other.slots))

    override fun hashCode(): Int =
        31 * (31 * weekStart.hashCode() + updatedAt.hashCode()) + slots.contentHashCode()
}
