package me.tarry.app.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import me.tarry.app.R
import javax.inject.Inject
import javax.inject.Singleton

/** Alert notification for outages, on its own channel so it is not silenced with the ongoing one. Tapping opens the app. */
@Singleton
class AndroidHealthAlertNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : HealthAlertNotifier {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun post(cause: AttentionCause) {
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tracking_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val text = context.getString(cause.messageRes())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(context.getString(R.string.tracking_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(TrackingIntents.openApp(context))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun clear() {
        manager.cancel(NOTIFICATION_ID)
    }

    @StringRes
    private fun AttentionCause.messageRes(): Int = when (this) {
        AttentionCause.NO_FINE_LOCATION -> R.string.tracking_attention_no_fine_location
        AttentionCause.NO_BACKGROUND_LOCATION -> R.string.tracking_attention_no_background_location
        AttentionCause.LOCATION_OFF -> R.string.tracking_attention_location_off
        AttentionCause.SERVICE_NOT_RUNNING -> R.string.tracking_attention_service_not_running
        AttentionCause.NO_FIX_WHILE_MOVING -> R.string.tracking_attention_no_fix_while_moving
        AttentionCause.EXACT_ALARM_DENIED -> R.string.tracking_attention_exact_alarm_denied
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "tracking_alerts"
    }
}
