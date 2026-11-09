package me.tarry.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingStatusStore
import me.tarry.core.data.settings.SettingsRepository
import java.time.Instant
import javax.inject.Inject

/**
 * The review-reminder alarm (non-exported). The recheck action only recomputes the alarm and never posts. The reminder
 * itself marks the day done and re-arms for the next day first, then posts only if the reminder is still on, today is
 * still a scheduled day (a restore can change both behind the alarm's back) and today still has items to review.
 */
@AndroidEntryPoint
class ReviewReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReviewReminderScheduler

    @Inject lateinit var notifier: ReviewReminderNotifier

    @Inject lateinit var statusStore: TrackingStatusStore

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var scheduledDays: ScheduledDays

    @Inject lateinit var clock: TrackingClock

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val recheckOnly = intent.action == AndroidReviewReminderScheduler.ACTION_RECHECK
        val pending = goAsync()
        scope.launch {
            try {
                if (recheckOnly) {
                    scheduler.rearm()
                    return@launch
                }
                val zone = clock.zone()
                val today = Instant.ofEpochMilli(clock.nowMs()).atZone(zone).toLocalDate()
                // Mark today done first (fired or skipped), so the re-arm moves to the next day instead of back to today.
                try {
                    statusStore.setLastReviewReminderDate(today)
                } finally {
                    scheduler.rearm()
                }
                if (!settings.settings.first().reviewReminderEnabled) return@launch
                if (today !in scheduledDays.among(today, 0, zone)) return@launch
                notifier.postIfNeeded(today, zone)
            } finally {
                pending.finish()
            }
        }
    }
}
