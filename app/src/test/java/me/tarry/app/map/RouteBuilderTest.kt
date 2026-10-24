package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteBuilderTest {
    private val cfg = MapConfig()

    // ~11 m per 0.0001 deg of latitude. Points walk north, 1 min apart.
    private fun pt(i: Int, lat: Double = -33.9 + i * 0.001, lng: Double = 151.2, acc: Float = 10f, t: Long = i * 60_000L) =
        RoutePoint(t, lat, lng, acc)

    @Test
    fun continuousPointsGiveOneSegment() {
        val route = buildRoute(List(5) { pt(it) }, emptyList(), cfg)
        assertThat(route).hasSize(1)
        // Collinear: only endpoints survive.
        assertThat(route[0]).hasSize(2)
    }

    @Test
    fun sortsByTimestamp() {
        val pts = List(5) { pt(it) }.reversed()
        val route = buildRoute(pts, emptyList(), cfg)
        assertThat(route.single().first().lat).isWithin(1e-9).of(-33.9)
        assertThat(route.single().last().lat).isWithin(1e-9).of(-33.9 + 0.004)
    }

    @Test
    fun timeBreakSplits() {
        val a = List(3) { pt(it) }
        val b = List(3) { pt(it + 3, t = 3 * 60_000L + cfg.routeBreakMs + 1 + it * 60_000L) }
        assertThat(buildRoute(a + b, emptyList(), cfg)).hasSize(2)
    }

    @Test
    fun exactBreakIntervalDoesNotSplit() {
        val pts = listOf(pt(0, t = 0), pt(1, t = cfg.routeBreakMs))
        assertThat(buildRoute(pts, emptyList(), cfg)).hasSize(1)
    }

    @Test
    fun gapOverlappingIntervalSplits() {
        val pts = List(6) { pt(it) }
        val route = buildRoute(pts, listOf(TimeSpan(150_000, 200_000)), cfg)
        assertThat(route).hasSize(2)
    }

    @Test
    fun gapOutsideIntervalDoesNotSplit() {
        val pts = List(6) { pt(it) }
        val route = buildRoute(pts, listOf(TimeSpan(10 * 60_000L, 11 * 60_000L)), cfg)
        assertThat(route).hasSize(1)
    }

    @Test
    fun inaccuratePointsDropped() {
        val pts = listOf(pt(0), pt(1, acc = 500f, lat = -33.0), pt(2))
        val route = buildRoute(pts, emptyList(), cfg)
        assertThat(route.single().map { it.lat }).doesNotContain(-33.0)
    }

    @Test
    fun segmentsUnderTwoPointsDropped() {
        val pts = listOf(pt(0), pt(1, t = 60_000L + cfg.routeBreakMs + 1))
        assertThat(buildRoute(pts, emptyList(), cfg)).isEmpty()
        assertThat(buildRoute(listOf(pt(0)), emptyList(), cfg)).isEmpty()
        assertThat(buildRoute(emptyList(), emptyList(), cfg)).isEmpty()
    }

    @Test
    fun simplificationKeepsRealCorner() {
        // North 10 steps, then east 10 steps (~100 m legs): the corner must survive.
        val north = List(11) { pt(it, lat = -33.9 + it * 0.0001) }
        val east = List(10) { pt(11 + it, lat = -33.9 + 0.001, lng = 151.2 + (it + 1) * 0.0001) }
        val seg = buildRoute(north + east, emptyList(), cfg).single()
        assertThat(seg).hasSize(3)
        assertThat(seg[1]).isEqualTo(GeoPoint(-33.9 + 0.001, 151.2))
        assertThat(seg.first()).isEqualTo(GeoPoint(-33.9, 151.2))
    }

    @Test
    fun smallWiggleWithinToleranceRemoved() {
        // ~1 m lateral jitter, tolerance 5 m.
        val pts = List(20) { pt(it, lng = 151.2 + if (it % 2 == 0) 0.0 else 0.00001) }
        assertThat(buildRoute(pts, emptyList(), cfg).single()).hasSize(2)
    }

    @Test
    fun hugeSegmentDoesNotOverflowAndIsFast() {
        val n = 50_000
        // A long winding path (sine, ~60 m amplitude) so simplification keeps many points and recurses deeply.
        val pts = List(n) {
            pt(it, lat = -33.9 + it * 0.00005, lng = 151.2 + 0.0006 * kotlin.math.sin(it / 40.0), t = it * 1_000L)
        }
        val start = System.nanoTime()
        val route = buildRoute(pts, emptyList(), cfg)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertThat(route).hasSize(1)
        assertThat(route[0].size).isGreaterThan(1000)
        assertThat(ms).isLessThan(5_000L)
    }
}
