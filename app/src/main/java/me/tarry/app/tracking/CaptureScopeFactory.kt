package me.tarry.app.tracking

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.tarry.core.data.model.GapCause

/**
 * Builds the [CaptureScope]: one thread keeps fix batches, activity events and geofence exits in arrival order.
 * An uncaught exception in a capture coroutine is logged (no crash SDKs) and recorded as an outage rather than
 * crashing the process; the health check then restarts capture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun createCaptureScope(
    recorder: () -> HealthGapRecorder,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
): CoroutineScope {
    lateinit var scope: CoroutineScope
    val handler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Uncaught error in capture scope", e)
        scope.launch {
            try {
                recorder().reportOutage(HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.SERVICE_KILLED))
            } catch (inner: Exception) {
                Log.e(TAG, "Could not record the capture outage", inner)
            }
        }
    }
    scope = CoroutineScope(SupervisorJob() + dispatcher + handler)
    return scope
}

private const val TAG = "CaptureScope"
