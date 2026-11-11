package me.tarry.app.tracking

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import me.tarry.core.data.repository.TrackingRepository
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

/**
 * The earliest time a Gap may start on this install; null until the app is set up (onboarding finished, or a session has
 * run): nothing is lost before that.
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
        // Onboarding not finished (or not even stamped yet, on the first start-up) and never tracked: not set up, nothing
        // is lost. Once a session has run, every outage counts again (rule 6), even if onboarding was left open: the
        // seeded schedule and a granted location can start tracking before Save hours.
        if (completed == null && !anySession()) return null
        val onboarded = if (started != null) completed ?: started else 0L
        return maxOf(installTime.firstInstallTimeMs(), onboarded)
    }
}
