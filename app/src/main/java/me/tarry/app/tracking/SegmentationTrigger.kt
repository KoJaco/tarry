package me.tarry.app.tracking

import javax.inject.Inject

/** Told about capture events so segmentation can run. The app binds the WorkManager implementation. */
interface SegmentationTrigger {
    suspend fun onWindowEnd()

    suspend fun onCaptureStarted()

    suspend fun onCaptureStopped()
}

/** Does nothing; for tests. */
class NoOpSegmentationTrigger @Inject constructor() : SegmentationTrigger {
    override suspend fun onWindowEnd() = Unit

    override suspend fun onCaptureStarted() = Unit

    override suspend fun onCaptureStopped() = Unit
}
