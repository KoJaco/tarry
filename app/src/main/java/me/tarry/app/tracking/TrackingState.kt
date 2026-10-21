package me.tarry.app.tracking

import java.time.Instant

enum class TrackingState { TRACKING, IDLE, PAUSED, ATTENTION }

/** What the toggle sheet needs besides [TrackingState]. The real state repository (T8) can fold this in. */
data class TrackingDetails(
    val attentionCause: AttentionCause? = null,
    /** When a manual pause ends; null if not paused or open-ended. */
    val pausedUntil: Instant? = null,
)
