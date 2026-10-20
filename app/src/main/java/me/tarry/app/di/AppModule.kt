package me.tarry.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.tarry.app.tracking.IdleTrackingStateRepository
import me.tarry.app.tracking.TrackingStateRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindTrackingStateRepository(impl: IdleTrackingStateRepository): TrackingStateRepository
}
