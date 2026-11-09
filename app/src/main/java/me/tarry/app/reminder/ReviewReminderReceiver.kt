package me.tarry.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.tarry.app.tracking.CaptureScope
import me.tarry.app.tracking.TrackingClock
import java.time.Instant
import javax.inject.Inject

/** The review-reminder alarm (non-exported). Re-arms for the next day first, then posts only if today still has items to review. */
@AndroidEntryPoint
class ReviewReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReviewReminderScheduler

    @Inject lateinit var notifier: ReviewReminderNotifier

    @Inject lateinit var clock: TrackingClock

    @Inject @CaptureScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                scheduler.rearm()
                val zone = clock.zone()
                notifier.postIfNeeded(Instant.ofEpochMilli(clock.nowMs()).atZone(zone).toLocalDate(), zone)
            } finally {
                pending.finish()
            }
        }
    }
}
