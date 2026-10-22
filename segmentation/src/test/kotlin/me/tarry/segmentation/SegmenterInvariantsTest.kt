package me.tarry.segmentation

import me.tarry.segmentation.trace.TraceBuilder
import me.tarry.segmentation.trace.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.tarry.segmentation.geo.haversineMeters
import kotlin.math.abs
import java.util.Random

/** Invariants of [Segmenter.segment] over seeded random traces (docs/specs/phase-2b.md T9). */
class SegmenterInvariantsTest {
    private val params = SegmentationParams()

    private class Case(val seed: Long, val input: SegmentationInput)

    private fun cases(): List<Case> = (1L..SEEDS).map { Case(it, randomInput(it)) }

    private fun randomInput(seed: Long): SegmentationInput {
        val rnd = Random(seed * 7919)
        val t0 = 1_760_000_000_000L + rnd.nextInt(86_400) * 1000L
        val b = TraceBuilder(seed = seed, startTimeMs = t0)
        repeat(3 + rnd.nextInt(8)) {
            when (rnd.nextInt(10)) {
                in 0..3 -> b.stay(
                    durationMs = (2 + rnd.nextInt(120)) * MIN,
                    intervalMs = (1 + rnd.nextInt(4)) * MIN,
                    jitterM = 3.0 + rnd.nextInt(25),
                    accuracyM = 8f + rnd.nextInt(40),
                )
                in 4..6 -> b.moveBy(
                    bearingDeg = rnd.nextDouble() * 360,
                    distanceM = 100.0 + rnd.nextInt(8000),
                    speedMps = 1.5 + rnd.nextInt(20),
                )
                7, 8 -> b.gap((1 + rnd.nextInt(90)) * MIN)
                else -> b.spikes(1 + rnd.nextInt(5))
            }
        }
        b.stay((1 + rnd.nextInt(30)) * MIN)
        val pings = b.build()
        val traceRange = pings.range()
        val range = TimeRange(traceRange.startMs - rnd.nextInt(60) * MIN, traceRange.endMs + rnd.nextInt(60) * MIN)
        fun ranges(max: Int, maxLenMin: Int) = List(rnd.nextInt(max + 1)) {
            val s = range.startMs + (rnd.nextDouble() * range.durationMs).toLong()
            TimeRange(s, s + (1 + rnd.nextInt(maxLenMin)) * MIN)
        }
        return SegmentationInput(
            range = range,
            pings = pings,
            frozen = ranges(2, 90),
            excluded = ranges(2, 60),
            windows = ranges(2, 600),
            sessions = ranges(3, 600),
            healthGaps = ranges(2, 90),
        )
    }

    /** Stale repeats (spike, then its exact copies) keep the structural invariants: ordered, disjoint, in range. */
    @Test
    fun staleRepeatsKeepTheStructuralInvariants() {
        for (seed in 1L..20L) {
            val b = TraceBuilder(seed = seed, startTimeMs = 1_760_000_000_000L).stay(30 * MIN, intervalMs = 3 * MIN)
            b.spikes(1, accuracyM = 100f, offsetM = 300.0)
            val spike = b.pingCount - 1
            val pings = b.stay(20 * MIN, intervalMs = 3 * MIN).repeatEarlier(spike, 8)
                .moveBy(45.0, 3_000.0, 10.0).stay(30 * MIN, intervalMs = 3 * MIN).repeatEarlier(spike, 4).build()
            val range = pings.range()
            val r = Segmenter.segment(SegmentationInput(range, pings), params)
            val all = allRanges(r)
            for (i in all.indices) for (j in i + 1 until all.size) {
                assertTrue("seed $seed: ${all[i]} overlaps ${all[j]}", !all[i].second.overlaps(all[j].second))
            }
            assertTrue(all.all { it.second.startMs >= range.startMs && it.second.endMs <= range.endMs })
        }
    }

    private fun allRanges(r: SegmentationResult) =
        r.stays.map { "stay" to it.range } + r.trips.map { "trip" to it.range } + r.gaps.map { "gap" to it.range }

    @Test
    fun noOverlapsAmongOrWithinKinds() {
        for (c in cases()) {
            val all = allRanges(Segmenter.segment(c.input, params))
            for (i in all.indices) for (j in i + 1 until all.size) {
                assertTrue("seed ${c.seed}: ${all[i]} overlaps ${all[j]}", !all[i].second.overlaps(all[j].second))
            }
        }
    }

    @Test
    fun everythingIsInsideTheInputRange() {
        for (c in cases()) {
            for ((kind, r) in allRanges(Segmenter.segment(c.input, params))) {
                assertTrue(
                    "seed ${c.seed}: $kind $r outside ${c.input.range}",
                    r.startMs >= c.input.range.startMs && r.endMs <= c.input.range.endMs,
                )
            }
        }
    }

    @Test
    fun frozenRangesAreNeverOverlapped() {
        for (c in cases()) {
            for ((kind, r) in allRanges(Segmenter.segment(c.input, params))) {
                c.input.frozen.forEach {
                    assertTrue("seed ${c.seed}: $kind $r overlaps frozen $it", !r.overlaps(it))
                }
            }
        }
    }

    @Test
    fun gapsNeverOverlapHealthGaps() {
        for (c in cases()) {
            for (g in Segmenter.segment(c.input, params).gaps) {
                c.input.healthGaps.forEach {
                    assertTrue("seed ${c.seed}: gap ${g.range} overlaps health gap $it", !g.range.overlaps(it))
                }
            }
        }
    }

    @Test
    fun indicesAndPlaceRefsAreValid() {
        for (c in cases()) {
            val r = Segmenter.segment(c.input, params)
            for (t in r.trips) {
                t.fromStay?.let { assertTrue("seed ${c.seed}: fromStay $it", it in r.stays.indices) }
                t.toStay?.let { assertTrue("seed ${c.seed}: toStay $it", it in r.stays.indices) }
            }
            for (s in r.stays) {
                val p = s.place
                if (p is PlaceRef.New) assertTrue("seed ${c.seed}: New(${p.index})", p.index in r.newPlaces.indices)
            }
        }
    }

    @Test
    fun segmentingTwiceGivesEqualResults() {
        for (c in cases()) {
            assertEquals("seed ${c.seed}", Segmenter.segment(c.input, params), Segmenter.segment(c.input, params))
        }
    }

    /**
     * Checkpoint C is the start of the last stay with mayBeOngoing, else the end of the last stay. A run from
     * C - overlapMs, widened to the start of any stay that cuts into (as the :app glue does), with the same pings must reproduce the full run for everything that ends after C:
     * - stays: same count, same end, confidence, bridged and mayBeOngoing; start within [START_TOLERANCE_MS] and
     *   centroid within [CENTROID_TOLERANCE_M]. A greedy detector settles a stay's start on the approach pings
     *   it sees, so where the run begins can move the start by a ping or two (seeds 15, 112, 156 and 226 found
     *   this); the visit keeps its id because the glue reuses ids by overlap. The place ref is excluded because
     *   New indices and clustering depend on the stays before the run.
     * - gaps clipped to start at C: exactly equal (range and cause). Clipping matters when the partial run starts
     *   inside a long stay it then cannot see, so its gap reaches further back than C.
     */
    @Test
    fun overlapReprocessingMatchesAFullRun() {
        for (c in cases()) {
            val full = Segmenter.segment(c.input, params)
            val last = full.stays.lastOrNull() ?: continue
            val checkpoint = if (last.mayBeOngoing) last.range.startMs else last.range.endMs
            // Like the :app glue: back off by the overlap, then widen to the start of any stay that range cuts into.
            val backed = checkpoint - params.overlapMs
            // Also from the stay before the first one: arrival attribution caps a stay's start at half the distance to
            // the previous stay's centroid, so the run must see that stay too (the runner's trip-origin widening already covers it).
            val firstKept = full.stays.indexOfFirst { it.range.endMs > backed }
            val widened = if (firstKept < 0) backed else full.stays[maxOf(0, firstKept - 1)].range.startMs
            val start = maxOf(c.input.range.startMs, minOf(backed, widened))
            val partial = Segmenter.segment(c.input.copy(range = TimeRange(start, c.input.range.endMs)), params)
            val label = "seed ${c.seed}, C=$checkpoint"

            val expected = full.stays.filter { it.range.endMs > checkpoint }
            val actual = partial.stays.filter { it.range.endMs > checkpoint }
            assertEquals("$label: stay count", expected.size, actual.size)
            for ((e, a) in expected.zip(actual)) {
                assertEquals("$label: end", e.range.endMs, a.range.endMs)
                assertTrue("$label: start ${e.range.startMs} vs ${a.range.startMs}",
                    abs(e.range.startMs - a.range.startMs) <= START_TOLERANCE_MS)
                assertTrue("$label: centroid", haversineMeters(e.lat, e.lng, a.lat, a.lng) <= CENTROID_TOLERANCE_M)
                assertEquals("$label: flags", listOf(e.confidence, e.bridged, e.mayBeOngoing), listOf(a.confidence, a.bridged, a.mayBeOngoing))
            }
            val after = TimeRange(checkpoint, c.input.range.endMs)
            fun gaps(r: SegmentationResult) = r.gaps.mapNotNull { g -> g.range.clip(after)?.let { Gap(it, g.cause) } }
            assertEquals("$label: gaps", gaps(full), gaps(partial))
        }
    }

    /**
     * Feeding a run's new places and moved centroids back in as known places (what the next run sees) changes
     * nothing: the same stays, and no further new places.
     */
    @Test
    fun placeFeedbackIsAFixpoint() {
        for (c in cases()) {
            val first = Segmenter.segment(c.input, params)
            val moved = c.input.places.map { p ->
                first.placeUpdates.find { it.placeId == p.id }?.let { p.copy(lat = it.lat, lng = it.lng) } ?: p
            }
            val created = first.newPlaces.mapIndexed { i, n -> KnownPlace("new-$i", n.lat, n.lng, n.radiusM.toFloat()) }
            val second = Segmenter.segment(c.input.copy(places = moved + created), params)
            assertEquals("seed ${c.seed}: stays", first.stays.map { it.range }, second.stays.map { it.range })
            assertTrue("seed ${c.seed}: new places ${second.newPlaces}", second.newPlaces.isEmpty())
        }
    }

    private companion object {
        const val SEEDS = 200L
        const val MIN = 60_000L
        const val START_TOLERANCE_MS = 2 * MIN
        const val CENTROID_TOLERANCE_M = 5.0
    }
}
