package me.tarry.app.tracking

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until a Play Services [Task] completes; avoids a dependency on coroutines-play-services.
 * A cancelled Task is a failure of the awaited operation (an exception), not cancellation of the caller.
 */
internal suspend fun Task<*>.awaitDone() {
    suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(Unit) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.resumeWithException(TaskCancelledException()) }
    }
}

/** A Play Services Task was cancelled. Deliberately not a coroutine `CancellationException`. */
internal class TaskCancelledException : Exception("Play Services task was cancelled")
