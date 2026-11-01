package me.tarry.segmentation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class GapsTest {
    private val params = SegmentationParams()
    private fun m(minutes: Long) = minutes * 60_000L
    private fun r(s: Long, e: Long) = TimeRange(m(s), m(e))
    private fun ping(t: Long) = Ping(m(t), 0.0, 0.0, 10f)
    private fun pingsEvery(from: Long, to: Long, skip: LongRange = LongRange.EMPTY) =
        (from..to).filter { it !in skip }.map { ping(it) }

    private fun input(
        range: TimeRange = r(0, 600),
        windows: List<TimeRange> = emptyList(),
        sessions: List<TimeRange> = emptyList(),
        frozen: List<TimeRange> = emptyList(),
        health: List<TimeRange> = emptyList(),
    ) = SegmentationInput(range, emptyList(), frozen = frozen, windows = windows, sessions = sessions, healthGaps = health)

    private fun run(i: SegmentationInput, pings: List<Ping> = emptyList()) = buildGaps(i, pings, params)

    @Test fun coveredWindowHasNoGaps() {
        val g = run(input(windows = listOf(r(60, 120)), sessions = listOf(r(50, 130))), pingsEvery(50, 129))
        assertEquals(emptyList<Gap>(), g)
    }

    @Test fun sessionInMiddleGivesTwoNoSession() {
        val g = run(input(windows = listOf(r(0, 300)), sessions = listOf(r(100, 200))), pingsEvery(100, 199))
        assertEquals(listOf(Gap(r(0, 100), GapCause.NO_SESSION), Gap(r(200, 300), GapCause.NO_SESSION)), g)
    }

    @Test fun longSilenceGivesNoFix() {
        val pings = pingsEvery(0, 200, skip = 51L..99L) // pings at 50 and 100: 50 min silence
        val g = run(input(sessions = listOf(r(0, 201))), pings)
        assertEquals(listOf(Gap(r(50, 100), GapCause.NO_FIX)), g)
    }

    @Test fun shortSilenceIgnored() {
        val pings = pingsEvery(0, 200, skip = 51L..89L) // pings at 50 and 90: 40 min silence
        assertEquals(emptyList<Gap>(), run(input(sessions = listOf(r(0, 201))), pings))
    }

    /** About 2.8 km east of (0, 0). */
    private fun farPing(t: Long, accuracyM: Float = 20f) = Ping(m(t), 0.0, 0.025, accuracyM)

    @Test fun shortSilenceWithMoveGivesNoFix() {
        // The 24 Oct 2026 shape: 12 min without fixes, and the next good fix is kilometres away.
        val pings = pingsEvery(0, 60) + (72L..120L).map { farPing(it) }
        val g = run(input(sessions = listOf(r(0, 121))), pings)
        assertEquals(listOf(Gap(r(60, 72), GapCause.NO_FIX)), g)
    }

    @Test fun moveSilenceShorterThanMinimumIgnored() {
        val pings = pingsEvery(0, 60) + (64L..120L).map { farPing(it) }
        assertEquals(emptyList<Gap>(), run(input(sessions = listOf(r(0, 121))), pings))
    }

    @Test fun moveShorterThanTripDistanceIgnored() {
        // 100 m east: within a trip's minimum distance once accuracy is allowed for.
        val pings = pingsEvery(0, 60) + (72L..120L).map { Ping(m(it), 0.0, 0.0009, 10f) }
        assertEquals(emptyList<Gap>(), run(input(sessions = listOf(r(0, 121))), pings))
    }

    @Test fun poorFixAfterSilenceIsNoEvidenceOfAMove() {
        // A network fix far away, then good fixes back where the phone was (the 28 Oct 2026 spike).
        val pings = pingsEvery(0, 60) + farPing(72, accuracyM = 100f) + pingsEvery(73, 120)
        assertEquals(emptyList<Gap>(), run(input(sessions = listOf(r(0, 121))), pings))
    }

    @Test fun moveSilenceAcrossPoorFixesCounts() {
        val pings = pingsEvery(0, 60) + Ping(m(66), 0.0, 0.01, 100f) + (72L..120L).map { farPing(it) }
        val g = run(input(sessions = listOf(r(0, 121))), pings)
        assertEquals(listOf(Gap(r(60, 72), GapCause.NO_FIX)), g)
    }

    @Test fun moveSilenceOverlappingLongSilenceIsOneGap() {
        // Good fixes at 10 and 70 with one poor fix at 40: the long-silence rule sees nothing over 45 min.
        val pings = pingsEvery(0, 10) + Ping(m(40), 0.0, 0.01, 100f) + (70L..120L).map { farPing(it) }
        val g = run(input(sessions = listOf(r(0, 121))), pings)
        assertEquals(listOf(Gap(r(10, 70), GapCause.NO_FIX)), g)
    }

    @Test fun moveSilenceOutsideSessionIgnored() {
        val pings = pingsEvery(0, 60) + (72L..120L).map { farPing(it) }
        val g = run(input(sessions = listOf(r(0, 61), r(72, 121))), pings)
        assertEquals(emptyList<Gap>(), g)
    }

    @Test fun sessionWithoutPingsIsOneNoFix() {
        val g = run(input(sessions = listOf(r(60, 180))))
        assertEquals(listOf(Gap(r(60, 180), GapCause.NO_FIX)), g)
    }

    @Test fun silenceAtSessionEdgesCounts() {
        val g = run(input(sessions = listOf(r(0, 200))), listOf(ping(60), ping(61)))
        assertEquals(listOf(Gap(r(0, 60), GapCause.NO_FIX), Gap(r(61, 200), GapCause.NO_FIX)), g)
    }

    @Test fun healthGapClipsNoFix() {
        val g = run(input(sessions = listOf(r(60, 180)), health = listOf(r(0, 90))))
        assertEquals(listOf(Gap(r(90, 180), GapCause.NO_FIX)), g)
    }

    @Test fun healthGapInMiddleSplits() {
        val g = run(input(sessions = listOf(r(60, 180)), health = listOf(r(100, 140))))
        assertEquals(listOf(Gap(r(60, 100), GapCause.NO_FIX), Gap(r(140, 180), GapCause.NO_FIX)), g)
    }

    @Test fun frozenSubtracted() {
        val g = run(input(windows = listOf(r(0, 100)), frozen = listOf(r(20, 100))))
        assertEquals(listOf(Gap(r(0, 20), GapCause.NO_SESSION)), g)
    }

    @Test fun clippedToRange() {
        val g = run(input(range = r(100, 150), windows = listOf(r(0, 300))))
        assertEquals(listOf(Gap(r(100, 150), GapCause.NO_SESSION)), g)
    }

    @Test fun piecesUnderOneMinuteDropped() {
        val g = run(input(windows = listOf(r(0, 100)), frozen = listOf(TimeRange(30_000L, m(100) - 30_000L))))
        assertEquals(emptyList<Gap>(), g)
    }

    @Test fun outputOrderedAndNonOverlapping() {
        val i = input(
            windows = listOf(r(0, 400)),
            sessions = listOf(r(100, 200), r(300, 350)),
            health = listOf(r(120, 130)),
        )
        val g = run(i, listOf(ping(100), ping(199), ping(300)))
        assertTrue(g.isNotEmpty())
        for ((a, b) in g.zipWithNext()) assertTrue("$a overlaps $b", a.range.endMs <= b.range.startMs)
        val ns = g.filter { it.cause == GapCause.NO_SESSION }
        val nf = g.filter { it.cause == GapCause.NO_FIX }
        assertTrue(ns.none { a -> nf.any { a.range.overlaps(it.range) } })
        assertTrue(g.none { gap -> gap.range.overlaps(r(120, 130)) })
    }
}
