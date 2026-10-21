package me.tarry.app.ui.timeline

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/** The fill colour a visit block is drawn with (job container, or `card` when it has no job). For tests. */
val BlockColorKey = SemanticsPropertyKey<Color>("TimelineBlockColor")
var SemanticsPropertyReceiver.blockColor: Color by BlockColorKey

/** Set on a visit block drawn with the dashed no-job outline. For tests. */
val BlockDashedKey = SemanticsPropertyKey<Boolean>("TimelineBlockDashed")
var SemanticsPropertyReceiver.blockDashed: Boolean by BlockDashedKey

/** Outage rects (dp, relative to the block's top-left) of the edge markers in a visit block. For tests. */
val InnerGapRectsKey = SemanticsPropertyKey<List<Rect>>("TimelineInnerGapRects")
var SemanticsPropertyReceiver.innerGapRects: List<Rect> by InnerGapRectsKey

/** Number of outage markers drawn inside a visit block. For tests. */
val InnerGapHatchKey = SemanticsPropertyKey<Int>("TimelineInnerGapHatch")
var SemanticsPropertyReceiver.innerGapHatch: Int by InnerGapHatchKey

/** Set on a gap block that draws the diagonal hatch. For tests. */
val GapHatchKey = SemanticsPropertyKey<Boolean>("TimelineGapHatch")
var SemanticsPropertyReceiver.gapHatch: Boolean by GapHatchKey
