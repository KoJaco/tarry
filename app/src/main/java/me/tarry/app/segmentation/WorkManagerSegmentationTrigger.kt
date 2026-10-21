package me.tarry.app.segmentation

import me.tarry.app.tracking.SegmentationTrigger
import javax.inject.Inject

/** Turns capture events into segmentation work. */
class WorkManagerSegmentationTrigger @Inject constructor(
    private val scheduler: SegmentationScheduler,
) : SegmentationTrigger {
    override suspend fun onWindowEnd() = scheduler.enqueueNow()

    override suspend fun onCaptureStarted() = scheduler.startPeriodic()

    override suspend fun onCaptureStopped() {
        scheduler.stopPeriodic()
        scheduler.enqueueNow()
    }
}
