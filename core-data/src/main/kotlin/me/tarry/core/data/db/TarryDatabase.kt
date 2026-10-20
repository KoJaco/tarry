package me.tarry.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import me.tarry.core.data.db.dao.ExcludedRangeDao
import me.tarry.core.data.db.dao.GapDao
import me.tarry.core.data.db.dao.JobDao
import me.tarry.core.data.db.dao.LocationPingDao
import me.tarry.core.data.db.dao.ManualOverrideDao
import me.tarry.core.data.db.dao.PlaceDao
import me.tarry.core.data.db.dao.ScheduleDao
import me.tarry.core.data.db.dao.SegmentationCheckpointDao
import me.tarry.core.data.db.dao.TagDao
import me.tarry.core.data.db.dao.TrackingSessionDao
import me.tarry.core.data.db.dao.TripDao
import me.tarry.core.data.db.dao.VisitDao
import me.tarry.core.data.db.entity.ExcludedRange
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.PlaceDefaultTag
import me.tarry.core.data.db.entity.ScheduleOverride
import me.tarry.core.data.db.entity.ScheduleTemplate
import me.tarry.core.data.db.entity.SegmentationCheckpoint
import me.tarry.core.data.db.entity.Tag
import me.tarry.core.data.db.entity.TagGroup
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.db.entity.Trip
import me.tarry.core.data.db.entity.TripTag
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.db.entity.VisitTag

@Database(
    entities = [
        TrackingSession::class, LocationPing::class, Job::class, Place::class,
        PlaceDefaultTag::class, Visit::class, Trip::class, TagGroup::class, Tag::class,
        VisitTag::class, TripTag::class, ExcludedRange::class, Gap::class,
        ScheduleTemplate::class, ScheduleOverride::class, ManualOverride::class,
        SegmentationCheckpoint::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TarryDatabase : RoomDatabase() {
    abstract fun trackingSessionDao(): TrackingSessionDao
    abstract fun locationPingDao(): LocationPingDao
    abstract fun manualOverrideDao(): ManualOverrideDao
    abstract fun segmentationCheckpointDao(): SegmentationCheckpointDao
    abstract fun jobDao(): JobDao
    abstract fun placeDao(): PlaceDao
    abstract fun tagDao(): TagDao
    abstract fun visitDao(): VisitDao
    abstract fun tripDao(): TripDao
    abstract fun gapDao(): GapDao
    abstract fun excludedRangeDao(): ExcludedRangeDao
    abstract fun scheduleDao(): ScheduleDao
}
