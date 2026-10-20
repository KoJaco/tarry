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
    private val permissions: LocationPermissionChecker,
    private val windowChecker: ScheduleWindowChecker,
    private val health: HealthGapRecorder,
    private val clock: TrackingClock,
    @param:CaptureScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var session: TrackingSession? = null
    private var machine: CaptureModeMachine? = null
    private var filter = PingFilter(config)
    private var activity: ActivityType? = null
    private var manualStartPending = false
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
        manualStartPending = trigger == SessionTrigger.MANUAL
        filter = PingFilter(config)
        statusStore.setActiveSessionId(opened.id)
        statusStore.setLastHeartbeat(now)
        try {
            // The service is up, so the outage (if any) is over; the health check re-raises anything still broken.
            health.closeOutage(now)
            val m = CaptureModeMachine(config, activityRecognitionAvailable = registerActivity())
            machine = m
            apply(m.start())
        } catch (t: Throwable) {
            // Never leave a session open without a machine: fixes would be ignored while the heartbeat looks alive.
            session = null
            machine = null
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
        locationJob?.cancel()
        locationJob = null
        trackingRepo.saveSession(open.copy(end = clock.nowMs(), endReason = reason))
        statusStore.clearSessionState()
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
        val accepted = ArrayList<LocationPing>()
        var latest = Long.MIN_VALUE
        for (fix in fixes.sortedBy { it.timeMs }) {
            latest = maxOf(latest, fix.timeMs)
            val decision = filter.offer(fix, m.mode)
            if (decision == FilterDecision.DROP_DISPLACEMENT) {
                // Not saved, but the machine still needs it (stationary rule); every delivered fix is a heartbeat.
                apply(m.onAcceptedFix(fix))
                continue
            }
            if (!decision.accepted) continue
            val machineSource = m.takeNextPingSource()
            val source = if (manualStartPending) PingSource.MANUAL_START else machineSource
            manualStartPending = false
            accepted += LocationPing(
                timestamp = fix.timeMs,
                zoneId = clock.zone().id,
                lat = fix.lat,
                lng = fix.lng,
                accuracyM = fix.accuracyM,
                speedMps = fix.speedMps,
                bearing = fix.bearing,
                activity = activity,
                source = source,
                sessionId = open.id,
            )
            apply(m.onAcceptedFix(fix))
        }
        if (accepted.isNotEmpty()) trackingRepo.savePings(accepted)
        // Every fix or batch counts as a heartbeat, even when all were filtered out.
        if (latest != Long.MIN_VALUE) {
            statusStore.setLastHeartbeat(latest)
            // A fix arrived, so any open outage (for example NO_FIX) is over. Attention-only causes stay.
            health.closeOutage(latest)
        }
    }

    suspend fun onActivity(type: ActivityType) = mutex.withLock {
        val m = machine ?: return@withLock
        activity = type
        apply(m.onActivity(type))
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
     * Restarts location updates if the request died (a non-security failure ended the collector) while a session is
     * open. Called by every reconcile and health check, so an open session is never left without updates.
     */
    suspend fun ensureLocationUpdates() = mutex.withLock {
        if (session == null || locationJob?.isActive == true) return@withLock
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
        val active = if (now > closedAt) {
            windowChecker.activeIntervals(Instant.ofEpochMilli(closedAt), Instant.ofEpochMilli(now))
        } else {
            emptyList()
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
