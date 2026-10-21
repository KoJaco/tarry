package me.tarry.app.tracking

import javax.inject.Inject

/** Told when a scheduled window ends so segmentation can run. Implemented in phase 2b. */
interface SegmentationTrigger {
    suspend fun onWindowEnd()
}

/** 2a binding: segmentation does not exist yet. */
class NoOpSegmentationTrigger @Inject constructor() : SegmentationTrigger {
    override suspend fun onWindowEnd() = Unit
}
