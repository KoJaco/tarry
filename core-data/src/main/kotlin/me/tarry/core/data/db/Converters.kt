package me.tarry.core.data.db

import androidx.room.TypeConverter
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin

/** Enums are stored as their name String. */
class Converters {
    @TypeConverter fun fromSessionTrigger(v: SessionTrigger?): String? = v?.name
    @TypeConverter fun toSessionTrigger(v: String?): SessionTrigger? = v?.let(SessionTrigger::valueOf)

    @TypeConverter fun fromSessionEndReason(v: SessionEndReason?): String? = v?.name
    @TypeConverter fun toSessionEndReason(v: String?): SessionEndReason? = v?.let(SessionEndReason::valueOf)

    @TypeConverter fun fromActivityType(v: ActivityType?): String? = v?.name
    @TypeConverter fun toActivityType(v: String?): ActivityType? = v?.let(ActivityType::valueOf)

    @TypeConverter fun fromPingSource(v: PingSource?): String? = v?.name
    @TypeConverter fun toPingSource(v: String?): PingSource? = v?.let(PingSource::valueOf)

    @TypeConverter fun fromVisitOrigin(v: VisitOrigin?): String? = v?.name
    @TypeConverter fun toVisitOrigin(v: String?): VisitOrigin? = v?.let(VisitOrigin::valueOf)

    @TypeConverter fun fromTripMode(v: TripMode?): String? = v?.name
    @TypeConverter fun toTripMode(v: String?): TripMode? = v?.let(TripMode::valueOf)

    @TypeConverter fun fromGapCause(v: GapCause?): String? = v?.name
    @TypeConverter fun toGapCause(v: String?): GapCause? = v?.let(GapCause::valueOf)

    @TypeConverter fun fromManualOverrideType(v: ManualOverrideType?): String? = v?.name
    @TypeConverter fun toManualOverrideType(v: String?): ManualOverrideType? = v?.let(ManualOverrideType::valueOf)

    @TypeConverter fun fromManualOverrideEndMode(v: ManualOverrideEndMode?): String? = v?.name
    @TypeConverter fun toManualOverrideEndMode(v: String?): ManualOverrideEndMode? = v?.let(ManualOverrideEndMode::valueOf)
}
