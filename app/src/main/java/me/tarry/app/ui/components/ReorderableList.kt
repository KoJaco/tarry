package me.tarry.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Hand-rolled reordering for a short vertical list (phase 4 T6). A long press on an item's handle picks it up, the item
 * follows the finger, neighbours swap when the dragged item's centre crosses their midpoint, and the order is committed
 * once on drop. [Modifier.reorderHandle] also exposes "Move up" / "Move down" custom actions (WCAG 2.5.7: a way to
 * reorder without dragging), which commit immediately. Nothing here animates, so it already respects reduced motion.
 *
 * It lays the items out in a plain `Column` rather than a `LazyColumn`: lazy lists re-anchor on the first visible key
 * when that item moves, which makes a dragged first row jump. These lists hold tens of rows at most.
 */

/** The vertical extent of one item, in the coordinates of the container that lays the items out. */
data class ItemBounds(val top: Float, val size: Float) {
    val bottom: Float get() = top + size
    val mid: Float get() = top + size / 2
}

/** A copy of the list with the item at [from] moved to [to]. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

/** The index the item at [from] takes next: one step up or down when its centre reached that neighbour's midpoint, else [from]. */
fun swapTarget(bounds: List<ItemBounds>, from: Int, draggedCenter: Float): Int = when {
    from > 0 && draggedCenter <= bounds[from - 1].mid -> from - 1
    from < bounds.lastIndex && draggedCenter >= bounds[from + 1].mid -> from + 1
    else -> from
}

/** [bounds] after the adjacent items at [a] and [b] trade places; the gap between them is kept. */
fun swapBounds(bounds: List<ItemBounds>, a: Int, b: Int): List<ItemBounds> {
    val lo = minOf(a, b)
    val hi = maxOf(a, b)
    val upper = bounds[lo]
    val lower = bounds[hi]
    val gap = lower.top - upper.bottom
    val out = bounds.toMutableList()
    out[lo] = ItemBounds(upper.top, lower.size)
    out[hi] = ItemBounds(upper.top + lower.size + gap, upper.size)
    return out
}

/**
 * A drag in progress, as pure data. [order] and [bounds] are positional (index = current position of the item).
 * [visualTop] is where the dragged item is drawn, in container coordinates; [offset] is how far that is from its slot.
 */
data class DragModel<K>(val order: List<K>, val bounds: List<ItemBounds>, val key: K, val visualTop: Float) {
    val index: Int get() = order.indexOf(key)

    /** Translation to apply to the dragged item so it is drawn at [visualTop]. */
    val offset: Float get() = visualTop - bounds[index].top

    /** Moves the dragged item by [dy] (clamped to the list) and swaps it past every neighbour whose midpoint it crossed. */
    fun dragBy(dy: Float): DragModel<K> {
        val size = bounds[index].size
        val top = (visualTop + dy).coerceIn(bounds.first().top, maxOf(bounds.first().top, bounds.last().bottom - size))
        var m = copy(visualTop = top)
        while (true) {
            val from = m.index
            val to = swapTarget(m.bounds, from, m.visualTop + size / 2)
            if (to == from) return m
            m = m.copy(order = m.order.moved(from, to), bounds = swapBounds(m.bounds, from, to))
        }
    }

    companion object {
        /** Null when any item's bounds are not known yet. */
        fun <K> start(order: List<K>, measured: Map<K, ItemBounds>, key: K): DragModel<K>? {
            val bounds = order.map { measured[it] ?: return null }
            val index = order.indexOf(key)
            if (index < 0) return null
            return DragModel(order, bounds, key, bounds[index].top)
        }
    }
}

/** State of one reorderable list. [onCommit] receives the full new order, once per drop or per "Move" action. */
@Stable
class ReorderState<K : Any> internal constructor(private val onCommit: (List<K>) -> Unit) {
    internal var keys: List<K> = emptyList()
    private var pending: List<K>? by mutableStateOf(null)
    private var drag: DragModel<K>? by mutableStateOf(null)
    private var startOrder: List<K> = emptyList()
    private val measured = HashMap<K, ItemBounds>()

    /**
     * What to draw: the order being dragged, else the committed order until the upstream list catches up (so a drop
     * does not flash back to the old order while the database write is in flight), else the upstream keys.
     */
    val order: List<K>
        get() = drag?.order ?: pending?.takeIf { it != keys && it.toSet() == keys.toSet() } ?: keys

    val draggingKey: K? get() = drag?.key

    /** Vertical translation for [key]: the drag offset for the dragged item, else 0. */
    fun offsetOf(key: K): Float = drag?.takeIf { it.key == key }?.offset ?: 0f

    internal fun clearPending() {
        pending = null
    }

    internal val hasPending: Boolean get() = pending != null

    internal fun onMeasured(key: K, bounds: ItemBounds) {
        if (drag?.key != key) measured[key] = bounds
    }

    fun startDrag(key: K) {
        if (drag != null) return
        startOrder = order
        drag = DragModel.start(startOrder, measured, key)
    }

    fun dragBy(dy: Float) {
        drag = drag?.dragBy(dy)
    }

    /** Drops the item: one commit, and only when the order actually changed. */
    fun endDrag() {
        val finished = drag ?: return
        drag = null
        if (finished.order != startOrder) commit(finished.order)
    }

    fun cancelDrag() {
        drag = null
    }

    fun canMove(key: K, delta: Int): Boolean = order.indexOf(key).let { it >= 0 && it + delta in order.indices }

    /** Moves [key] by [delta] places and commits immediately (the non-drag alternative). */
    fun moveBy(key: K, delta: Int) {
        if (!canMove(key, delta)) return
        val current = order
        commit(current.moved(current.indexOf(key), current.indexOf(key) + delta))
    }

    private fun commit(newOrder: List<K>) {
        pending = newOrder
        onCommit(newOrder)
    }
}

private const val PENDING_TIMEOUT_MS = 3_000L

/** [keys] is the upstream order (re-read on every composition); [onCommit] is called once per completed reorder. */
@Composable
fun <K : Any> rememberReorderState(keys: List<K>, onCommit: (List<K>) -> Unit): ReorderState<K> {
    val latest by rememberUpdatedState(onCommit)
    val state = remember { ReorderState<K> { latest(it) } }
    state.keys = keys
    // The upstream list changed (the commit landed, or something else edited it): the held order is stale.
    LaunchedEffect(keys) { state.clearPending() }
    // A commit that was rejected never changes the upstream list: stop holding the dropped order.
    LaunchedEffect(state.hasPending) {
        if (state.hasPending) {
            delay(PENDING_TIMEOUT_MS)
            state.clearPending()
        }
    }
    return state
}

/** Apply to each direct child of the `Column` that lays the items out: reports its bounds and follows the finger while dragged. */
fun <K : Any> Modifier.reorderItem(state: ReorderState<K>, key: K): Modifier = this
    // Before the layer, so the reported position never includes the drag translation.
    .onGloballyPositioned { c -> state.onMeasured(key, ItemBounds(c.positionInParent().y, c.size.height.toFloat())) }
    .zIndex(if (state.draggingKey == key) 1f else 0f)
    .graphicsLayer { translationY = state.offsetOf(key) }

/**
 * Apply to the drag handle (give it a 48 dp or larger target): long press then drag moves the item; the semantics are
 * [label] plus "Move up" / "Move down" custom actions for the moves that are possible.
 */
fun <K : Any> Modifier.reorderHandle(
    state: ReorderState<K>,
    key: K,
    label: String,
    moveUpLabel: String,
    moveDownLabel: String,
): Modifier = this
    .semantics {
        contentDescription = label
        customActions = buildList {
            if (state.canMove(key, -1)) add(CustomAccessibilityAction(moveUpLabel) { state.moveBy(key, -1); true })
            if (state.canMove(key, 1)) add(CustomAccessibilityAction(moveDownLabel) { state.moveBy(key, 1); true })
        }
    }
    .pointerInput(state, key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.startDrag(key) },
            onDrag = { change, amount ->
                change.consume()
                state.dragBy(amount.y)
            },
            onDragEnd = { state.endDrag() },
            onDragCancel = { state.cancelDrag() },
        )
    }

/** Archived and other receded rows. */
fun Modifier.receded(): Modifier = alpha(RECEDED_ALPHA)

/** Opacity of receded (archived) rows. 70% keeps `ink` text above 4.5:1 on `card` in both themes. */
const val RECEDED_ALPHA = 0.7f
