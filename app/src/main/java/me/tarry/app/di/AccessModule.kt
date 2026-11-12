package me.tarry.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.tarry.app.BuildConfig
import me.tarry.app.access.DebugEntitlement
import me.tarry.app.access.Entitlement
import me.tarry.app.access.PlayPurchaseClient
import me.tarry.app.access.PurchaseClient
import me.tarry.app.access.RealEntitlement
import me.tarry.app.access.entitlementLockSource
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
        chooseEntitlement(BuildConfig.DEBUG, real, debug)

    @Provides @Singleton
    fun providePurchaseClient(client: PlayPurchaseClient): PurchaseClient = client

    /** The lock spans for the segmentation glue, read through the entitlement. */
    @Provides
    fun provideAccessLockSource(entitlement: Provider<Entitlement>, settings: SettingsRepository): AccessLockSource =
        AccessLockSource { entitlementLockSource(entitlement.get(), settings).lockedSpans() }
}

/** Release builds get [RealEntitlement] and never construct the debug one. */
internal fun chooseEntitlement(
    debugBuild: Boolean,
    real: Provider<RealEntitlement>,
    debug: Provider<DebugEntitlement>,
): Entitlement = if (debugBuild) debug.get() else real.get()
