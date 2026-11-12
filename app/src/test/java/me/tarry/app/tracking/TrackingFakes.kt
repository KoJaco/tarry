package me.tarry.app.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.settings.PowerMode
import me.tarry.core.data.settings.Settings
import java.time.Instant
import java.time.ZoneId

class FakeLocationClient : LocationClient {
    val requests = mutableListOf<LocationRequestParams>()
    var activeCollectors = 0
        private set
    val batches = MutableSharedFlow<List<Fix>>()

    override fun updates(params: LocationRequestParams): Flow<List<Fix>> {
        requests += params
        activeCollectors++
        return batches.onCompletion { activeCollectors-- }
    }

    /** What [currentFix] returns; the test sets it (fix, timeout or denied). */
    var currentFixResult: CurrentFixResult = CurrentFixResult.Timeout

    /** When set, [currentFix] suspends until it completes, so a test can look at the running state. */
    var currentFixGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val currentFixRequests = mutableListOf<Pair<LocationPriority, Long>>()

    override suspend fun currentFix(priority: LocationPriority, timeoutMs: Long): CurrentFixResult {
        currentFixRequests += priority to timeoutMs
        currentFixGate?.await()
        return currentFixResult
    }
}

class FakeActivityClient(var available: Boolean = true) : ActivityTransitionClient {
    var registerCalls = 0
        private set

    var registered = false
        private set
    var unregisterCalls = 0
        private set

    override suspend fun register(): Boolean {
        registerCalls++
        registered = available
        return available
    }

    override suspend fun unregister() {
        registered = false
        unregisterCalls++
    }
}

class FakeGeofenceClient : GeofenceClient {
    data class Stay(val lat: Double, val lng: Double, val radiusM: Float)

    var stay: Stay? = null
        private set
    var removeCalls = 0
        private set

    override suspend fun addStayGeofence(lat: Double, lng: Double, radiusM: Float) {
        stay = Stay(lat, lng, radiusM)
    }

    override suspend fun remove() {
        stay = null
        removeCalls++
    }
}

class FakePermissions(var fine: Boolean = true) : LocationPermissionChecker {
    override fun hasFineLocation() = fine
}

class FakeClock(var now: Long = 1_000_000_000L, var zone: ZoneId = ZoneId.of("Australia/Sydney")) : TrackingClock {
    override fun nowMs() = now
    override fun zone() = zone
}

class FakeWindowChecker(var inside: Boolean = true) : ScheduleWindowChecker {
    val calls = mutableListOf<Pair<Instant, Instant>>()

    /** When set, returned as is; otherwise the whole range if [inside], else nothing. */
    var intervals: List<WindowBounds>? = null

    override suspend fun activeIntervals(from: Instant, to: Instant): List<WindowBounds> {
        calls += from to to
        return intervals ?: if (inside) listOf(WindowBounds(from, to)) else emptyList()
    }
}

class FakeLauncher(var denyStart: Boolean = false) : ServiceLauncher {
    val started: MutableList<SessionTrigger> = java.util.concurrent.CopyOnWriteArrayList()
    @Volatile var stopCalls = 0
        private set

    override fun start(trigger: SessionTrigger) {
        if (denyStart) throw ServiceStartDeniedException()
        started += trigger
    }

    override fun stop() {
        stopCalls++
    }
}

class FakePreconditions(var value: Preconditions = Preconditions()) : PreconditionChecker {
    override fun check() = value
}

class FakeAlertNotifier : HealthAlertNotifier {
    val posted: MutableList<AttentionCause> = java.util.concurrent.CopyOnWriteArrayList()
    var clearCalls = 0
        private set

    override fun post(cause: AttentionCause) {
        posted += cause
    }

    override fun clear() {
        clearCalls++
    }
}

class FakeStayRadius(var radiusM: Float = Settings.DEFAULT_STAY_RADIUS_M.toFloat()) : StayRadiusSource {
    override suspend fun stayRadiusM() = radiusM
}

class FakeWindowEndGrace(var maxMin: Int = Settings.DEFAULT_WINDOW_END_GRACE_MIN) : WindowEndGraceSource {
    override suspend fun graceMaxMin() = maxMin
}

class FakePowerSettings(
    mode: PowerMode = PowerMode.PRECISE,
    lowBatterySaver: Boolean = true,
) : PowerSettingsSource {
    val flow = kotlinx.coroutines.flow.MutableStateFlow(PowerSettings(mode, lowBatterySaver))
    override val settings: Flow<PowerSettings> get() = flow
}

class FakeBatterySource(var reading: BatteryReading? = null) : BatterySource {
    val flow = MutableSharedFlow<BatteryReading>(extraBufferCapacity = 8)
    override fun read() = reading
    override val changes: Flow<BatteryReading> get() = flow

    /** A platform change: the reading and the broadcast. */
    suspend fun change(r: BatteryReading) {
        reading = r
        flow.emit(r)
    }
}
