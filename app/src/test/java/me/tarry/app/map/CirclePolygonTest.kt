package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import me.tarry.segmentation.geo.haversineMeters
import org.junit.Test

class CirclePolygonTest {
    @Test
    fun ringIsClosed() {
        val ring = circlePolygon(GeoPoint(-33.9, 151.2), 100.0)
        assertThat(ring).hasSize(MapConfig().circleSegments + 1)
        assertThat(ring.last()).isEqualTo(ring.first())
    }

    @Test
    fun customSegmentCount() {
        assertThat(circlePolygon(GeoPoint(0.0, 0.0), 50.0, 8)).hasSize(9)
    }

    @Test
    fun radiusAccurateAtSeveralLatitudes() {
        for (lat in listOf(0.0, -33.9, 51.5, 64.1, -77.8)) {
            for (r in listOf(30.0, 150.0, 2_000.0)) {
                val c = GeoPoint(lat, 151.2)
                for (p in circlePolygon(c, r)) {
                    val d = haversineMeters(c.lat, c.lng, p.lat, p.lng)
                    assertThat(d).isWithin(r * 0.005).of(r)
                }
            }
        }
    }

    @Test
    fun longitudeStaysNormalisedNearAntimeridian() {
        for (p in circlePolygon(GeoPoint(0.0, 179.999), 1_000.0)) {
            assertThat(p.lng).isAtMost(180.0)
            assertThat(p.lng).isAtLeast(-180.0)
        }
    }
}
