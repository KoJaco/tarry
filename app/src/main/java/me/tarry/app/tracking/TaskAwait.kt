package me.tarry.app.tracking

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val listenerExecutor = Dispatchers.Default.asExecutor()

/**
 * Suspends until a Play Services [Task] completes; avoids a dependency on coroutines-play-services.
 * A cancelled Task is a failure of the awaited operation (an exception), not cancellation of the caller.
 */
internal suspend fun Task<*>.awaitDone() {
    suspendCancellableCoroutine { c ->
        // Off the main thread: a caller blocking the main thread (TrackingService.fail) must not wait on its own looper.
        addOnSuccessListener(listenerExecutor) { c.resume(Unit) }
        addOnFailureListener(listenerExecutor) { c.resumeWithException(it) }
        addOnCanceledListener(listenerExecutor) { c.resumeWithException(TaskCancelledException()) }
    }
}

/** A Play Services Task was cancelled. Deliberately not a coroutine `CancellationException`. */
internal class TaskCancelledException : Exception("Play Services task was cancelled")
