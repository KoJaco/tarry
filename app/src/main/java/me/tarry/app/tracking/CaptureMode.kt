package me.tarry.app.tracking

/** Motion-driven capture mode while a session is running. SUSPENDED is the absence of a session. */
enum class CaptureMode { STATIONARY, MOVING }

/** Why tracking is expected but not working. Persisted in [TrackingStatusStore]. */
enum class AttentionCause {
    NO_FINE_LOCATION,
    NO_BACKGROUND_LOCATION,
    LOCATION_OFF,
    SERVICE_NOT_RUNNING,
    NO_FIX_WHILE_MOVING,
    EXACT_ALARM_DENIED,
}
