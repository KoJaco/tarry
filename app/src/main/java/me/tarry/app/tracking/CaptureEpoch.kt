package me.tarry.app.tracking

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import me.tarry.core.data.repository.TrackingRepository
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

/**
 * The earliest time a Gap may start on this install; null while first-run onboarding is open (nothing is lost before
 * the user has set the app up).
 */
fun interface CaptureEpoch {
    suspend fun notBeforeMs(): Long?
}

/** When the app was first installed (UTC epoch ms). */
fun interface InstallTimeSource {
    fun firstInstallTimeMs(): Long
}

class AndroidInstallTimeSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : InstallTimeSource {
    override fun firstInstallTimeMs(): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }
}

/**
 * Upgraders keep their old install time, so a real outage that began before the upgrade still starts at its true
 * time; a pre-granted or permission-restored fresh install is bounded by its install time and, if onboarding ran,
 * by its completion.
 */
class TrackingCaptureEpoch internal constructor(
    private val settings: SettingsRepository,
    private val installTime: InstallTimeSource,
    private val anySession: suspend () -> Boolean,
) : CaptureEpoch {
    @Inject constructor(settings: SettingsRepository, installTime: InstallTimeSource, trackingRepo: TrackingRepository) :
        this(settings, installTime, trackingRepo::anySession)

    override suspend fun notBeforeMs(): Long? {
        val started = settings.onboardingStartedAtMs()
        val completed = settings.onboardingCompletedAtMs()
        if (started != null && completed == null) return null
        // Nothing stamped and never tracked: a fresh install whose first start-up runs before the onboarding gate has
        // stamped anything. It is not set up yet either. (An upgrader before its first launch has sessions.)
        if (started == null && completed == null && !anySession()) return null
        return maxOf(installTime.firstInstallTimeMs(), if (started != null) completed ?: 0L else 0L)
    }
}
