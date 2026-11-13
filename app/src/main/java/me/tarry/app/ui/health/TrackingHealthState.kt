package me.tarry.app.ui.health

import me.tarry.app.tracking.AttentionCause
import me.tarry.app.tracking.Fix
import me.tarry.app.tracking.Preconditions
import me.tarry.core.data.settings.PowerMode
import me.tarry.app.tracking.TrackingState
import me.tarry.app.ui.permissions.PermissionSnapshot

/** The things Tarry needs for scheduled tracking, in the order the screen lists them. */
enum class HealthCheckId {
    PRECISE_LOCATION,
    BACKGROUND_LOCATION,
    LOCATION_SERVICES,
    PHYSICAL_ACTIVITY,
    NOTIFICATIONS,
    EXACT_ALARMS,
    BATTERY,
}

data class HealthCheck(val id: HealthCheckId, val ok: Boolean)

/** The checks that apply on this phone (notifications only from Android 13), each read from the platform right now. */
fun buildHealthChecks(snapshot: PermissionSnapshot, preconditions: Preconditions): List<HealthCheck> = buildList {
    add(HealthCheck(HealthCheckId.PRECISE_LOCATION, snapshot.fineLocation))
    add(HealthCheck(HealthCheckId.BACKGROUND_LOCATION, snapshot.backgroundLocation))
    add(HealthCheck(HealthCheckId.LOCATION_SERVICES, preconditions.locationEnabled))
    add(HealthCheck(HealthCheckId.PHYSICAL_ACTIVITY, snapshot.activityRecognition))
    if (snapshot.notificationsApplicable) add(HealthCheck(HealthCheckId.NOTIFICATIONS, snapshot.notifications))
    add(HealthCheck(HealthCheckId.EXACT_ALARMS, snapshot.exactAlarms))
    add(HealthCheck(HealthCheckId.BATTERY, snapshot.batteryExempt))
}

/** The first precondition that stops scheduled capture right now (the same causes the health monitor reports), or null. */
fun firstBlockingCause(p: Preconditions): AttentionCause? = when {
    !p.fineLocation -> AttentionCause.NO_FINE_LOCATION
    !p.backgroundLocation -> AttentionCause.NO_BACKGROUND_LOCATION
    !p.locationEnabled -> AttentionCause.LOCATION_OFF
    !p.exactAlarms -> AttentionCause.EXACT_ALARM_DENIED
    else -> null
}

/** Where the one-off Test tracking request is. Nothing is recorded: this is only what the card says. */
sealed interface TestFixState {
    data object Idle : TestFixState

    data object Running : TestFixState

    /**
     * A fix arrived. [blockedBy] is why tracking still could not start (a failing precondition, else the current health
     * cause), or null when it can.
     */
    data class Success(val accuracyM: Int, val ageSeconds: Long, val blockedBy: AttentionCause?) : TestFixState

    data class Timeout(val seconds: Int) : TestFixState

    /** Location permission is off; the card offers the fix. */
    data object Denied : TestFixState
}

data class TrackingHealthUiState(
    val trackingState: TrackingState = TrackingState.IDLE,
    val attentionCause: AttentionCause? = null,
    val checks: List<HealthCheck> = emptyList(),
    val test: TestFixState = TestFixState.Idle,
    /** The mode the user chose, and whether the low-battery switch has put capture in Saver right now. */
    val powerMode: PowerMode = PowerMode.BALANCED,
    val lowBatterySaverActive: Boolean = false,
) {
    val testRunning: Boolean get() = test == TestFixState.Running
}

internal fun successOf(fix: Fix, nowMs: Long, blockedBy: AttentionCause?): TestFixState.Success =
    TestFixState.Success(
        accuracyM = Math.round(fix.accuracyM),
        ageSeconds = maxOf(0L, (nowMs - fix.timeMs) / MS_PER_SECOND),
        blockedBy = blockedBy,
    )

private const val MS_PER_SECOND = 1_000L
