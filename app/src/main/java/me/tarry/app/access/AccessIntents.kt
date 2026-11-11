package me.tarry.app.access

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import me.tarry.app.MainActivity

/** The trial-ended alert's tap, handled by MainActivity: open the app on the paywall. */
object AccessIntents {
    const val EXTRA_OPEN_PAYWALL = "me.tarry.app.extra.OPEN_PAYWALL"
    private const val REQUEST_CODE = 71

    fun openPaywall(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_PAYWALL, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
