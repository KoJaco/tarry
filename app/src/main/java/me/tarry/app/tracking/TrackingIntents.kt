package me.tarry.app.tracking

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * PendingIntents for the tracking receivers. All target explicit, non-exported components.
 * Play Services must add result extras to the activity and geofence intents, so those are FLAG_MUTABLE
 * (explicit intent, as required on API 34+); everything else is FLAG_IMMUTABLE.
 */
internal object TrackingIntents {
    const val ACTION_PAUSE_1H = "me.tarry.app.action.PAUSE_1H"
    const val ACTION_STOP = "me.tarry.app.action.STOP"

    private const val REQ_ACTIVITY = 1
    private const val REQ_GEOFENCE = 2
    private const val REQ_PAUSE = 3
    private const val REQ_STOP = 4
    private const val REQ_OPEN_APP = 5

    fun activityTransitions(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQ_ACTIVITY,
        Intent(context, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun geofenceExit(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQ_GEOFENCE,
        Intent(context, GeofenceExitReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun pause1h(context: Context): PendingIntent = action(context, REQ_PAUSE, ACTION_PAUSE_1H)

    fun stop(context: Context): PendingIntent = action(context, REQ_STOP, ACTION_STOP)

    fun openApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, REQ_OPEN_APP, it, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun action(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TrackingActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
