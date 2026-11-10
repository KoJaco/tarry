package me.tarry.app.ui.onboarding

import me.tarry.app.tracking.TrackingClock
import me.tarry.app.ui.permissions.PermissionChecker
import me.tarry.core.data.repository.TrackingRepository
import me.tarry.core.data.settings.SettingsRepository
import javax.inject.Inject

/**
 * Decides at app start whether first-run onboarding shows. Only a fresh install sees it: an existing install (fine location
 * already granted, or any tracking session recorded) skips it silently, and that skip also stamps the completion time so
 * the answer stays the same later (8b's trial clock starts from the stamp).
 */
class OnboardingGate @Inject constructor(
    private val settings: SettingsRepository,
    private val trackingRepo: TrackingRepository,
    private val permissions: PermissionChecker,
    private val clock: TrackingClock,
) {
    suspend fun needsOnboarding(): Boolean {
        if (settings.onboardingCompletedAtMs() != null) return false
        if (permissions.check().fineLocation || trackingRepo.anySession()) {
            settings.setOnboardingCompletedAtMs(clock.nowMs())
            return false
        }
        return true
    }

    /** Onboarding finished (Open Tarry, or Not now on the location page): never show it again. Keeps the first stamp. */
    suspend fun markCompleted() {
        if (settings.onboardingCompletedAtMs() == null) settings.setOnboardingCompletedAtMs(clock.nowMs())
    }
}
