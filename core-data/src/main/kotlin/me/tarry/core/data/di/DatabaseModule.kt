package me.tarry.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.tarry.core.data.db.ALL_MIGRATIONS
import me.tarry.core.data.db.BackupStore
import me.tarry.core.data.db.SegmentationStore
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.repository.EditClock
import me.tarry.core.data.repository.RetentionFloor
import me.tarry.core.data.repository.RewindFloor
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TarryDatabase =
        Room.databaseBuilder(context, TarryDatabase::class.java, "tarry.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun segmentationStore(db: TarryDatabase) = SegmentationStore(db)
    @Provides fun backupStore(db: TarryDatabase) = BackupStore(db)
    @Provides fun editClock(): EditClock = EditClock { System.currentTimeMillis() }
    @Provides fun rewindFloor(settings: SettingsRepository): RewindFloor = RewindFloor {
        RetentionFloor.compute(settings, System.currentTimeMillis())
    }
    @Provides fun trackingSessionDao(db: TarryDatabase) = db.trackingSessionDao()
    @Provides fun locationPingDao(db: TarryDatabase) = db.locationPingDao()
    @Provides fun manualOverrideDao(db: TarryDatabase) = db.manualOverrideDao()
    @Provides fun segmentationCheckpointDao(db: TarryDatabase) = db.segmentationCheckpointDao()
    @Provides fun jobDao(db: TarryDatabase) = db.jobDao()
    @Provides fun jobBlockDao(db: TarryDatabase) = db.jobBlockDao()
    @Provides fun placeDao(db: TarryDatabase) = db.placeDao()
    @Provides fun visitDao(db: TarryDatabase) = db.visitDao()
    @Provides fun tripDao(db: TarryDatabase) = db.tripDao()
    @Provides fun gapDao(db: TarryDatabase) = db.gapDao()
    @Provides fun excludedRangeDao(db: TarryDatabase) = db.excludedRangeDao()
    @Provides fun scheduleDao(db: TarryDatabase) = db.scheduleDao()
}
