package me.tarry.app.ui.timeline

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JobColumnPlanTest {
    private val min = 60_000L

    private fun job(id: String, start: Long, end: Long, lane: Int, count: Int) =
        PlacedJob(JobBlockItem(id, "j", id, 0, start * min, end * min, true, null), start.toFloat(), (end - start).toFloat(), lane, count)

    private fun plan(jobs: List<PlacedJob>, maxLanes: Int) =
        planJobColumn(jobs, emptyList(), maxLanes, dayStart = 0, dayEnd = 1000 * min)

    @Test
    fun laneCountsFromTheColumnWidth() {
        assertThat(maxJobLanes(30.dp)).isEqualTo(1) // never below one
        assertThat(maxJobLanes(48.dp)).isEqualTo(1)
        assertThat(maxJobLanes(98.dp)).isEqualTo(2) // 2 * 48 + 2 gap
        assertThat(maxJobLanes(97.dp)).isEqualTo(1)
        assertThat(maxJobLanes(148.dp)).isEqualTo(3)
    }

    @Test
    fun aClusterThatFitsIsUntouched() {
        val jobs = listOf(job("a", 0, 60, 0, 2), job("b", 30, 90, 1, 2))

        val p = plan(jobs, maxLanes = 2)

        assertThat(p.jobs).isEqualTo(jobs)
        assertThat(p.overflows).isEmpty()
    }

    @Test
    fun aTooWideClusterKeepsTheFirstLanesAndFoldsTheRestOverTheirCombinedRange() {
        val jobs = listOf(job("a", 0, 100, 0, 3), job("b", 10, 60, 1, 3), job("c", 50, 120, 2, 3))

        val p = plan(jobs, maxLanes = 2)

        assertThat(p.jobs.map { it.item.id to it.laneCount }).containsExactly("a" to 2)
        val o = p.overflows.single()
        assertThat(o.blocks.map { it.item.id }).containsExactly("b", "c").inOrder()
        assertThat(o.lane).isEqualTo(1)
        assertThat(o.laneCount).isEqualTo(2)
        assertThat(o.topDp).isEqualTo(10f)
        assertThat(o.heightDp).isEqualTo(110f) // 10 .. 120
        assertThat(o.count).isEqualTo(2)
    }

    @Test
    fun withOneLaneEverythingFoldsAndOtherClustersAreLeftAlone() {
        val jobs = listOf(
            job("a", 0, 60, 0, 2), job("b", 30, 90, 1, 2),
            job("solo", 200, 260, 0, 1),
        )

        val p = plan(jobs, maxLanes = 1)

        assertThat(p.jobs.map { it.item.id }).containsExactly("solo")
        assertThat(p.overflows.single().blocks.map { it.item.id }).containsExactly("a", "b")
        assertThat(p.overflows.single().laneCount).isEqualTo(1)
    }
}
