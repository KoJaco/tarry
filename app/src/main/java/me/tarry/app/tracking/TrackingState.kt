package me.tarry.app.tracking

import me.tarry.core.data.settings.PowerMode
import java.time.Instant

enum class TrackingState { TRACKING, IDLE, PAUSED, ATTENTION }

/** What the toggle sheet needs besides [TrackingState]. The real state repository (T8) can fold this in. */
data class TrackingDetails(
    val attentionCause: AttentionCause? = null,
    /** When a manual pause ends; null if not paused or open-ended. */
    val pausedUntil: Instant? = null,
    /** TRACKING only: capture is past the schedule end, finishing the current stop; the sheet says so and still offers Stop. */
    val finishingUp: Boolean = false,
    /** The mode the user chose (Settings > Battery use). */
    val powerMode: PowerMode = PowerMode.BALANCED,
    /** The low-battery switch has put the open session in Saver (rule 6: shown, never silent). */
    val lowBatterySaverActive: Boolean = false,
)
