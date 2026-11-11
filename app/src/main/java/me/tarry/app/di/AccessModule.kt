package me.tarry.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.tarry.app.BuildConfig
import me.tarry.app.access.DebugEntitlement
import me.tarry.app.access.Entitlement
import me.tarry.app.access.RealEntitlement
import me.tarry.app.segmentation.AccessLockSource
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Provider
import javax.inject.Singleton

/** The one [Entitlement]: the real one in release builds, the debug switch (delegating to the real one) in debug builds. */
@Module
@InstallIn(SingletonComponent::class)
object AccessModule {
    @Provides @Singleton
    fun provideEntitlement(real: Provider<RealEntitlement>, debug: Provider<DebugEntitlement>): Entitlement =
        if (BuildConfig.DEBUG) debug.get() else real.get()

    /** When the lock began, for the segmentation glue (the stamp is written by the entitlement). */
    @Provides
    fun provideAccessLockSource(settings: SettingsRepository): AccessLockSource =
        AccessLockSource { settings.currentAccessPrefs().lockedSinceMs }
}
