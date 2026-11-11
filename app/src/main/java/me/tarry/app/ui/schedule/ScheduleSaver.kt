package me.tarry.app.ui.schedule

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.tarry.app.reminder.ReviewReminderScheduler
import me.tarry.app.tracking.ScheduleChange
import me.tarry.app.tracking.TrackingController
import me.tarry.app.tracking.TrackingStateRepository
import javax.inject.Inject

/**
 * The chain every schedule save runs: [TrackingController.updateSchedule] (writes, then one re-arm and reconcile), a
 * tracking-state refresh, then the review-reminder re-arm. Shared by the Schedule screen and onboarding. Callers run it on
 * the capture scope so it finishes even if the user leaves right after tapping.
 */
class ScheduleSaver @Inject constructor(
    private val controller: TrackingController,
    private val trackingState: TrackingStateRepository,
    private val reviewReminder: ReviewReminderScheduler,
) {
    /** Throws if the save itself failed; a failing reminder re-arm is logged only (the next app start re-arms it). */
    suspend fun save(change: ScheduleChange) {
        controller.updateSchedule(change)
        trackingState.refresh()
        try {
            reviewReminder.rearm()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ScheduleSaver", "Review reminder re-arm failed after a schedule save", e)
        }
    }
}
