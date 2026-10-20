package me.tarry.app.tracking

import android.util.Log
import kotlinx.coroutines.CancellationException
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
 * An uncaught exception in a capture coroutine is logged (no crash SDKs) rather than crashing the process. It is
 * recorded as an outage only while a session is live in this process: the scope is also shared with state and UI work, whose
 * failures are not capture outages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun createCaptureScope(
    recorder: () -> HealthGapRecorder,
    hasSession: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
): CoroutineScope {
    lateinit var scope: CoroutineScope
    val handler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Uncaught error in capture scope", e)
        scope.launch {
            try {
                if (hasSession()) {
                    recorder().reportOutage(HealthIssue(AttentionCause.SERVICE_NOT_RUNNING, GapCause.SERVICE_KILLED))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (inner: Throwable) {
                // Logged only: reporting must never feed back into this handler.
                Log.e(TAG, "Could not record the capture outage", inner)
            }
        }
    }
    scope = CoroutineScope(SupervisorJob() + dispatcher + handler)
    return scope
}

private const val TAG = "CaptureScope"
