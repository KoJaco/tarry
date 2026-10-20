package me.tarry.app.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import me.tarry.core.data.model.SessionTrigger
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
}

class FakeActivityClient(private val available: Boolean = true) : ActivityTransitionClient {
    var registered = false
        private set
    var unregisterCalls = 0
        private set

    override suspend fun register(): Boolean {
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

    override suspend fun isInsideWindow(from: Instant, to: Instant): Boolean {
        calls += from to to
        return inside
    }
}

class FakeLauncher(var denyStart: Boolean = false) : ServiceLauncher {
    val started = mutableListOf<SessionTrigger>()
    var stopCalls = 0
        private set

    override fun start(trigger: SessionTrigger) {
        if (denyStart) throw ServiceStartDeniedException()
        started += trigger
    }

    override fun stop() {
        stopCalls++
    }
}
