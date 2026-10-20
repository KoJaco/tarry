package me.tarry.app.tracking

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tarry.app.BuildConfig
import me.tarry.core.data.db.entity.ManualOverride
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.ManualOverrideEndMode
import me.tarry.core.data.model.ManualOverrideType
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.repository.TrackingRepository
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Starts and stops the foreground service. Implemented on Android by [AndroidServiceLauncher]. */
interface ServiceLauncher {
    /** Starts the service for [trigger]. Throws [ServiceStartDeniedException] if the OS refuses a background start. */
    fun start(trigger: SessionTrigger)

    fun stop()
}

/** The OS refused to start the foreground service (ForegroundServiceStartNotAllowedException). */
class ServiceStartDeniedException(cause: Throwable? = null) : Exception(cause)

/** Why a foreground-service start failed, reported back to [TrackingController.onStartFailure]. */
enum class StartFailure {
    /** `startForegroundService` was not allowed (no exact-alarm exemption). */
    FGS_NOT_ALLOWED,

    /** `startForeground(type=location)` threw SecurityException (location permission not eligible). */
    SECURITY,
}

/** Manual pause lengths offered by the toggle sheet and the notification action. */
enum class PauseKind { PAUSE_30M, PAUSE_1H, UNTIL_NEXT_WINDOW }

/** Who is asking for a reconcile; decides whether it may start the service directly. */
enum class ReconcileSource {
    /** The schedule alarm: the exact-alarm exemption allows a direct start (ADR 0003). */
    ALARM,

    /** The user, in the foreground or from a notification action: may start directly. */
    UI,

    /** Boot, app start, clock/zone/permission changes: never starts directly, arms a short alarm instead. */
    SYSTEM,
}

/**
 * The single entry point that starts or stops capture (phase 2a design notes). Alarms, receivers,
 * the toggle, notification actions and the health worker all go through here. Every operation is
 * serialised by one mutex, so a stop can never interleave with a start.
 *
 * Manual controls persist `manual_override` rows that the [EffectiveScheduleCalculator] layers over the
 * schedule. A stop inside a scheduled window is a PAUSE row with end mode UNTIL_STOPPED and an explicit
 * `end` at the next window start; that is how a stop (state IDLE) differs from a pause (state PAUSED,
 * end mode DURATION or NEXT_WINDOW).
 */
@Singleton
class TrackingController @Inject constructor(
    private val coordinator: CaptureCoordinator,
    private val launcher: ServiceLauncher,
    private val statusStore: TrackingStatusStore,
    private val trackingRepo: TrackingRepository,
    private val inputsProvider: ScheduleInputsProvider,
    private val calculator: EffectiveScheduleCalculator,
    private val alarmScheduler: ScheduleAlarmScheduler,
    private val segmentationTrigger: SegmentationTrigger,
    private val health: HealthGapRecorder,
    private val clock: TrackingClock,
    private val config: TrackingConfig,
) {
    private val mutex = Mutex()

    /**
     * Makes the running state match the effective schedule, then re-arms the single alarm. Also closes a
     * session left open by a dead process. At a scheduled window end it calls [SegmentationTrigger].
     */
    suspend fun reconcile(source: ReconcileSource = ReconcileSource.ALARM) = mutex.withLock {
        reconcileLocked(source)
    }

    /** Manual START. [durationMin] is required for DURATION. Replaces any active manual override. */
    suspend fun start(endMode: ManualOverrideEndMode, durationMin: Int? = null) = mutex.withLock {
        require(endMode != ManualOverrideEndMode.DURATION || (durationMin ?: 0) > 0) { "DURATION needs durationMin" }
        val now = clock.nowMs()
        deactivateActive(null, now)
        val end = if (endMode == ManualOverrideEndMode.DURATION) now + durationMin!! * MS_PER_MIN else null
        trackingRepo.saveOverride(override(ManualOverrideType.START, endMode, now, end))
        reconcileLocked(ReconcileSource.UI)
    }

    /** Manual PAUSE; capture resumes by itself when it ends. Replaces any active pause. */
    suspend fun pause(kind: PauseKind) = mutex.withLock {
        val now = clock.nowMs()
        deactivateActive(ManualOverrideType.PAUSE, now)
        val row = when (kind) {
            PauseKind.PAUSE_30M -> override(
                ManualOverrideType.PAUSE, ManualOverrideEndMode.DURATION, now, now + config.pauseShortMin * MS_PER_MIN,
            )
            PauseKind.PAUSE_1H -> override(
                ManualOverrideType.PAUSE, ManualOverrideEndMode.DURATION, now, now + config.pauseLongMin * MS_PER_MIN,
            )
            PauseKind.UNTIL_NEXT_WINDOW ->
                override(ManualOverrideType.PAUSE, ManualOverrideEndMode.NEXT_WINDOW, now, null)
        }
        trackingRepo.saveOverride(row)
        reconcileLocked(ReconcileSource.UI)
    }

    /** Ends any manual pause now. */
    suspend fun resume() = mutex.withLock {
        deactivateActive(ManualOverrideType.PAUSE, clock.nowMs())
        reconcileLocked(ReconcileSource.UI)
    }

    /** Ends the session; tracking resumes at the next scheduled window. */
    suspend fun stop() = mutex.withLock {
        val now = clock.nowMs()
        deactivateActive(ManualOverrideType.START, now)
        val nowInstant = Instant.ofEpochMilli(now)
        val zone = clock.zone()
        val inputs = inputsProvider.inputs(nowInstant, nowInstant, zone)
        if (calculator.calculate(inputs, nowInstant, zone).isActive) {
            // Inside a scheduled window: suppress it until the next one starts.
            val untilNext = calculator.resolveEnd(
                inputs,
                override(ManualOverrideType.PAUSE, ManualOverrideEndMode.NEXT_WINDOW, now, null),
                zone,
                nowInstant,
            )
            deactivateActive(ManualOverrideType.PAUSE, now)
            trackingRepo.saveOverride(
                override(ManualOverrideType.PAUSE, ManualOverrideEndMode.UNTIL_STOPPED, now, untilNext?.toEpochMilli()),
            )
        }
        stopCapture(SessionEndReason.USER_STOP)
        reconcileLocked(ReconcileSource.UI)
    }

    /**
     * Called by the service once it is in the foreground. Re-checks the effective schedule under the
     * controller lock, so a stop that arrived first leaves no open session without a running service.
     */
    suspend fun onServiceStarted(trigger: SessionTrigger) = mutex.withLock {
        if (effectiveNow().isActive) coordinator.begin(trigger) else launcher.stop()
    }

    /** Closes a session left open by a dead process (KILLED, plus a Gap when inside a window). */
    suspend fun recoverCrashedSession() = mutex.withLock { coordinator.recoverCrashedSession() }

    /** Called by the service when the OS refused to run the location FGS. */
    suspend fun onStartFailure(failure: StartFailure) = mutex.withLock { onStartFailureLocked(failure) }

    suspend fun onActivity(type: ActivityType) = coordinator.onActivity(type)

    suspend fun onGeofenceExit() = coordinator.onGeofenceExit()

    /** Debug builds only: opens a test window starting soon and re-arms the alarm for it. */
    suspend fun startDebugTestWindow() {
        if (!BuildConfig.DEBUG) return
        mutex.withLock {
            statusStore.setDebugTestWindowStart(clock.nowMs() + config.debugTestWindowDelayMs)
            reconcileLocked(ReconcileSource.UI)
        }
    }

    private suspend fun effectiveNow(): EffectiveSchedule {
        val now = Instant.ofEpochMilli(clock.nowMs())
        val zone = clock.zone()
        return calculator.calculate(inputsProvider.inputs(now, now, zone), now, zone)
    }

    private suspend fun reconcileLocked(source: ReconcileSource) {
        coordinator.recoverCrashedSession()
        // Exact alarms are allowed again: drop the stale cause; a start that fails again re-raises it.
        if (alarmScheduler.canScheduleExact() && statusStore.current().attentionCause == AttentionCause.EXACT_ALARM_DENIED) {
            statusStore.setAttentionCause(null)
        }
        val now = Instant.ofEpochMilli(clock.nowMs())
        val zone = clock.zone()
        val inputs = inputsProvider.inputs(now, now, zone)
        expireOverrides(inputs, now, zone)
        val effective = calculator.calculate(inputs, now, zone)

        var deferredStart = false
        if (effective.isActive) {
            if (!coordinator.isActive) {
                if (source == ReconcileSource.SYSTEM) {
                    deferredStart = true
                } else {
                    startService(if (effective.manuallyStarted) SessionTrigger.MANUAL else SessionTrigger.SCHEDULE)
                }
            }
        } else if (coordinator.isActive) {
            val paused = effective.winningOverride?.type == ManualOverrideType.PAUSE
            stopCapture(if (paused) SessionEndReason.USER_PAUSE else SessionEndReason.SCHEDULE_END)
            if (!paused) segmentationTrigger.onWindowEnd()
        }
        // Not supposed to run (window ended, paused, stopped): any open outage ends here.
        if (!effective.isActive) health.recover(now.toEpochMilli())
        rearm(if (deferredStart) now.plusMillis(config.deferredStartDelayMs) else effective.nextTransition)
    }

    private suspend fun rearm(at: Instant?) {
        val exact = alarmScheduler.arm(at)
        statusStore.setAlarmDegraded(at != null && !exact)
    }

    private suspend fun startService(trigger: SessionTrigger) {
        try {
            launcher.start(trigger)
        } catch (e: ServiceStartDeniedException) {
            onStartFailureLocked(StartFailure.FGS_NOT_ALLOWED)
        }
    }

    private suspend fun stopCapture(endReason: SessionEndReason) {
        coordinator.end(endReason)
        launcher.stop()
    }

    private suspend fun onStartFailureLocked(failure: StartFailure) {
        launcher.stop()
        val effective = effectiveNow()
        // A stale start (window already over, paused) is not an outage.
        if (!effective.isActive) return
        val issue = when (failure) {
            StartFailure.FGS_NOT_ALLOWED -> HealthIssue(AttentionCause.EXACT_ALARM_DENIED, GapCause.SERVICE_KILLED)
            StartFailure.SECURITY -> HealthIssue(coordinator.locationPermissionFailureCause(), GapCause.PERMISSION_LOST)
        }
        health.reportOutage(issue, effective.activeSince?.toEpochMilli())
    }

    /** Deactivates active overrides (of [type], or all if null), ending them now. */
    private suspend fun deactivateActive(type: ManualOverrideType?, nowMs: Long) {
        for (o in trackingRepo.observeActiveOverrides().first()) {
            if (type != null && o.type != type) continue
            trackingRepo.saveOverride(o.copy(active = false, end = minOf(o.end ?: nowMs, nowMs)))
        }
    }

    /** Marks active overrides whose end has passed as inactive, recording the end. */
    private suspend fun expireOverrides(inputs: ScheduleInputs, now: Instant, zone: ZoneId) {
        for (o in trackingRepo.observeActiveOverrides().first()) {
            val end = calculator.resolveEnd(inputs, o, zone, now) ?: continue
            if (end <= now) trackingRepo.saveOverride(o.copy(active = false, end = end.toEpochMilli()))
        }
    }

    private fun override(type: ManualOverrideType, mode: ManualOverrideEndMode, start: Long, end: Long?) =
        ManualOverride(type = type, start = start, end = end, zoneId = clock.zone().id, endMode = mode, active = true)

    private companion object {
        const val MS_PER_MIN = 60_000L
    }
}
