package me.tarry.app.ui.catalog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.awaitCancellation

/** Workers hang instead of running segmentation: the catalog tests only care that undo asked for a run. */
internal object HangingWorkerFactory : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
        object : CoroutineWorker(appContext, workerParameters) {
            override suspend fun doWork(): Result = awaitCancellation()
        }
}
