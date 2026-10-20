package me.tarry.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.tarry.core.data.db.ALL_MIGRATIONS
import me.tarry.core.data.db.TarryDatabase
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

    @Provides fun trackingSessionDao(db: TarryDatabase) = db.trackingSessionDao()
    @Provides fun locationPingDao(db: TarryDatabase) = db.locationPingDao()
    @Provides fun manualOverrideDao(db: TarryDatabase) = db.manualOverrideDao()
    @Provides fun segmentationCheckpointDao(db: TarryDatabase) = db.segmentationCheckpointDao()
    @Provides fun jobDao(db: TarryDatabase) = db.jobDao()
    @Provides fun placeDao(db: TarryDatabase) = db.placeDao()
    @Provides fun tagDao(db: TarryDatabase) = db.tagDao()
    @Provides fun visitDao(db: TarryDatabase) = db.visitDao()
    @Provides fun tripDao(db: TarryDatabase) = db.tripDao()
    @Provides fun gapDao(db: TarryDatabase) = db.gapDao()
    @Provides fun excludedRangeDao(db: TarryDatabase) = db.excludedRangeDao()
    @Provides fun scheduleDao(db: TarryDatabase) = db.scheduleDao()
}
