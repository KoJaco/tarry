package me.tarry.app.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tarry.core.data.db.entity.Gap
import me.tarry.core.data.db.entity.LocationPing
import me.tarry.core.data.db.entity.TrackingSession
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.repository.TimelineRepository
import me.tarry.core.data.repository.TrackingRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The logic of one capture session, free of Service types so it runs on the JVM with fakes.
 * All state changes are serialised by a mutex; [TrackingController] and [TrackingService] drive it.
 *
 * Lifecycle: [begin] opens a session and starts the machine in MOVING; fixes, activity transitions
 * and geofence exits arrive through [onFixes] / [onActivity] / [onGeofenceExit]; [end] closes it.
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    private val config: TrackingConfig,
    private val trackingRepo: TrackingRepository,
    private val timelineRepo: TimelineRepository,
    private val statusStore: TrackingStatusStore,
    private val locationClient: LocationClient,
    private val activityClient: ActivityTransitionClient,
    private val geofenceClient: GeofenceClient,
    private val stayRadius: StayRadiusSource,
    private val permissions: LocationPermissionChecker,
    private val windowChecker: ScheduleWindowChecker,
    private val health: HealthGapRecorder,
    private val clock: TrackingClock,
    @param:CaptureScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    @Volatile
    private var session: TrackingSession? = null
    private var machine: CaptureModeMachine? = null
    private var filter = PingFilter(config)
    private var activity: ActivityType? = null
    private var lastActivityEventMs = Long.MIN_VALUE
    private var manualStartPending = false
    private var activityRegistered = false
    private var graceWatch = false
    private var locationJob: Job? = null
    private var lastLocationParams: LocationRequestParams? = null

    /**
     * Set by [TrackingController] so a capture failure that must also stop the service goes through the single
     * start/stop entry point. Without it (plain coordinator tests) the session is just ended here.
     */
    @Volatile
    var failureListener: (suspend (sessionId: Long, issue: HealthIssue) -> Unit)? = null

    /** True while a session is open in this process. */
    val isActive: Boolean get() = session != null

    /** The id of the open session, or null. */
    val activeSessionId: Long? get() = session?.id

    /** What started the open session, or null. */
    val activeTrigger: SessionTrigger? get() = session?.trigger

    /**
     * Called (from the capture scope) once a window-end grace is being watched and the machine reports a stay of at
     * least [TrackingConfig.windowEndSettleMs]. Set by [TrackingController].
     */
    @Volatile
    var settleListener: (suspend () -> Unit)? = null

    /** True when capture is STATIONARY and that stay has lasted at least [TrackingConfig.windowEndSettleMs]. */
    suspend fun isSettled(): Boolean = mutex.withLock { settledLocked() }

    /** Starts or stops reporting settled stays through [settleListener]. Reports at once if already settled. */
    suspend fun watchSettle(on: Boolean) = mutex.withLock {
        graceWatch = on
        notifyIfSettledLocked()
    }

    private fun settledLocked(): Boolean {
        val since = machine?.takeIf { it.mode == CaptureMode.STATIONARY }?.stationarySinceMs ?: return false
        return clock.nowMs() - since >= config.windowEndSettleMs
    }

    private fun notifyIfSettledLocked() {
        if (graceWatch && settledLocked()) scope.launch { settleListener?.invoke() }
    }

    /**
     * Opens a session for [trigger]. A no-op if one is already running. A stale open session from a
     * previous process is closed first (see [recoverCrashedSession]).
     */
    suspend fun begin(trigger: SessionTrigger) = mutex.withLock {
        if (session != null) return@withLock
        recoverLocked()
        val now = clock.nowMs()
        val draft = TrackingSession(start = now, zoneId = clock.zone().id, trigger = trigger)
        val opened = draft.copy(id = trackingRepo.saveSession(draft))
        session = opened
        activity = null
        lastActivityEventMs = Long.MIN_VALUE
        manualStartPending = trigger == SessionTrigger.MANUAL
        graceWatch = false
        filter = PingFilter(config)
        statusStore.setActiveSessionId(opened.id)
        statusStore.setLastHeartbeat(now)
        try {
            // The service is up, so the outage (if any) is over; the health check re-raises anything still broken.
            health.closeOutage(now)
            activityRegistered = registerActivity()
            val m = CaptureModeMachine(config, activityRecognitionAvailable = activityRegistered)
            m.stayRadiusM = stayRadius.stayRadiusM()
            machine = m
            apply(m.start())
        } catch (t: Throwable) {
            // Never leave a session open without a machine: fixes would be ignored while the heartbeat looks alive.
            session = null
            machine = null
            activityRegistered = false
            locationJob?.cancel()
            locationJob = null
            withContext(NonCancellable) {
                trackingRepo.saveSession(opened.copy(end = clock.nowMs(), endReason = SessionEndReason.KILLED))
                statusStore.clearSessionState()
                // Best effort: whatever the machine registered before failing must not outlive the session.
                runCatching { geofenceClient.remove() }
                runCatching { activityClient.unregister() }
            }
            throw t
        }
    }

    /** Activity Recognition is optional: any failure means distance-only gating. */
    private suspend fun registerActivity(): Boolean = try {
        activityClient.register()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Closes the session with [reason] and tears down location, geofence and activity updates. */
    suspend fun end(reason: SessionEndReason) = mutex.withLock {
        val open = session ?: return@withLock
        session = null
        machine = null
        activityRegistered = false
        locationJob?.cancel()
        locationJob = null
        trackingRepo.saveSession(open.copy(end = clock.nowMs(), endReason = reason))
        statusStore.clearSessionState()
        statusStore.setFinishingUp(false)
        geofenceClient.remove()
        activityClient.unregister()
    }

    /**
     * Closes a session left open by a dead process at its last ping time with KILLED and, if that
     * span was inside a window, writes a SERVICE_KILLED [Gap] from there to now. No-op while a
     * session is live in this process.
     */
    suspend fun recoverCrashedSession() = mutex.withLock { recoverLocked() }

    /** The ATTENTION cause for a failed `location` FGS start (missing fine or background location). */
    fun locationPermissionFailureCause(): AttentionCause = permissionCause()

    suspend fun onFixes(fixes: List<Fix>) = mutex.withLock {
        val open = session ?: return@withLock
        val m = machine ?: return@withLock
        m.stayRadiusM = stayRadius.stayRadiusM()
        val accepted = ArrayList<LocationPing>()
        var latest = Long.MIN_VALUE
        for (fix in fixes.sortedBy { it.timeMs }) {
            latest = maxOf(latest, fix.timeMs)
            if (!filter.offer(fix).accepted) continue
            // Step first: a safety-net exit triggered by this very fix tags it GEOFENCE_EXIT.
            apply(m.onAcceptedFix(fix))
            val machineSource = m.takeNextPingSource()
            val source = if (manualStartPending) PingSource.MANUAL_START else machineSource
            manualStartPending = false
            accepted += pingOf(fix, source, open.id, activity)
        }
        if (accepted.isNotEmpty()) trackingRepo.savePings(accepted)
        // Every fix or batch counts as a heartbeat, even when all were filtered out.
        if (latest != Long.MIN_VALUE) {
            statusStore.setLastHeartbeat(latest)
            // A fix arrived, so any open outage (for example NO_FIX) is over. Attention-only causes stay.
            health.closeOutage(latest)
        }
        notifyIfSettledLocked()
    }

    private fun pingOf(fix: Fix, source: PingSource, sessionId: Long, activity: ActivityType?) = LocationPing(
        timestamp = fix.timeMs,
        zoneId = clock.zone().id,
        lat = fix.lat,
        lng = fix.lng,
        accuracyM = fix.accuracyM,
        speedMps = fix.speedMps,
        bearing = fix.bearing,
        activity = activity,
        source = source,
        sessionId = sessionId,
    )

    /**
     * An Activity Recognition transition. [eventAgeMs] is how long ago the event happened when it was received (from its
     * elapsed-realtime stamp). Every transition is logged for the Sessions screen (counted as ignored when it was not applied).
     * A late event is still the newest word on the activity, so it is applied unless an
     * equal or newer event was already applied. The ping's activity is only ever what AR reported; it is never
     * inferred from GPS speed.
     */
    suspend fun onActivity(type: ActivityType, eventAgeMs: Long = 0L) = mutex.withLock {
        val open = session ?: return@withLock
        val m = machine ?: return@withLock
        val now = clock.nowMs()
        val eventMs = now - eventAgeMs
        val newest = eventMs > lastActivityEventMs
        statusStore.recordArTransition(open.id, type, eventMs, now, ignored = !newest)
        if (!newest) return@withLock
        lastActivityEventMs = eventMs
        m.stayRadiusM = stayRadius.stayRadiusM()
        activity = type
        apply(m.onActivity(type))
        notifyIfSettledLocked()
    }

    suspend fun onGeofenceExit() = mutex.withLock {
        val m = machine ?: return@withLock
        apply(m.onGeofenceExit())
    }

    private suspend fun apply(step: MachineStep) {
        statusStore.setCaptureMode(step.mode)
        for (effect in step.effects) {
            when (effect) {
                is CaptureEffect.RequestLocation -> startLocation(effect.params)
                is CaptureEffect.AddStayGeofence -> addGeofence(effect)
                CaptureEffect.RemoveGeofence -> geofenceClient.remove()
            }
        }
    }

    private suspend fun addGeofence(effect: CaptureEffect.AddStayGeofence) {
        try {
            geofenceClient.addStayGeofence(effect.lat, effect.lng, effect.radiusM)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The machine's own distance check (fix beyond the radius) still exits STATIONARY, and a
            // capture outage is caught by the heartbeat check. Capture itself continues.
        }
    }

    /**
     * Keeps an open session fully armed: restarts location updates if the request died (a non-security failure
     * ended the collector) and retries Activity Recognition if it is not registered. Called by every reconcile, by the
     * periodic health check (at most 15 min) and when the app resolves a health state (for example after an in-app
     * permission grant). AR stays optional, so a failed retry is silent.
     */
    suspend fun ensureCapture() = mutex.withLock {
        if (session == null) return@withLock
        if (!activityRegistered && registerActivity()) {
            activityRegistered = true
            machine?.onActivityRecognitionAvailable()
        }
        if (locationJob?.isActive == true) return@withLock
        lastLocationParams?.let(::startLocation)
    }

    private fun startLocation(params: LocationRequestParams) {
        lastLocationParams = params
        val sessionId = session?.id
        val previous = locationJob
        locationJob = scope.launch {
            try {
                locationClient.updates(params).collect { batch ->
                    // A batch can trigger a new request that cancels this job; finish saving it first.
                    withContext(NonCancellable) { onFixes(batch) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                scope.launch { onPermissionLost(sessionId) }
            } catch (e: Exception) {
                // Rule 6: record the outage now (Gap, ATTENTION and alert) rather than waiting for the heartbeat check.
                health.reportOutage(HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.SERVICE_KILLED))
            }
        }
        previous?.cancel()
    }

    private suspend fun onPermissionLost(sessionId: Long?) {
        // A new session may have started since this request died: it is not ours to stop.
        if (sessionId == null || session?.id != sessionId) return
        val issue = HealthIssue(permissionCause(), GapCause.PERMISSION_LOST)
        val listener = failureListener
        if (listener != null) {
            // The controller reports the outage, then ends the session and stops the service (no zombie FGS).
            listener(sessionId, issue)
        } else {
            // Reported before end(), which clears the heartbeat the Gap starts from.
            health.reportOutage(issue)
            end(SessionEndReason.PERMISSION_LOST)
        }
    }

    private fun permissionCause(): AttentionCause =
        if (permissions.hasFineLocation()) AttentionCause.NO_BACKGROUND_LOCATION else AttentionCause.NO_FINE_LOCATION

    private suspend fun recoverLocked() {
        if (session != null) return
        val open = trackingRepo.openSession() ?: return
        val now = clock.nowMs()
        val lastPing = trackingRepo.pingsForSession(open.id).lastOrNull()?.timestamp ?: open.start
        val closedAt = minOf(lastPing, now)
        trackingRepo.saveSession(open.copy(end = closedAt, endReason = SessionEndReason.KILLED))
        // The dead time counts only where capture should have been running: a phone that died at 15:00 and
        // booted at 06:50 next day lost 15:00-17:00, not the night.
        val windows = if (now > closedAt) {
            windowChecker.activeIntervals(Instant.ofEpochMilli(closedAt), Instant.ofEpochMilli(now))
        } else {
            emptyList()
        }
        // A session that died during the window-end grace was meant to run until the grace cap (or the next window):
        // that dead time is lost capture too (rule 6), not only the scheduled windows.
        val status = statusStore.current()
        val graceStart = status.finishingUpSinceMs?.takeIf { status.finishingUp }
        val graceEnd = graceStart?.let {
            minOf(now, it + config.windowEndGraceMaxMs, windows.firstOrNull()?.start?.toEpochMilli() ?: Long.MAX_VALUE)
        }
        val active = if (graceEnd != null && graceEnd > closedAt) {
            listOf(WindowBounds(Instant.ofEpochMilli(closedAt), Instant.ofEpochMilli(graceEnd))) + windows
        } else {
            windows
        }
        // An open health Gap (started at the last heartbeat) absorbs the interval that touches it; the rest are ours.
        for (interval in health.adoptOpenGap(active)) {
            timelineRepo.saveGap(
                Gap(
                    start = interval.start.toEpochMilli(),
                    end = interval.end.toEpochMilli(),
                    zoneId = clock.zone().id,
                    cause = GapCause.SERVICE_KILLED,
                    sessionId = open.id,
                    updatedAt = now,
                ),
            )
        }
        statusStore.clearSessionState()
        // A dead process can leave its stay geofence and activity registration behind.
        geofenceClient.remove()
        activityClient.unregister()
    }
}
