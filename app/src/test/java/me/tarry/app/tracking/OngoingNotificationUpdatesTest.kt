package me.tarry.app.tracking

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The ongoing notification is re-posted once notifications are allowed (onboarding allows them after tracking starts). */
@OptIn(ExperimentalCoroutinesApi::class)
class OngoingNotificationUpdatesTest {
    private val moving = TrackingStatus(captureMode = CaptureMode.MOVING)

    /** Collects the posts while running [steps] one at a time, letting the flow settle after each. */
    private fun posts(
        status: MutableStateFlow<TrackingStatus>,
        allowed: MutableStateFlow<Boolean>,
        vararg steps: () -> Unit,
    ): List<OngoingContent> {
        val out = mutableListOf<OngoingContent>()
        runTest {
            backgroundScope.launch { ongoingNotificationUpdates(status, allowed).toList(out) }
            runCurrent()
            steps.forEach { it(); settle() }
        }
        return out
    }

    private fun TestScope.settle() = runCurrent()

    @Test
    fun trackingStartedBeforeNotificationsWereAllowedPostsOnceTheyAre() {
        val status = MutableStateFlow(moving)
        val allowed = MutableStateFlow(false)
        assertThat(posts(status, allowed, { allowed.value = true }))
            .containsExactly(OngoingContent(CaptureMode.MOVING, false))
    }

    @Test
    fun nothingIsPostedWhileNotificationsAreNotAllowed() {
        val status = MutableStateFlow(moving)
        val allowed = MutableStateFlow(false)
        assertThat(posts(status, allowed, { status.value = TrackingStatus(captureMode = CaptureMode.STATIONARY) }))
            .isEmpty()
    }

    @Test
    fun whileAllowedEveryContentChangeIsPostedOnce() {
        val status = MutableStateFlow(moving)
        val allowed = MutableStateFlow(true)
        val result = posts(
            status, allowed,
            { status.value = moving.copy(lastHeartbeatMs = 1L) }, // not shown in the notification
            { status.value = TrackingStatus(captureMode = CaptureMode.STATIONARY) },
            { status.value = TrackingStatus(captureMode = CaptureMode.STATIONARY, finishingUp = true) },
            { allowed.value = true }, // a refresh with no change
        )
        assertThat(result).containsExactly(
            OngoingContent(CaptureMode.MOVING, false),
            OngoingContent(CaptureMode.STATIONARY, false),
            OngoingContent(CaptureMode.STATIONARY, true),
        ).inOrder()
    }

    @Test
    fun notificationsTurnedOffAndOnAgainRepostTheCurrentContent() {
        val status = MutableStateFlow(moving)
        val allowed = MutableStateFlow(true)
        assertThat(posts(status, allowed, { allowed.value = false }, { allowed.value = true })).containsExactly(
            OngoingContent(CaptureMode.MOVING, false),
            OngoingContent(CaptureMode.MOVING, false),
        )
    }

    @Test
    fun noCaptureModeMeansNoPost() {
        assertThat(posts(MutableStateFlow(TrackingStatus()), MutableStateFlow(true))).isEmpty()
    }
}
