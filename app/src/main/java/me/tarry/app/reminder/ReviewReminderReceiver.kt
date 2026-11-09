package me.tarry.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.TrackingClock
import me.tarry.app.tracking.TrackingStatusStore
import java.time.Instant
import javax.inject.Inject

/** The review-reminder alarm (non-exported). Marks the day done and re-arms for the next day first, then posts only if today still has items to review. */
@AndroidEntryPoint
class ReviewReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReviewReminderScheduler

    @Inject lateinit var notifier: ReviewReminderNotifier

    @Inject lateinit var statusStore: TrackingStatusStore

    @Inject lateinit var clock: TrackingClock

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val zone = clock.zone()
                val today = Instant.ofEpochMilli(clock.nowMs()).atZone(zone).toLocalDate()
                // Mark today done first (fired or skipped as empty), so the re-arm moves to the next day instead of back to today's window.
                try {
                    statusStore.setLastReviewReminderDate(today)
                } finally {
                    scheduler.rearm()
                }
                notifier.postIfNeeded(today, zone)
            } finally {
                pending.finish()
            }
        }
    }
}
