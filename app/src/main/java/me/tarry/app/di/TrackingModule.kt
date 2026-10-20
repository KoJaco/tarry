package me.tarry.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import me.tarry.app.tracking.ActivityTransitionClient
import me.tarry.app.tracking.AlwaysInsideWindowChecker
import me.tarry.app.tracking.AndroidLocationPermissionChecker
import me.tarry.app.tracking.AndroidServiceLauncher
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.GeofenceClient
import me.tarry.app.tracking.LocationClient
import me.tarry.app.tracking.LocationPermissionChecker
import me.tarry.app.tracking.PlayActivityTransitionClient
import me.tarry.app.tracking.PlayGeofenceClient
import me.tarry.app.tracking.PlayLocationClient
import me.tarry.app.tracking.ScheduleWindowChecker
import me.tarry.app.tracking.ServiceLauncher
import me.tarry.app.tracking.SystemTrackingClock
import me.tarry.app.tracking.TrackingClock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {
    @Binds @Singleton
    abstract fun bindLocationClient(impl: PlayLocationClient): LocationClient

    @Binds @Singleton
    abstract fun bindActivityTransitionClient(impl: PlayActivityTransitionClient): ActivityTransitionClient

    @Binds @Singleton
    abstract fun bindGeofenceClient(impl: PlayGeofenceClient): GeofenceClient

    @Binds
    abstract fun bindServiceLauncher(impl: AndroidServiceLauncher): ServiceLauncher

    @Binds
    abstract fun bindPermissionChecker(impl: AndroidLocationPermissionChecker): LocationPermissionChecker

    @Binds
    abstract fun bindClock(impl: SystemTrackingClock): TrackingClock

    // TODO(2a-T6): bind the schedule-backed checker instead.
    @Binds
    abstract fun bindWindowChecker(impl: AlwaysInsideWindowChecker): ScheduleWindowChecker

    companion object {
        /** One thread keeps fix batches, activity events and geofence exits in arrival order. */
        @OptIn(ExperimentalCoroutinesApi::class)
        @Provides
        @Singleton
        @CaptureScope
        fun provideCaptureScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    }
}
