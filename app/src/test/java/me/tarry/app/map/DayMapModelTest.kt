package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DayMapModelTest {
    private fun ping(t: Long, lat: Double, acc: Float = 5f) = RoutePoint(t, lat, 151.2, acc)
    private val home = GeoPoint(-33.90, 151.2)
    private val site = GeoPoint(-33.95, 151.2)

    @Test
    fun routeAndPinsTogetherSetTheBounds() {
        val map = buildDayMap(
            visits = listOf(PinInput("a", 0, home, null, null), PinInput("b", 10, site, null, null)),
            tripCount = 1,
            gaps = emptyList(),
            points = listOf(ping(0, -33.90), ping(60_000, -33.92), ping(120_000, -33.96)),
        )
        assertThat(map.route).hasSize(1)
        assertThat(map.pins.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(map.bounds).isEqualTo(GeoBounds(-33.96, 151.2, -33.90, 151.2))
        assertThat(map.routeMissing).isFalse()
    }

    @Test
    fun visitsWithoutPingsAreDrawnFromCentroidsAndFlagTheMissingRoute() {
        val map = buildDayMap(listOf(PinInput("a", 0, home, null, null)), tripCount = 0, gaps = emptyList(), points = emptyList())
        assertThat(map.route).isEmpty()
        assertThat(map.pins).hasSize(1)
        assertThat(map.bounds).isEqualTo(GeoBounds(home.lat, home.lng, home.lat, home.lng))
        assertThat(map.routeMissing).isTrue()
    }

    @Test
    fun anEmptyDayIsNotAMissingRoute() {
        val map = buildDayMap(emptyList(), tripCount = 0, gaps = emptyList(), points = emptyList())
        assertThat(map.routeMissing).isFalse()
        assertThat(map.bounds).isNull()
    }

    @Test
    fun inaccuratePingsNeverReachTheScrubberPoints() {
        val map = buildDayMap(emptyList(), 0, emptyList(), listOf(ping(2, -33.9), ping(1, -33.9, acc = 500f), ping(0, -33.9)))
        assertThat(map.points.map { it.timestamp }).containsExactly(0L, 2L).inOrder()
    }
}
