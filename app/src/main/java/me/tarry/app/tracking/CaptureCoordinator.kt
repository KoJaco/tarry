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

    /** True while a session is open in this process. */
    val isActive: Boolean get() = session != null

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
        // The service is up, so the outage (if any) is over; the health check re-raises anything still broken.
        health.recover(now)
        val m = CaptureModeMachine(config, activityRecognitionAvailable = activityClient.register())
        machine = m
        apply(m.start())
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
            if (!filter.offer(fix, m.mode).accepted) continue
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
            // A fix arrived, so any open outage (for example NO_FIX) is over.
            health.recover(latest)
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

    private fun startLocation(params: LocationRequestParams) {
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
                scope.launch { onPermissionLost() }
            } catch (e: Exception) {
                // Heartbeat goes stale and T8 reports it; surface the cause now rather than staying silent.
                statusStore.setAttentionCause(AttentionCause.SERVICE_NOT_RUNNING)
            }
        }
        previous?.cancel()
    }

    private suspend fun onPermissionLost() {
        // Reported before end(), which clears the heartbeat the Gap starts from.
        health.reportOutage(HealthIssue(permissionCause(), GapCause.PERMISSION_LOST))
        end(SessionEndReason.PERMISSION_LOST)
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
        if (now > closedAt &&
            windowChecker.isInsideWindow(Instant.ofEpochMilli(closedAt), Instant.ofEpochMilli(now))
        ) {
            // An open health Gap already covers this outage (it started at the last heartbeat): extend it
            // instead of writing a second, overlapping Gap.
            if (!health.extendOpenGap(now)) {
                timelineRepo.saveGap(
                    Gap(
                        start = closedAt,
                        end = now,
                        zoneId = clock.zone().id,
                        cause = GapCause.SERVICE_KILLED,
                        sessionId = open.id,
                        updatedAt = now,
                    ),
                )
            }
        }
        statusStore.clearSessionState()
        // A dead process can leave its stay geofence and activity registration behind.
        geofenceClient.remove()
        activityClient.unregister()
    }
}
