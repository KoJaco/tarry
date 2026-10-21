package me.tarry.app.tracking

import java.time.Instant

class FakeAlarmScheduler(var exact: Boolean = true) : ScheduleAlarmScheduler {
    val armed = mutableListOf<Instant?>()

    override fun canScheduleExact() = exact

    override fun arm(at: Instant?): Boolean {
        armed += at
        return exact
    }
}

class FakeSegmentationTrigger : SegmentationTrigger {
    var windowEnds = 0
        private set

    var captureStarts = 0
        private set

    var captureStops = 0
        private set

    override suspend fun onWindowEnd() {
        windowEnds++
    }

    override suspend fun onCaptureStarted() {
        captureStarts++
    }

    override suspend fun onCaptureStopped() {
        captureStops++
    }
}
