package me.tarry.app.tracking

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app may post notifications, refreshed when the app comes to the foreground. Tracking can start before the
 * user allows notifications (onboarding grants background location first); Android then hides the foreground-service
 * notification and never shows it later on its own, so the running service re-posts it when this turns true.
 */
@Singleton
class NotificationAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val state = MutableStateFlow(enabled())
    val allowed: StateFlow<Boolean> = state.asStateFlow()

    fun refresh() {
        state.value = enabled()
    }

    private fun enabled() = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** What the ongoing notification shows. */
data class OngoingContent(val mode: CaptureMode, val finishingUp: Boolean)

/**
 * When to (re)post the ongoing notification: on every content change while notifications are allowed, and again when
 * they become allowed, so a notification hidden for lack of permission appears without waiting for the next change.
 */
fun ongoingNotificationUpdates(status: Flow<TrackingStatus>, allowed: Flow<Boolean>): Flow<OngoingContent> =
    combine(
        status.map { s -> s.captureMode?.let { OngoingContent(it, s.finishingUp) } }.distinctUntilChanged(),
        allowed.distinctUntilChanged(),
    ) { content, ok -> if (ok) content else null }
        .distinctUntilChanged()
        .filter { it != null }
        .map { it!! }
