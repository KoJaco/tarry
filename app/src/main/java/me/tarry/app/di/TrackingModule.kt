package me.tarry.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import me.tarry.app.tracking.ActivityTransitionClient
import me.tarry.app.tracking.EffectiveScheduleWindowChecker
import me.tarry.app.tracking.AndroidLocationPermissionChecker
import me.tarry.app.tracking.AndroidHealthAlertNotifier
import me.tarry.app.tracking.AndroidPreconditionChecker
import me.tarry.app.tracking.HealthAlertNotifier
import me.tarry.app.tracking.HealthGapRecorder
import me.tarry.app.tracking.createCaptureScope
import me.tarry.app.tracking.PreconditionChecker
import me.tarry.app.tracking.AndroidServiceLauncher
import me.tarry.app.tracking.CaptureCoordinator
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.GeofenceClient
import me.tarry.app.tracking.LocationClient
import me.tarry.app.tracking.LocationPermissionChecker
import me.tarry.app.tracking.PlayActivityTransitionClient
import me.tarry.app.tracking.PlayGeofenceClient
import me.tarry.app.tracking.PlayLocationClient
import me.tarry.app.tracking.NoOpSegmentationTrigger
import me.tarry.app.tracking.AndroidScheduleAlarmScheduler
import me.tarry.app.tracking.ScheduleAlarmScheduler
import me.tarry.app.tracking.SegmentationTrigger
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
    abstract fun bindPreconditionChecker(impl: AndroidPreconditionChecker): PreconditionChecker

    @Binds
    abstract fun bindHealthAlertNotifier(impl: AndroidHealthAlertNotifier): HealthAlertNotifier

    @Binds
    abstract fun bindClock(impl: SystemTrackingClock): TrackingClock

    @Binds
    abstract fun bindWindowChecker(impl: EffectiveScheduleWindowChecker): ScheduleWindowChecker

    @Binds
    abstract fun bindAlarmScheduler(impl: AndroidScheduleAlarmScheduler): ScheduleAlarmScheduler

    @Binds
    abstract fun bindSegmentationTrigger(impl: NoOpSegmentationTrigger): SegmentationTrigger

    companion object {
        /** See [createCaptureScope]. Lazy recorder avoids a construction cycle. */
        @Provides
        @Singleton
        @CaptureScope
        fun provideCaptureScope(recorder: dagger.Lazy<HealthGapRecorder>, coordinator: dagger.Lazy<CaptureCoordinator>): CoroutineScope =
            createCaptureScope({ recorder.get() }, { coordinator.get().isActive })
    }
}
