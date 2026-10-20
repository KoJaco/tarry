package me.tarry.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import me.tarry.app.R
import javax.inject.Inject
import javax.inject.Singleton

/** The ongoing foreground-service notification. Alert notifications (T8) use a separate channel. */
@Singleton
class TrackingNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun build(mode: CaptureMode): Notification {
        ensureChannel()
        val text = when (mode) {
            CaptureMode.MOVING -> R.string.tracking_notification_moving
            CaptureMode.STATIONARY -> R.string.tracking_notification_stationary
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.tracking_notification_title))
            .setContentText(context.getString(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(TrackingIntents.openApp(context))
            .addAction(0, context.getString(R.string.tracking_action_pause_1h), TrackingIntents.pause1h(context))
            .addAction(0, context.getString(R.string.tracking_action_stop), TrackingIntents.stop(context))
            .build()
    }

    fun update(mode: CaptureMode) {
        manager.notify(NOTIFICATION_ID, build(mode))
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tracking_ongoing"
    }
}
