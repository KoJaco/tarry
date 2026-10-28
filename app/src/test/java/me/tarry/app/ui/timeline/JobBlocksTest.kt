package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import me.tarry.core.data.db.entity.JobBlock
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin
import org.junit.Test

class JobBlocksTest {
    private val min = 60_000L

    private fun block(id: String, start: Long, end: Long, jobId: String = "j") =
        JobBlock(id = id, jobId = jobId, start = start * min, end = end * min, zoneId = "UTC", billable = true, updatedAt = 0)

    private fun seg(id: String, start: Long, end: Long) = Segment(id, start * min, end * min)

    private fun visit(id: String, start: Long, end: Long, placeId: String? = "p", origin: VisitOrigin = VisitOrigin.DETECTED, deleted: Boolean = false) =
        Visit(
            id = id, placeId = placeId, start = start * min, end = end * min, zoneId = "UTC", origin = origin,
            confidence = 1f, updatedAt = 0, deleted = deleted,
        )

    private fun place(id: String = "p", defaultJobId: String? = "j", ignored: Boolean = false) =
        Place(id = id, centroidLat = 0.0, centroidLng = 0.0, defaultJobId = defaultJobId, ignored = ignored, updatedAt = 0)

    // region lanes

    @Test
    fun noBlocksNoLanes() {
        assertThat(jobLanes(emptyList())).isEmpty()
    }

    @Test
    fun aLoneBlockIsLaneZeroOfOne() {
        assertThat(jobLanes(listOf(block("a", 0, 60)))).containsExactly("a", Lane(0, 1))
    }

    @Test
    fun twoOverlappingBlocksShareTwoLanes() {
        val lanes = jobLanes(listOf(block("b", 30, 90), block("a", 0, 60)))

        assertThat(lanes).containsExactly("a", Lane(0, 2), "b", Lane(1, 2))
    }

    @Test
    fun touchingBlocksDoNotOverlapSoEachHasItsOwnClusterOfOne() {
        val lanes = jobLanes(listOf(block("a", 0, 60), block("b", 60, 120)))

        assertThat(lanes).containsExactly("a", Lane(0, 1), "b", Lane(0, 1))
    }

    @Test
    fun aChainOfThreeReusesTheFirstLaneAndSharesTheClustersCount() {
        // a overlaps b, b overlaps c, but a and c do not: c drops into a's lane and all three share count 2.
        val lanes = jobLanes(listOf(block("a", 0, 60), block("b", 30, 90), block("c", 60, 120)))

        assertThat(lanes).containsExactly("a", Lane(0, 2), "b", Lane(1, 2), "c", Lane(0, 2))
    }

    @Test
    fun threeAtOnceNeedThreeLanesAndALaterIsolatedBlockStartsOver() {
        val lanes = jobLanes(
            listOf(block("a", 0, 100), block("b", 10, 100), block("c", 20, 100), block("d", 200, 260)),
        )

        assertThat(lanes).containsExactly("a", Lane(0, 3), "b", Lane(1, 3), "c", Lane(2, 3), "d", Lane(0, 1))
    }

    // endregion

    // region coverage

    @Test
    fun coverageListsEveryOverlappingBlockForVisitsAndTrips() {
        val blocks = listOf(block("a", 0, 60), block("b", 50, 120), block("far", 500, 600))

        val c = coverage(listOf(seg("v", 40, 55), seg("lone", 200, 210)), listOf(seg("t", 59, 61)), blocks)

        assertThat(c.byVisit.getValue("v")).containsExactly("a", "b")
        assertThat(c.byVisit.getValue("lone")).isEmpty()
        assertThat(c.byTrip.getValue("t")).containsExactly("a", "b")
    }

    @Test
    fun aBlockThatOnlyTouchesASegmentDoesNotCoverIt() {
        val c = coverage(listOf(seg("v", 60, 90)), emptyList(), listOf(block("a", 0, 60)))

        assertThat(c.byVisit.getValue("v")).isEmpty()
    }

    @Test
    fun anyOverlapCountsAsCovered() {
        val c = coverage(listOf(seg("v", 0, 120)), emptyList(), listOf(block("a", 119, 130)))

        assertThat(c.byVisit.getValue("v")).containsExactly("a")
    }

    @Test
    fun needsReviewIsAnUncoveredVisitAtAPlaceThatIsNotIgnored() {
        val c = coverage(listOf(seg("covered", 0, 30), seg("open", 100, 130)), emptyList(), listOf(block("a", 0, 60)))

        assertThat(c.needsReview("covered", placeIgnored = false)).isFalse()
        assertThat(c.needsReview("open", placeIgnored = false)).isTrue()
        assertThat(c.needsReview("open", placeIgnored = true)).isFalse()
    }

    // endregion

    // region suggestions

    @Test
    fun aDetectedVisitAtAPlaceWithADefaultJobGetsASuggestionOverItsRange() {
        val s = suggestions(listOf(visit("v", 10, 40)), mapOf("p" to place()), emptyList())

        assertThat(s).containsExactly(JobSuggestion("v", "j", 10 * min, 40 * min))
    }

    @Test
    fun manualAndDeletedVisitsAreNotSuggested() {
        val s = suggestions(
            listOf(visit("manual", 0, 30, origin = VisitOrigin.MANUAL), visit("gone", 40, 70, deleted = true)),
            mapOf("p" to place()), emptyList(),
        )

        assertThat(s).isEmpty()
    }

    @Test
    fun aVisitAtAnIgnoredPlaceIsNotSuggested() {
        val s = suggestions(listOf(visit("v", 10, 40)), mapOf("p" to place(ignored = true)), emptyList())

        assertThat(s).isEmpty()
    }

    @Test
    fun noSuggestionWithoutADefaultJobOrAPlace() {
        val s = suggestions(
            listOf(visit("noDefault", 0, 30), visit("noPlace", 40, 70, placeId = null), visit("unknown", 80, 90, placeId = "missing")),
            mapOf("p" to place(defaultJobId = null)), emptyList(),
        )

        assertThat(s).isEmpty()
    }

    @Test
    fun aBlockOfThatJobOverlappingTheVisitSuppressesTheSuggestion() {
        val v = visit("v", 10, 40)

        assertThat(suggestions(listOf(v), mapOf("p" to place()), listOf(block("a", 39, 60)))).isEmpty()
    }

    @Test
    fun aBlockOfAnotherJobOrOneThatOnlyTouchesDoesNotSuppressIt() {
        val v = visit("v", 10, 40)
        val blocks = listOf(block("other", 0, 60, jobId = "k"), block("touch", 40, 60))

        assertThat(suggestions(listOf(v), mapOf("p" to place()), blocks).map { it.visitId }).containsExactly("v")
    }

    // endregion

    // region covering job

    private fun item(id: String, start: Long, end: Long) = JobBlockItem(id, "j", "J", 0, start * min, end * min, true, null, Lane(0, 1))

    @Test
    fun theCoveringJobIsTheFirstOverlappingBlockByStart() {
        val blocks = listOf(item("late", 30, 90), item("early", 10, 50), item("after", 60, 90))

        assertThat(coveringJob(20 * min, 40 * min, blocks)?.id).isEqualTo("early")
        assertThat(coveringJob(50 * min, 60 * min, blocks)?.id).isEqualTo("late")
        assertThat(coveringJob(0, 10 * min, blocks)).isNull()
    }

    // endregion

    // region drag range

    private val dayEnd = 24 * 60 * min
    private fun drag(anchor: Long, current: Long) = dragJobRange(anchor * min, current * min, 0L, dayEnd)
    private fun range(start: Long, end: Long) = Span(start * min, end * min)

    @Test
    fun dragSnapsBothEndsToTheNearestQuarterHour() {
        assertThat(drag(9 * 60 + 6, 10 * 60 + 40)).isEqualTo(range(9 * 60, 10 * 60 + 45))
    }

    @Test
    fun draggingUpwardsSwapsTheEnds() {
        assertThat(drag(11 * 60, 10 * 60)).isEqualTo(range(10 * 60, 11 * 60))
    }

    @Test
    fun aPressWithoutADragIsOneHourFromTheSnappedTime() {
        assertThat(drag(9 * 60 + 5, 9 * 60 + 6)).isEqualTo(range(9 * 60, 10 * 60))
    }

    @Test
    fun aRangeIsNeverPastTheEndOfTheDay() {
        assertThat(drag(23 * 60 + 50, 23 * 60 + 50)).isEqualTo(range(23 * 60 + 45, 24 * 60))
        assertThat(drag(23 * 60, 25 * 60)).isEqualTo(range(23 * 60, 24 * 60))
    }

    // endregion

    // region assign range and add to segment

    @Test
    fun assignRangeSpansEarliestStartToLatestEnd() {
        val range = assignRange(listOf(seg("a", 100, 130), seg("b", 20, 50), seg("c", 60, 200)))

        assertThat(range).isEqualTo(Span(20 * min, 200 * min))
        assertThat(assignRange(emptyList())).isNull()
    }

    private val config = JobBlockConfig(joinWindowMs = 15 * min)
    private fun span(start: Long, end: Long) = Span(start * min, end * min)

    @Test
    fun addJobCreatesABlockEqualToTheSegmentWhenNoneIsNear() {
        val r = addJobToSegment(span(100, 130), "j", listOf(block("a", 0, 60)), config)

        assertThat(r).isEqualTo(AddJobResult.Create(100 * min, 130 * min))
    }

    @Test
    fun addJobExtendsABlockEndingJustBeforeTheSegment() {
        val r = addJobToSegment(span(70, 100), "j", listOf(block("a", 0, 60)), config)

        assertThat(r).isEqualTo(AddJobResult.Extend("a", 0, 100 * min))
    }

    @Test
    fun addJobExtendsABlockStartingJustAfterTheSegment() {
        val r = addJobToSegment(span(0, 50), "j", listOf(block("a", 60, 120)), config)

        assertThat(r).isEqualTo(AddJobResult.Extend("a", 0, 120 * min))
    }

    @Test
    fun addJobExtendsAnOverlappingBlockToTheUnion() {
        val r = addJobToSegment(span(30, 90), "j", listOf(block("a", 0, 60)), config)

        assertThat(r).isEqualTo(AddJobResult.Extend("a", 0, 90 * min))
    }

    @Test
    fun addJobWithTheSegmentInsideTheBlockKeepsTheBlockRange() {
        val r = addJobToSegment(span(10, 20), "j", listOf(block("a", 0, 60)), config)

        assertThat(r).isEqualTo(AddJobResult.Extend("a", 0, 60 * min))
    }

    @Test
    fun addJobJoinsExactlyAtTheWindowEdgeButNotBeyondIt() {
        assertThat(addJobToSegment(span(75, 90), "j", listOf(block("a", 0, 60)), config))
            .isEqualTo(AddJobResult.Extend("a", 0, 90 * min))
        assertThat(addJobToSegment(span(76, 90), "j", listOf(block("a", 0, 60)), config))
            .isEqualTo(AddJobResult.Create(76 * min, 90 * min))
    }

    @Test
    fun addJobIgnoresBlocksOfAnotherJob() {
        val r = addJobToSegment(span(30, 90), "j", listOf(block("other", 0, 60, jobId = "k")), config)

        assertThat(r).isEqualTo(AddJobResult.Create(30 * min, 90 * min))
    }

    @Test
    fun addJobPicksTheNearestOfTwoCandidates() {
        val blocks = listOf(block("far", 0, 48), block("near", 95, 140))

        val r = addJobToSegment(span(60, 90), "j", blocks, config)

        assertThat(r).isEqualTo(AddJobResult.Extend("near", 60 * min, 140 * min))
    }

    // endregion
}
