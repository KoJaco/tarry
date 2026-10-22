package me.tarry.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The pure index and bounds logic of the reorderable list, plus the commit rules of [ReorderState]. */
class ReorderStateTest {
    // Three 50 px rows, 10 px apart: tops 0, 60, 120.
    private val bounds = listOf(ItemBounds(0f, 50f), ItemBounds(60f, 50f), ItemBounds(120f, 50f))
    private val keys = listOf("a", "b", "c")

    @Test
    fun movedPutsTheItemAtTheNewIndex() {
        assertThat(keys.moved(0, 2)).containsExactly("b", "c", "a").inOrder()
        assertThat(keys.moved(2, 0)).containsExactly("c", "a", "b").inOrder()
        assertThat(keys.moved(1, 1)).containsExactly("a", "b", "c").inOrder()
        assertThat(keys).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun swapTargetOnlyMovesOnceTheCentreCrossesTheNeighboursMidpoint() {
        // Row b's midpoint is 85, row a's is 25.
        assertThat(swapTarget(bounds, 0, centre(top = 30f))).isEqualTo(0)
        assertThat(swapTarget(bounds, 0, 84.5f)).isEqualTo(0)
        assertThat(swapTarget(bounds, 0, 85f)).isEqualTo(1)
        assertThat(swapTarget(bounds, 1, 25f)).isEqualTo(0)
        assertThat(swapTarget(bounds, 1, 25.5f)).isEqualTo(1)
    }

    @Test
    fun theEndsHaveNoNeighbourToSwapWith() {
        assertThat(swapTarget(bounds, 0, -500f)).isEqualTo(0)
        assertThat(swapTarget(bounds, 2, 5_000f)).isEqualTo(2)
    }

    @Test
    fun swapBoundsTradesAdjacentItemsAndKeepsTheGap() {
        val tall = listOf(ItemBounds(0f, 40f), ItemBounds(50f, 100f))

        val swapped = swapBounds(tall, 0, 1)

        assertThat(swapped).containsExactly(ItemBounds(0f, 100f), ItemBounds(110f, 40f)).inOrder()
        assertThat(swapBounds(swapped, 1, 0)).isEqualTo(tall)
    }

    @Test
    fun draggingBelowTheNeighboursMidpointSwapsAndKeepsTheItemUnderTheFinger() {
        val start = DragModel(keys, bounds, "a", visualTop = 0f)

        val small = start.dragBy(40f)
        assertThat(small.order).containsExactly("a", "b", "c").inOrder()
        assertThat(small.offset).isEqualTo(40f)

        val crossed = small.dragBy(30f)
        assertThat(crossed.order).containsExactly("b", "a", "c").inOrder()
        // Drawn where the finger put it (70), though its slot is now at 60.
        assertThat(crossed.visualTop).isEqualTo(70f)
        assertThat(crossed.offset).isEqualTo(10f)
    }

    @Test
    fun aBigDragSwapsPastSeveralNeighboursAndIsClampedToTheList() {
        val dropped = DragModel(keys, bounds, "a", visualTop = 0f).dragBy(10_000f)

        assertThat(dropped.order).containsExactly("b", "c", "a").inOrder()
        assertThat(dropped.visualTop).isEqualTo(120f)
        assertThat(dropped.offset).isEqualTo(0f)

        val up = DragModel(keys, bounds, "c", visualTop = 120f).dragBy(-10_000f)
        assertThat(up.order).containsExactly("c", "a", "b").inOrder()
        assertThat(up.visualTop).isEqualTo(0f)
    }

    @Test
    fun draggingBackRestoresTheOriginalOrder() {
        val there = DragModel(keys, bounds, "b", visualTop = 60f).dragBy(60f)
        assertThat(there.order).containsExactly("a", "c", "b").inOrder()

        val back = there.dragBy(-60f)

        assertThat(back.order).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun unevenHeightsSwapAtTheNeighboursOwnMidpoint() {
        val uneven = listOf(ItemBounds(0f, 40f), ItemBounds(40f, 120f), ItemBounds(160f, 40f))

        // Dragging a (centre 20 + dy) past b's midpoint (100) reaches it at dy = 80.
        assertThat(DragModel(keys, uneven, "a", 0f).dragBy(79f).order).containsExactly("a", "b", "c").inOrder()
        assertThat(DragModel(keys, uneven, "a", 0f).dragBy(80f).order).containsExactly("b", "a", "c").inOrder()
    }

    @Test
    fun startNeedsTheBoundsOfEveryItem() {
        assertThat(DragModel.start(keys, mapOf("a" to bounds[0], "b" to bounds[1]), "a")).isNull()
        assertThat(DragModel.start(keys, keys.zip(bounds).toMap(), "zzz")).isNull()
        assertThat(DragModel.start(keys, keys.zip(bounds).toMap(), "b")!!.visualTop).isEqualTo(60f)
    }

    // region ReorderState

    private val commits = mutableListOf<List<String>>()

    private fun state(): ReorderState<String> = ReorderState<String> { commits += it }.also { s ->
        s.keys = keys
        keys.zip(bounds).forEach { (k, b) -> s.onMeasured(k, b) }
    }

    @Test
    fun aDropCommitsOnceWithTheNewOrder() {
        val s = state()

        s.startDrag("a")
        s.dragBy(40f)
        s.dragBy(40f)
        assertThat(commits).isEmpty()
        assertThat(s.order).containsExactly("b", "a", "c").inOrder()
        s.endDrag()

        assertThat(commits).containsExactly(listOf("b", "a", "c"))
        // Held until the upstream list catches up, so the row does not flash back.
        assertThat(s.order).containsExactly("b", "a", "c").inOrder()
        assertThat(s.draggingKey).isNull()
    }

    @Test
    fun aDropWithoutAnOrderChangeAndACancelCommitNothing() {
        val s = state()
        s.startDrag("a")
        s.dragBy(10f)
        s.endDrag()
        s.startDrag("a")
        s.dragBy(500f)
        s.cancelDrag()

        assertThat(commits).isEmpty()
        assertThat(s.order).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun theUpstreamListWinsOnceItCatchesUp() {
        val s = state()
        s.moveBy("a", 1)
        assertThat(s.order).containsExactly("b", "a", "c").inOrder()

        s.keys = listOf("b", "a", "c")
        s.clearPending()

        assertThat(s.order).containsExactly("b", "a", "c").inOrder()
        s.keys = listOf("a", "b", "c")
        assertThat(s.order).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun moveByCommitsImmediatelyAndStopsAtTheEnds() {
        val s = state()

        assertThat(s.canMove("a", -1)).isFalse()
        assertThat(s.canMove("c", 1)).isFalse()
        assertThat(s.canMove("b", -1)).isTrue()
        s.moveBy("a", -1)
        s.moveBy("c", 1)
        assertThat(commits).isEmpty()
        s.moveBy("b", 1)

        assertThat(commits).containsExactly(listOf("a", "c", "b"))
    }

    @Test
    fun aSecondDragCannotStartWhileOneIsActive() {
        val s = state()
        s.startDrag("a")
        s.startDrag("b")

        assertThat(s.draggingKey).isEqualTo("a")
    }

    // endregion

    private fun centre(top: Float) = top + 25f
}
