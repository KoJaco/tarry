package me.tarry.app.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DayMapModelTest {
    private fun ping(t: Long, lat: Double, acc: Float = 5f) = RoutePoint(t, lat, 151.2, acc)
    private val home = GeoPoint(-33.90, 151.2)
    private val site = GeoPoint(-33.95, 151.2)
    private val min = 60_000L

    private fun visit(id: String, start: Long, end: Long, at: GeoPoint?) = PinInput(id, start, at, null, null, end = end, radiusM = 75f)

    @Test
    fun aTripRunsFromThePreviousVisitThroughItsPingsToTheNextVisit() {
        val map = buildDayMap(
            visits = listOf(visit("a", 0, 10 * min, home), visit("b", 20 * min, 30 * min, site)),
            trips = listOf(TimeSpan(10 * min, 20 * min)),
            gaps = emptyList(),
            points = listOf(ping(11 * min, -33.91), ping(12 * min, -33.92), ping(13 * min, -33.93), ping(19 * min, -33.94)),
        )
        val trip = map.routes.single()
        assertThat(trip.solid.flatten().first()).isEqualTo(home)
        // 13 → 19 min is past tripBreakMs: the line breaks there (nothing drawn across it), with the fixes either side
        // marked, and the last stretch ends at the arrival place.
        assertThat(trip.solid).hasSize(2)
        assertThat(trip.solid.first().last()).isEqualTo(GeoPoint(-33.93, 151.2))
        assertThat(trip.solid.last()).containsExactly(GeoPoint(-33.94, 151.2), site).inOrder()
        assertThat(trip.breakEnds).containsExactly(GeoPoint(-33.93, 151.2), GeoPoint(-33.94, 151.2)).inOrder()
        assertThat(map.pins.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(map.pins.first().radiusM).isEqualTo(75f)
        assertThat(map.routeMissing).isFalse()
    }

    @Test
    fun visitJitterNeverBecomesRoute() {
        // Noisy pings during a stay (no trip) draw nothing: the visit is its circle.
        val jitter = (0 until 30).map { ping(it * min, -33.90 + (if (it % 2 == 0) 0.0005 else -0.0005)) }
        val map = buildDayMap(listOf(visit("a", 0, 30 * min, home)), trips = emptyList(), gaps = emptyList(), points = jitter)
        assertThat(map.routes).isEmpty()
        assertThat(map.visitCentres).containsExactly("a", home)
    }

    @Test
    fun aTripWithNoFixesDrawsNoLine() {
        // A ferry leg with no pings: the path is unknown, so no straight line between the places. Their pins mark the
        // ends, so there are no break dots either.
        val map = buildDayMap(
            visits = listOf(visit("a", 0, 10 * min, home), visit("b", 40 * min, 50 * min, site)),
            trips = listOf(TimeSpan(10 * min, 40 * min)),
            gaps = emptyList(),
            points = listOf(ping(5 * min, -33.90)),
        )
        assertThat(map.routes.single().solid).isEmpty()
        assertThat(map.routes.single().breakEnds).isEmpty()
        assertThat(map.bounds).isEqualTo(GeoBounds(site.lat, home.lng, home.lat, home.lng))
    }

    @Test
    fun aRecordedGapInsideATripBreaksTheLine() {
        val map = buildDayMap(
            visits = emptyList(),
            trips = listOf(TimeSpan(0, 10 * min)),
            gaps = listOf(TimeSpan(2 * min, 3 * min)),
            points = listOf(ping(1 * min, -33.91), ping(90_000, -33.915), ping(4 * min, -33.93), ping(5 * min, -33.94)),
        )
        val trip = map.routes.single()
        assertThat(trip.solid).hasSize(2)
        assertThat(trip.breakEnds).containsExactly(GeoPoint(-33.915, 151.2), GeoPoint(-33.93, 151.2)).inOrder()
    }

    @Test
    fun visitsWithoutPingsFlagTheMissingRoute() {
        val map = buildDayMap(listOf(visit("a", 0, 10, home)), trips = emptyList(), gaps = emptyList(), points = emptyList())
        assertThat(map.pins).hasSize(1)
        assertThat(map.bounds).isEqualTo(GeoBounds(home.lat, home.lng, home.lat, home.lng))
        assertThat(map.routeMissing).isTrue()
    }

    @Test
    fun anEmptyDayIsNotAMissingRoute() {
        val map = buildDayMap(emptyList(), trips = emptyList(), gaps = emptyList(), points = emptyList())
        assertThat(map.routeMissing).isFalse()
        assertThat(map.bounds).isNull()
    }

    @Test
    fun inaccuratePingsNeverReachTheScrubberPoints() {
        val map = buildDayMap(emptyList(), emptyList(), emptyList(), listOf(ping(2, -33.9), ping(1, -33.9, acc = 500f), ping(0, -33.9)))
        assertThat(map.points.map { it.timestamp }).containsExactly(0L, 2L).inOrder()
    }
    @Test
    fun aNetworkFixInsideATripBreaksTheLineInsteadOfDrawingAPhantomPoint() {
        // A 75 m Wi-Fi/cell fix that jumped offshore: left out, so the line breaks around it (one minute either side of
        // it is under tripBreakMs, but the two GPS fixes are four minutes apart without it).
        val offshore = RoutePoint(3 * min, -33.98, 151.25, 75f)
        val map = buildDayMap(
            visits = emptyList(),
            trips = listOf(TimeSpan(0, 10 * min)),
            gaps = emptyList(),
            points = listOf(ping(1 * min, -33.91), ping(90_000, -33.912), offshore, ping(5 * min, -33.93), ping(6 * min, -33.94)),
        )
        val trip = map.routes.single()
        assertThat(trip.points).doesNotContain(GeoPoint(offshore.lat, offshore.lng))
        assertThat(trip.solid).hasSize(2)
        assertThat(map.points.map { it.timestamp }).doesNotContain(3 * min)
    }
}
