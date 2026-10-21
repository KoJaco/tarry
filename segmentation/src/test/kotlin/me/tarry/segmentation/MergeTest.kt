package me.tarry.segmentation

import me.tarry.segmentation.geo.haversineMeters
import me.tarry.segmentation.trace.Trace
import me.tarry.segmentation.trace.TraceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    private val params = SegmentationParams()
    private val t0 = 1_760_000_000_000L
    private val min = 60_000L
    private fun builder() = TraceBuilder(seed = 11, startTimeMs = t0)
    private fun TraceBuilder.walk(bearing: Double, m: Double) =
        moveBy(bearing, m, 1.4, mode = Activity.WALKING)

    private fun detect(pings: Trace) = detectStays(pings, params)

    @Test
    fun closeStaysWithShortWalkBetweenMerge() {
        val pings = builder().stay(10 * min).walk(90.0, 100.0).stay(10 * min).build()
        assertEquals(2, detect(pings).size)
        assertEquals(1, mergeStays(detect(pings), pings, params).size)
    }

    @Test
    fun staysFarApartDoNotMerge() {
        val pings = builder().stay(10 * min).moveBy(90.0, 400.0, 10.0).stay(10 * min).build()
        assertEquals(2, mergeStays(detect(pings), pings, params).size)
    }

    @Test
    fun quickReturnToSameSpotMerges() {
        val pings = builder().stay(10 * min).moveBy(90.0, 250.0, 6.25).moveBy(270.0, 250.0, 6.25).stay(10 * min).build()
        val stays = detect(pings)
        assertEquals(2, stays.size)
        assertTrue(stays[1].range.startMs - stays[0].range.endMs < params.shortTripMergeMs)
        assertEquals(1, mergeStays(stays, pings, params).size)
    }

    @Test
    fun blipThenStayThreeHundredMetresAwayDoesNotMerge() {
        val pings = builder().stay(10 * min).moveBy(90.0, 300.0, 3.0).stay(10 * min).build()
        val stays = detect(pings)
        assertTrue(stays[1].range.startMs - stays[0].range.endMs < params.shortTripMergeMs)
        assertEquals(2, mergeStays(stays, pings, params).size)
    }

    @Test
    fun chainCollapsesToOneStay() {
        val pings = builder().stay(10 * min).walk(90.0, 100.0).stay(10 * min).walk(270.0, 100.0).stay(10 * min).build()
        val stays = detect(pings)
        assertEquals(3, stays.size)
        assertEquals(1, mergeStays(stays, pings, params).size)
    }

    @Test
    fun distinctPlacesBlocksMerge() {
        val pings = builder().stay(10 * min).walk(90.0, 100.0).stay(10 * min).build()
        val stays = detect(pings)
        assertEquals(2, mergeStays(stays, pings, params) { _, _ -> true }.size)
    }

    @Test
    fun mergedStayHasCombinedRangeCentroidAndFlags() {
        val pings = builder().stay(10 * min).gap(20 * min).stay(10 * min).walk(90.0, 100.0).stay(10 * min).build()
        val stays = detect(pings)
        assertEquals(2, stays.size)
        assertTrue(stays[0].bridged)
        assertFalse(stays[1].bridged)
        assertFalse(stays[0].open)
        assertTrue(stays[1].open)
        val m = mergeStays(stays, pings, params).single()
        assertEquals(stays[0].range.startMs, m.range.startMs)
        assertEquals(stays[1].range.endMs, m.range.endMs)
        assertTrue(m.bridged)
        assertTrue(m.open)
        assertTrue(m.pings.size >= stays[0].pings.size + stays[1].pings.size)
        assertEquals(m.pings.sortedBy { it.timeMs }, m.pings)
        val (lat, lng) = weightedCentroid(m.pings, params)
        assertEquals(lat, m.lat, 1e-12)
        assertEquals(lng, m.lng, 1e-12)
        val d0 = haversineMeters(m.lat, m.lng, stays[0].lat, stays[0].lng)
        val d1 = haversineMeters(m.lat, m.lng, stays[1].lat, stays[1].lng)
        assertTrue(d0 < 100.0 && d1 < 100.0)
    }
}
