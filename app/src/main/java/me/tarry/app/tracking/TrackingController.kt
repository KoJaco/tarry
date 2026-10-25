package me.tarry.app.tracking

import kotlinx.coroutines.CancellationException
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
import me.tarry.core.data.repository.ScheduleRepository
import me.tarry.core.data.repository.TrackingRepository
import java.time.Duration
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
    private val scheduleRepo: ScheduleRepository,
    private val inputsProvider: ScheduleInputsProvider,
    private val calculator: EffectiveScheduleCalculator,
    private val alarmScheduler: ScheduleAlarmScheduler,
    private val segmentationTrigger: SegmentationTrigger,
    private val health: HealthGapRecorder,
    private val clock: TrackingClock,
    private val config: TrackingConfig,
) {
    private val mutex = Mutex()

    /** When the window-end grace began (the first reconcile after the schedule ended with capture unsettled); in memory only. */
    private var graceStartMs: Long? = null

    init {
        coordinator.failureListener = ::onCaptureFailed
        coordinator.settleListener = ::onSettled
    }

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

    /** Manual PAUSE for [durationMin] minutes from now. Replaces any active pause. */
    suspend fun pause(durationMin: Int) = mutex.withLock {
        require(durationMin > 0) { "durationMin must be positive" }
        val now = clock.nowMs()
        deactivateActive(ManualOverrideType.PAUSE, now)
        trackingRepo.saveOverride(
            override(ManualOverrideType.PAUSE, ManualOverrideEndMode.DURATION, now, now + durationMin * MS_PER_MIN),
        )
        reconcileLocked(ReconcileSource.UI)
    }

    /** Manual START that ends at the exact instant [untilMs] (must be after now). Replaces any active manual override. */
    suspend fun startUntil(untilMs: Long) = mutex.withLock {
        val now = clock.nowMs()
        require(untilMs > now) { "untilMs must be in the future" }
        deactivateActive(null, now)
        trackingRepo.saveOverride(override(ManualOverrideType.START, ManualOverrideEndMode.DURATION, now, untilMs))
        reconcileLocked(ReconcileSource.UI)
    }

    /** Manual PAUSE that ends at the exact instant [untilMs] (must be after now). Replaces any active pause. */
    suspend fun pauseUntil(untilMs: Long) = mutex.withLock {
        val now = clock.nowMs()
        require(untilMs > now) { "untilMs must be in the future" }
        deactivateActive(ManualOverrideType.PAUSE, now)
        trackingRepo.saveOverride(override(ManualOverrideType.PAUSE, ManualOverrideEndMode.DURATION, now, untilMs))
        reconcileLocked(ReconcileSource.UI)
    }

    /**
     * Saves a schedule change and applies it at once: records that the edit counts from now (no Gap for the past),
     * re-bounds a Stop, then reconciles, which starts or stops capture and re-arms the single alarm. Painting out
     * the current window ends it through the normal window-end path (grace, SCHEDULE_END).
     */
    suspend fun updateSchedule(change: ScheduleChange) = mutex.withLock {
        val now = clock.nowMs()
        val zone = clock.zone()
        val nowInstant = Instant.ofEpochMilli(now)
        inputsProvider.seedTemplateIfMissing()
        val templateBefore = checkNotNull(scheduleRepo.template()) { "template was just seeded" }
        val weekBefore = when (change) {
            is ScheduleChange.SaveTemplate -> null
            is ScheduleChange.SaveWeek -> WeekBefore(change.weekStart, scheduleRepo.override(change.weekStart.toString()))
            is ScheduleChange.ClearWeek -> WeekBefore(change.weekStart, scheduleRepo.override(change.weekStart.toString()))
        }
        val changes = when (change) {
            is ScheduleChange.SaveTemplate -> change.schedule != templateBefore
            is ScheduleChange.SaveWeek -> change.schedule != weekBefore?.overrideBefore
            is ScheduleChange.ClearWeek -> weekBefore?.overrideBefore != null
        }
        if (changes) {
            // The edit goes first: a crash before the write below leaves an edit whose "before" is still current (harmless),
            // while the other order would leave a schedule change that was never marked, and so counted retroactively.
            statusStore.appendScheduleEdit(
                ScheduleEdit(now, templateBefore, weekBefore), config.scheduleEditRetentionDays, config.scheduleEditMax,
            )
            when (change) {
                is ScheduleChange.SaveTemplate -> scheduleRepo.saveTemplate(change.schedule, now)
                is ScheduleChange.SaveWeek -> scheduleRepo.saveOverride(change.weekStart.toString(), change.schedule, now)
                is ScheduleChange.ClearWeek -> scheduleRepo.clearOverride(change.weekStart.toString())
            }
        }
        rebindStop(nowInstant, zone)
        reconcileLocked(ReconcileSource.UI)
    }

    /**
     * A Stop inside a window is a PAUSE (UNTIL_STOPPED) ending at the next window start; after a schedule change that
     * start may have moved (a window painted later today), so recompute its end the way [stop] does.
     */
    private suspend fun rebindStop(now: Instant, zone: ZoneId) {
        val stops = trackingRepo.observeActiveOverrides().first().filter {
            it.type == ManualOverrideType.PAUSE && it.endMode == ManualOverrideEndMode.UNTIL_STOPPED &&
                (it.end ?: return@filter false) > now.toEpochMilli()
        }
        if (stops.isEmpty()) return
        val inputs = inputsProvider.inputs(now, now, zone)
        val untilNext = calculator.resolveEnd(
            inputs,
            override(ManualOverrideType.PAUSE, ManualOverrideEndMode.NEXT_WINDOW, now.toEpochMilli(), null),
            zone,
            now,
        ) ?: now.plus(Duration.ofDays(config.scheduleLookAheadDays))
        for (s in stops) {
            if (s.end != untilNext.toEpochMilli()) trackingRepo.saveOverride(s.copy(end = untilNext.toEpochMilli()))
        }
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
            // No window within the look-ahead: still bound the stop, so it cannot suppress the schedule forever.
            val untilNext = calculator.resolveEnd(
                inputs,
                override(ManualOverrideType.PAUSE, ManualOverrideEndMode.NEXT_WINDOW, now, null),
                zone,
                nowInstant,
            ) ?: nowInstant.plus(Duration.ofDays(config.scheduleLookAheadDays))
            deactivateActive(ManualOverrideType.PAUSE, now)
            trackingRepo.saveOverride(
                override(ManualOverrideType.PAUSE, ManualOverrideEndMode.UNTIL_STOPPED, now, untilNext.toEpochMilli()),
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
        val effective = effectiveNow()
        if (!effective.isActive) {
            launcher.stop()
            return@withLock
        }
        try {
            coordinator.begin(trigger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The service is in the foreground but there is no session (begin rolled back): stop it and say so.
            launcher.stop()
            health.reportOutage(
                HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.SERVICE_KILLED),
                outageNotBefore(effective),
            )
        }
    }

    /** Closes a session left open by a dead process (KILLED, plus a Gap when inside a window). */
    suspend fun recoverCrashedSession() = mutex.withLock { coordinator.recoverCrashedSession() }

    /**
     * A capture failure that must also stop the service (permission lost mid-session): report the outage while
     * the heartbeat is still set (it is the Gap start), then end the session and stop the service.
     */
    suspend fun onCaptureFailed(sessionId: Long, issue: HealthIssue) = mutex.withLock {
        if (coordinator.activeSessionId != sessionId) return@withLock
        health.reportOutage(issue)
        stopCapture(SessionEndReason.PERMISSION_LOST)
    }

    /** Called by the service when the OS refused to run the location FGS. */
    suspend fun onStartFailure(failure: StartFailure) = mutex.withLock { onStartFailureLocked(failure) }

    suspend fun onActivity(type: ActivityType, eventAgeMs: Long = 0L) = coordinator.onActivity(type, eventAgeMs)

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
        settleOrphanedGrace()
        // Exact alarms are allowed again: drop the stale cause; a start that fails again re-raises it.
        if (alarmScheduler.canScheduleExact()) health.recoverCause(AttentionCause.EXACT_ALARM_DENIED)
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
            } else {
                // A location request that died on a non-security error, or a failed Activity Recognition
                // registration, is retried here.
                coordinator.ensureCapture()
                endGrace()
            }
        } else if (coordinator.isActive) {
            val paused = effective.winningOverride?.type == ManualOverrideType.PAUSE
            if (paused) {
                stopCapture(SessionEndReason.USER_PAUSE)
            } else if (!keepOpenForGrace(now.toEpochMilli())) {
                stopCapture(SessionEndReason.SCHEDULE_END)
                segmentationTrigger.onWindowEnd()
            }
        }
        // Not supposed to run (window ended, paused, stopped): any open outage ends here.
        // A grace session is still capture that should be running: its outage (rule 6) stays open.
        if (!effective.isActive && graceStartMs == null) health.closeOutage(now.toEpochMilli())
        val next = if (deferredStart) now.plusMillis(config.deferredStartDelayMs) else effective.nextTransition
        // During the grace the cap is the one extra alarm; settling itself is driven by capture events, never a timer.
        val graceCap = graceStartMs?.let { Instant.ofEpochMilli(it + config.windowEndGraceMaxMs) }
        rearm(if (graceCap != null && (next == null || graceCap.isBefore(next))) graceCap else next)
    }

    /**
     * Window-end grace: at the schedule end a scheduled session stays open ("Finishing up…") while capture is MOVING or
     * the current stay is younger than [TrackingConfig.windowEndSettleMs], until a settled stay is confirmed
     * (reported by the coordinator through [onSettled]) or [TrackingConfig.windowEndGraceMaxMs] has passed.
     * Returns true to keep the session open.
     */
    private suspend fun keepOpenForGrace(nowMs: Long): Boolean {
        if (coordinator.activeTrigger != SessionTrigger.SCHEDULE) return false
        val started = graceStartMs
        if (started == null) {
            if (coordinator.isSettled()) return false
            graceStartMs = nowMs
            statusStore.setFinishingUp(true, nowMs)
            coordinator.watchSettle(true)
            return true
        }
        return nowMs - started < config.windowEndGraceMaxMs && !coordinator.isSettled()
    }

    /**
     * A grace that was running when the process died: the session is gone (crash recovery closed it and recorded the
     * lost time), but its window end was never handled. Finish it now: clear the flag and run the end-of-window
     * segmentation, so the grace is neither left "finishing up" forever nor skipped by segmentation.
     */
    private suspend fun settleOrphanedGrace() {
        if (graceStartMs != null || coordinator.isActive) return
        if (!statusStore.current().finishingUp) return
        statusStore.setFinishingUp(false)
        segmentationTrigger.onWindowEnd()
    }

    private suspend fun endGrace() {
        if (graceStartMs == null) return
        graceStartMs = null
        coordinator.watchSettle(false)
        statusStore.setFinishingUp(false)
    }

    /** A stay long enough to end the grace was confirmed. */
    private suspend fun onSettled() = mutex.withLock {
        if (graceStartMs != null) reconcileLocked(ReconcileSource.ALARM)
    }

    private suspend fun rearm(at: Instant?) {
        val exact = alarmScheduler.arm(at)
        statusStore.setAlarmDegraded(at != null && !exact)
    }

    private suspend fun startService(trigger: SessionTrigger) {
        try {
            launcher.start(trigger)
            segmentationTrigger.onCaptureStarted()
        } catch (e: ServiceStartDeniedException) {
            onStartFailureLocked(StartFailure.FGS_NOT_ALLOWED)
        }
    }

    private suspend fun stopCapture(endReason: SessionEndReason) {
        endGrace()
        coordinator.end(endReason)
        launcher.stop()
        segmentationTrigger.onCaptureStopped()
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
        health.reportOutage(issue, outageNotBefore(effective))
    }

    /**
     * Earliest time an outage may be dated from: the start of the active stretch, but never over time that was
     * captured (last session end) or deliberately paused (end of the last manual override). Now if unknown.
     */
    private suspend fun outageNotBefore(effective: EffectiveSchedule): Long {
        val now = clock.nowMs()
        val since = effective.activeSince?.toEpochMilli() ?: return now
        val lastSessionEnd = trackingRepo.sessionsInRange(since, now).mapNotNull { it.end }.filter { it <= now }.maxOrNull()
        val lastOverrideEnd = trackingRepo.overridesInRange(since, now).mapNotNull { it.end }.filter { it <= now }.maxOrNull()
        return maxOf(since, lastSessionEnd ?: since, lastOverrideEnd ?: since)
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
