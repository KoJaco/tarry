package me.tarry.app.tracking

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Suspends until a Play Services [Task] completes; avoids a dependency on coroutines-play-services. */
internal suspend fun Task<*>.awaitDone() {
    suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(Unit) }
        addOnFailureListener { c.resumeWithException(it) }
        addOnCanceledListener { c.cancel() }
    }
}
