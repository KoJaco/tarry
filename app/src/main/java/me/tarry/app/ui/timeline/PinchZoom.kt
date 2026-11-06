package me.tarry.app.ui.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.tarry.app.ui.components.rememberReducedMotion

/**
 * Two-finger pinch on the day canvas. It only looks at the Initial pass and does nothing while one finger is down, so
 * scrolling, long-press selection, block drags and resize handles behave as before. Once a second finger lands it consumes
 * every change until all fingers are up, which cancels the children's detectors and stops the page scroll.
 *
 * [onStart] fires once per pinch. [onPinch] gets the zoom ratio since the last event and the centroid's y in px from the
 * top of this element. [onEnd] gets the last centroid y when the fingers lift.
 */
internal fun Modifier.pinchZoom(
    onStart: () -> Unit,
    onPinch: (ratio: Float, focalY: Float) -> Unit,
    onEnd: (focalY: Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        var focalY = 0f
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (!pinching && pressed >= 2) {
                    pinching = true
                    onStart()
                }
                if (pinching) {
                    if (pressed >= 2) {
                        val ratio = event.verticalZoom()
                        focalY = event.calculateCentroid(useCurrent = true).y
                        if (ratio != 1f) onPinch(ratio, focalY)
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            // Also runs when the gesture is cancelled, so the zoom never stays stuck mid-pinch.
            if (pinching) onEnd(focalY)
        }
    }
}

private const val ZoomAnimationMillis = 200

/**
 * The timeline's zoom: the live [zoom] factor (continuous during a pinch, always a level's factor at rest) and the
 * [settled] level, which is what snapping, the pill and the saved setting use. Pinches and menu changes both move the
 * scroll so the time under the fingers (or the viewport centre) stays put.
 */
@Stable
internal class ZoomState(
    private val scroll: ScrollState,
    private val scope: CoroutineScope,
    initial: ZoomLevel,
    private val reducedMotion: () -> Boolean,
    private val onLevelChange: () -> (ZoomLevel) -> Unit,
    private val onInteraction: () -> Unit,
) {
    var zoom by mutableFloatStateOf(initial.factor)
        private set
    var settled by mutableStateOf(initial)
        private set
    var pinching by mutableStateOf(false)
        private set

    /** The scrolling viewport and the canvas box, for turning a viewport position into a canvas position. */
    var viewport: LayoutCoordinates? = null
    var canvas: LayoutCoordinates? = null

    private var animation: Job? = null
    private var correction: Job? = null

    /** A level that arrived from the saved setting (not from this screen): adopt it unless the user is mid-change. */
    fun adopt(level: ZoomLevel) {
        if (pinching || animation?.isActive == true || level == settled) return
        settled = level
        zoom = level.factor
    }

    fun pinchStart() {
        animation?.cancel()
        pinching = true
        onInteraction()
    }

    fun pinch(ratio: Float, focalY: Float) = apply(zoom * ratio, focalY)

    fun pinchEnd(focalY: Float) {
        pinching = false
        val canvasTop = canvas?.takeIf { it.isAttached }?.positionInRoot()?.y ?: return settle(nearestLevel(zoom), 0f)
        val viewportTop = viewport?.takeIf { it.isAttached }?.positionInRoot()?.y ?: 0f
        settle(nearestLevel(zoom), focalY + canvasTop - viewportTop)
    }

    /** The Zoom menu: animates to [level] around the viewport's centre. */
    fun chooseLevel(level: ZoomLevel) {
        onInteraction()
        settle(level, (viewport?.takeIf { it.isAttached }?.size?.height ?: 0) / 2f)
    }

    /** Records [level] as settled, saves it, and animates the zoom there, keeping the viewport y [viewportY] (px) fixed. */
    private fun settle(level: ZoomLevel, viewportY: Float) {
        settled = level
        onLevelChange()(level)
        // The canvas top in scroll-content px is constant while the zoom animates; the focal point is re-derived each step.
        val canvasTop = canvas?.takeIf { it.isAttached }?.positionInRoot()?.y ?: 0f
        val viewportTop = viewport?.takeIf { it.isAttached }?.positionInRoot()?.y ?: 0f
        val canvasTopInContent = canvasTop - viewportTop + scroll.value
        fun focal() = scroll.value + viewportY - canvasTopInContent
        animation?.cancel()
        animation = scope.launch {
            if (reducedMotion()) {
                apply(level.factor, focal())
            } else {
                animate(zoom, level.factor, animationSpec = tween(ZoomAnimationMillis)) { v, _ -> apply(v, focal()) }
            }
            zoom = level.factor
        }
    }

    /** Sets the zoom (clamped) and scrolls so the content [focal] px below the canvas top stays where it is on screen. */
    private fun apply(target: Float, focal: Float) {
        val old = zoom
        val new = target.coerceIn(TimelineZoomConfig.MinFactor, TimelineZoomConfig.MaxFactor)
        if (new == old) return
        zoom = new
        val want = anchoredScroll(scroll.value, focal, old, new).coerceAtLeast(0)
        scroll.dispatchRawDelta((want - scroll.value).toFloat())
        // The new content height is only known after the next layout; a target past the old maximum is finished then.
        if (scroll.value != want) {
            correction?.cancel()
            correction = scope.launch {
                withFrameNanos { }
                scroll.scrollTo(want.coerceIn(0, scroll.maxValue))
            }
        }
    }
}

@Composable
internal fun rememberZoomState(
    scroll: ScrollState,
    savedLevel: ZoomLevel,
    onLevelChange: (ZoomLevel) -> Unit,
    onInteraction: () -> Unit,
): ZoomState {
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val reducedNow by rememberUpdatedState(reduced)
    val changeNow by rememberUpdatedState(onLevelChange)
    val interactionNow by rememberUpdatedState(onInteraction)
    val state = remember(scroll) {
        ZoomState(scroll, scope, savedLevel, { reducedNow }, { changeNow }, { interactionNow() })
    }
    LaunchedEffect(savedLevel) { state.adopt(savedLevel) }
    return state
}

/**
 * The change in the fingers' vertical spread since the last event (1 when there is none). Only the vertical spread
 * counts: the timeline only stretches vertically, so a sideways pinch must not zoom it.
 */
internal fun PointerEvent.verticalZoom(): Float {
    val moving = changes.filter { it.pressed && it.previousPressed }
    if (moving.size < 2) return 1f
    val currentCentre = moving.sumOf { it.position.y.toDouble() } / moving.size
    val previousCentre = moving.sumOf { it.previousPosition.y.toDouble() } / moving.size
    val current = moving.sumOf { abs(it.position.y - currentCentre) } / moving.size
    val previous = moving.sumOf { abs(it.previousPosition.y - previousCentre) } / moving.size
    return if (current > 0.0 && previous > 0.0) (current / previous).toFloat() else 1f
}
