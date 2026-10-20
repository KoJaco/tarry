package me.tarry.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.tarry.app.tracking.IdleTrackingStateRepository
import me.tarry.app.tracking.TrackingConfig
import me.tarry.app.tracking.TrackingStateRepository
import me.tarry.app.tracking.TrackingStatusDataStore
import me.tarry.app.tracking.createTrackingStatusDataStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindTrackingStateRepository(impl: IdleTrackingStateRepository): TrackingStateRepository

    companion object {
        @Provides
        @Singleton
        fun provideTrackingConfig(): TrackingConfig = TrackingConfig()

        @Provides
        @Singleton
        @TrackingStatusDataStore
        fun provideTrackingStatusDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            createTrackingStatusDataStore(CoroutineScope(Dispatchers.IO + SupervisorJob())) {
                context.preferencesDataStoreFile("tracking_status")
            }
    }
}
